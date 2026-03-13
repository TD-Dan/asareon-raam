package app.auf.feature.agent

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.auf.core.*
import app.auf.core.Feature.ComposableProvider
import app.auf.core.generated.ActionRegistry
import app.auf.ui.components.colorToHex
import app.auf.ui.components.hslToColor
import app.auf.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/**
 * ## The Executor
 * A pure switchboard feature.
 * - Config/Persistence -> AgentCrudLogic
 * - Runtime State -> AgentRuntimeReducer
 * - Cognition -> AgentCognitivePipeline
 * - Side Effects -> AgentAvatarLogic / Self
 *
 * All agent IDs are typed via [IdentityUUID]; session references use [IdentityUUID]
 * (immutable, rename-safe). Session handles are resolved from the identity registry
 * at point-of-use for cross-feature dispatch.
 *
 * All strategy-specific behavior is dispatched polymorphically through the
 * [CognitiveStrategy] lifecycle hooks — no implicit strategy checks.
 *
 * All command-dispatchable agent actions publish `agent.ACTION_RESULT` broadcasts
 * with `correlationId` for CommandBot correlation.
 */
class AgentRuntimeFeature(
    private val platformDependencies: PlatformDependencies,
    private val coroutineScope: CoroutineScope
) : Feature {
    override val identity: Identity = Identity(uuid = null, handle = "agent", localHandle = "agent", name="Agent Runtime")

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val agentConfigFILENAME = "agent.json"
    private val nvramFILENAME = "nvram.json"
    private val contextFILENAME = "context.json"

    private val workspacePathMarker = "/workspace/"

    private val commandBotSenderId = "commandbot"

    private val activeTurnJobs = mutableMapOf<IdentityUUID, Job>()
    private val avatarUpdateJobs = mutableMapOf<IdentityUUID, Job>()
    private var agentLoadCount = 0

    companion object {
        /** TTL for pending command entries: 5 minutes. */
        const val PENDING_COMMAND_TTL_MS = 5 * 60 * 1000L
    }

    // ---- Boundary helpers ----
    private fun JsonObject.agentUUID(field: String = "agentId"): IdentityUUID? =
        this[field]?.jsonPrimitive?.contentOrNull?.let { IdentityUUID(it) }

    private fun JsonObject.correlationId(): String? =
        this["correlationId"]?.jsonPrimitive?.contentOrNull

    /**
     * Resolves an agent from a payload field using flexible identity matching.
     * For public, command-dispatchable actions — resolves by UUID, handle,
     * localHandle, or display name via the identity registry.
     * On failure, publishes an ACTION_RESULT error with close-match suggestions.
     *
     * Internal actions (where the ID is always a UUID from our own dispatches)
     * should continue using [agentUUID] directly.
     */
    private fun resolveAgentId(
        payload: JsonObject?,
        store: Store,
        correlationId: String?,
        actionName: String,
        field: String = "agentId"
    ): IdentityUUID? {
        val raw = payload?.get(field)?.jsonPrimitive?.contentOrNull ?: return null
        val registry = store.state.value.identityRegistry
        val resolved = registry.resolve(raw, parentHandle = "agent")
        if (resolved?.identityUUID != null) return resolved.identityUUID

        // Not found in registry — publish error with suggestions
        val suggestions = registry.suggestMatches(raw, parentHandle = "agent")
            .joinToString(", ") { "'${it.name}' (${it.uuid})" }
        val hint = if (suggestions.isNotEmpty()) " Did you mean: $suggestions?" else ""
        publishActionResult(store, correlationId, actionName, false,
            error = "Agent '$raw' not found.$hint")
        platformDependencies.log(LogLevel.WARN, identity.handle, "$actionName: Agent '$raw' not found.$hint")
        return null
    }

    /**
     * Resolves a session [IdentityUUID] to its current handle via the identity registry.
     * Used at dispatch sites for cross-feature session actions (SESSION_POST, etc.).
     */
    private fun resolveSessionHandle(sessionUUID: IdentityUUID, store: Store): String? {
        return store.state.value.identityRegistry.findByUUID(sessionUUID)?.handle
    }

    override fun init(store: Store) {
        // Register all built-in cognitive strategies before any agents boot.
        CognitiveStrategyRegistry.register(
            app.auf.feature.agent.strategies.MinimalStrategy)
        CognitiveStrategyRegistry.register(
            app.auf.feature.agent.strategies.VanillaStrategy,
            legacyId = "vanilla_v1"
        )
        CognitiveStrategyRegistry.register(
            app.auf.feature.agent.strategies.SovereignStrategy,
            legacyId = "sovereign_v1"
        )
        CognitiveStrategyRegistry.register(
            app.auf.feature.agent.strategies.StateMachineStrategy
        )
        CognitiveStrategyRegistry.register(
            app.auf.feature.agent.strategies.PrivateSessionStrategy
        )

        coroutineScope.launch {
            while (true) {
                delay(1000)
                val state = store.state.value.featureStates[identity.handle] as? AgentRuntimeState
                if (state != null) {
                    AgentAutoTriggerLogic.checkAndDispatchTriggers(store, state, platformDependencies, identity.handle)
                }
            }
        }
    }

    override fun reducer(state: FeatureState?, action: Action): FeatureState? {
        val currentFeatureState = state as? AgentRuntimeState ?: AgentRuntimeState()
        val crudState = AgentCrudLogic.reduce(currentFeatureState, action, platformDependencies)
        if (crudState !== currentFeatureState) return crudState
        return AgentRuntimeReducer.reduce(currentFeatureState, action, platformDependencies)
    }

    override fun handleSideEffects(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {
        val agentState = newState as? AgentRuntimeState ?: run {
            platformDependencies.log(LogLevel.ERROR, identity.handle, "handleSideEffects: newState is not AgentRuntimeState for action '${action.name}'. Feature state corrupted?")
            return
        }
        when (action.name) {
            // Targeted responses — route to pipeline or handlers.
            ActionRegistry.Names.SESSION_RETURN_LEDGER,
            ActionRegistry.Names.KNOWLEDGEGRAPH_RETURN_CONTEXT,
            ActionRegistry.Names.GATEWAY_RETURN_RESPONSE,
            ActionRegistry.Names.GATEWAY_RETURN_PREVIEW,
            ActionRegistry.Names.FILESYSTEM_RETURN_LIST,
            ActionRegistry.Names.FILESYSTEM_RETURN_READ,
            ActionRegistry.Names.FILESYSTEM_RETURN_FILES_CONTENT -> {
                handleTargetedResponse(action, store, agentState)
            }
            // --- Startup ---
            ActionRegistry.Names.SYSTEM_STARTING -> {
                // Inject built-in resources from all registered strategies.
                CognitiveStrategyRegistry.getAllBuiltInResources().forEach { resource ->
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.AGENT_RESOURCE_LOADED,
                        json.encodeToJsonElement(resource) as JsonObject
                    ))
                }
                // Then load user-defined resources from disk
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_LIST))
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_LIST, buildJsonObject {
                    put("path", "resources")
                }))
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.GATEWAY_REQUEST_AVAILABLE_MODELS))
            }
            ActionRegistry.Names.AGENT_AGENTS_LOADED -> {
                // Seed nvram.json for any agent that doesn't have one yet
                agentState.agents.values.forEach { agent ->
                    if (agent.cognitiveState is JsonNull || agent.cognitiveState == null) {
                        saveAgentNvram(agent, store)
                    }
                }
                // Polymorphic infrastructure check for all agents.
                if (store.state.value.identityRegistry.values.any { it.parentHandle == "session" }) {
                    dispatchEnsureInfrastructureForAll(agentState, store)
                }
                broadcastAgentNames(agentState, store)
            }
            ActionRegistry.Names.AGENT_VALIDATE_SOVEREIGN_STATE -> {
                // Generalized: run ensureInfrastructure for all agents.
                // The action name is retained for backward compat but the implementation is strategy-agnostic.
                dispatchEnsureInfrastructureForAll(agentState, store)
            }
            ActionRegistry.Names.AGENT_AGENT_LOADED -> {
                val agent = action.payload?.let { json.decodeFromJsonElement<AgentInstance>(it) } ?: return
                val uuid = agent.identityUUID
                // Register agent identity
                store.deferredDispatch(identity.handle, Action(
                    ActionRegistry.Names.CORE_REGISTER_IDENTITY,
                    buildJsonObject {
                        put("uuid", uuid.uuid)
                        put("name", agent.identity.name)
                    }
                ))
            }
            ActionRegistry.Names.AGENT_RESOURCE_LOADED -> {
                // No side effects needed, pure reducer handles state merge
            }

            // --- Agent CRUD Side Effects ---
            ActionRegistry.Names.AGENT_CREATE -> {
                val payload = action.payload
                val correlationId = payload?.correlationId()
                val agentToSave = agentState.agents.values.lastOrNull() ?: run {
                    platformDependencies.log(LogLevel.ERROR, identity.handle, "AGENT_CREATE: No agents in state after CREATE action. Reducer may have failed.")
                    publishActionResult(store, correlationId, action.name, false, error = "Agent creation failed — reducer did not produce an agent.")
                    return
                }
                saveAgentConfig(agentToSave, store)
                // NVRAM is agent-written runtime state. Write initial NVRAM on creation
                // so the agent has a baseline cognitive state file.
                saveAgentNvram(agentToSave, store)

                // Polymorphic: let the strategy set up its infrastructure.
                val strategy = CognitiveStrategyRegistry.get(agentToSave.cognitiveStrategyId)
                strategy.onAgentRegistered(agentToSave, store)

                // Register agent identity (no localHandle — CoreFeature generates via slugifyName)
                // Auto-generate a display color using golden-angle hue rotation from the
                // primary teal (H≈182, S≈0.66, L≈0.61). Each agent gets a distinct hue that
                // matches the design language's vibrancy.
                val agentIndex = agentState.agents.size - 1 // -1 because new agent is already in state
                val autoHue = ((agentIndex * 137.5f) % 360f)
                val autoColor = colorToHex(hslToColor(autoHue, 0.66f, 0.61f))

                store.deferredDispatch(identity.handle, Action(
                    ActionRegistry.Names.CORE_REGISTER_IDENTITY,
                    buildJsonObject {
                        put("uuid", agentToSave.identityUUID.uuid)
                        put("name", agentToSave.identity.name)
                        put("displayColor", autoColor)
                    }
                ))

                publishActionResult(store, correlationId, action.name, true, summary = "Agent '${agentToSave.identity.name}' created.")
                broadcastAgentNames(agentState, store)
            }
            ActionRegistry.Names.AGENT_CLONE -> {
                val payload = action.payload
                val correlationId = payload?.correlationId()
                val agentId = resolveAgentId(payload, store, correlationId, action.name) ?: return
                val agentToClone = agentState.agents[agentId] ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "AGENT_CLONE: Source agent '$agentId' not found in state.")
                    publishActionResult(store, correlationId, action.name, false, error = "Source agent '$agentId' not found.")
                    return
                }
                // strategyConfig is a generic JSON object — copy it as-is.
                // No strategy-specific field names needed in core code.
                val createPayload = buildJsonObject {
                    put("name", "${agentToClone.identity.name} (Copy)")
                    put("strategyConfig", agentToClone.strategyConfig)
                    put("modelProvider", agentToClone.modelProvider)
                    put("modelName", agentToClone.modelName)
                    put("subscribedSessionIds", buildJsonArray { agentToClone.subscribedSessionIds.forEach { add(it.uuid) } })
                    put("automaticMode", agentToClone.automaticMode)
                    put("autoWaitTimeSeconds", agentToClone.autoWaitTimeSeconds)
                    put("autoMaxWaitTimeSeconds", agentToClone.autoMaxWaitTimeSeconds)
                }
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_CREATE, createPayload))

                publishActionResult(store, correlationId, action.name, true, summary = "Agent '${agentToClone.identity.name}' cloned.")
            }
            ActionRegistry.Names.AGENT_TOGGLE_AUTOMATIC_MODE, ActionRegistry.Names.AGENT_TOGGLE_ACTIVE -> {
                val payload = action.payload
                val correlationId = payload?.correlationId()
                val agentId = resolveAgentId(payload, store, correlationId, action.name) ?: return
                val agent = agentState.agents[agentId] ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "AGENT_TOGGLE: Agent '$agentId' not found in state.")
                    publishActionResult(store, correlationId, action.name, false, error = "Agent '$agentId' not found.")
                    return
                }
                saveAgentConfig(agent, store)
                AgentAvatarLogic.touchAgentAvatarCard(agent, agentState, store)

                val summary = when (action.name) {
                    ActionRegistry.Names.AGENT_TOGGLE_AUTOMATIC_MODE ->
                        "Agent '${agent.identity.name}' automatic mode ${if (agent.automaticMode) "enabled" else "disabled"}."
                    else ->
                        "Agent '${agent.identity.name}' ${if (agent.isAgentActive) "activated" else "paused"}."
                }
                publishActionResult(store, correlationId, action.name, true, summary = summary)
            }
            ActionRegistry.Names.AGENT_ADD_SESSION_SUBSCRIPTION,
            ActionRegistry.Names.AGENT_REMOVE_SESSION_SUBSCRIPTION -> {
                val payload = action.payload
                val correlationId = payload?.correlationId()
                val agentId = resolveAgentId(payload, store, correlationId, action.name) ?: return
                val agent = agentState.agents[agentId] ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "${action.name}: Agent '$agentId' not found in state.")
                    publishActionResult(store, correlationId, action.name, false, error = "Agent '$agentId' not found.")
                    return
                }
                saveAgentConfig(agent, store)
                AgentAvatarLogic.updateAgentAvatars(agentId, store, agentState)
                broadcastAgentNames(agentState, store)

                val sessionIdStr = payload?.get("sessionId")?.jsonPrimitive?.contentOrNull ?: ""
                val verb = if (action.name == ActionRegistry.Names.AGENT_ADD_SESSION_SUBSCRIPTION) "added to" else "removed from"
                publishActionResult(store, correlationId, action.name, true,
                    summary = "Agent '${agent.identity.name}' $verb session '$sessionIdStr'.")
            }
            ActionRegistry.Names.AGENT_UPDATE_CONFIG -> {
                val payload = action.payload
                val correlationId = payload?.correlationId()
                val agentId = resolveAgentId(payload, store, correlationId, action.name) ?: return
                val oldAgent = (previousState as? AgentRuntimeState)?.agents?.get(agentId)
                val newAgent = agentState.agents[agentId] ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "AGENT_UPDATE_CONFIG: Agent '$agentId' not found in state after update.")
                    publishActionResult(store, correlationId, action.name, false, error = "Agent '$agentId' not found after update.")
                    return
                }

                // Polymorphic config change notification.
                if (oldAgent != null) {
                    val strategy = CognitiveStrategyRegistry.get(newAgent.cognitiveStrategyId)
                    strategy.onAgentConfigChanged(oldAgent, newAgent, store)
                }

                saveAgentConfig(newAgent, store)

                // NVRAM is only written when the agent's own cognitive state changes
                // (e.g., phase transition via postProcessResponse). Config changes
                // (including strategyConfig) are persisted by saveAgentConfig.

                AgentAvatarLogic.updateAgentAvatars(agentId, store, agentState)

                // Polymorphic infrastructure check.
                dispatchEnsureInfrastructureForAll(agentState, store)

                // Update identity if name, displayColor, displayIcon, or displayEmoji changed
                val nameChanged = oldAgent != null && oldAgent.identity.name != newAgent.identity.name
                val newDisplayColor = action.payload?.get("displayColor")
                val newDisplayIcon = action.payload?.get("displayIcon")
                val newDisplayEmoji = action.payload?.get("displayEmoji")
                val colorChanged = newDisplayColor != null
                val iconChanged = newDisplayIcon != null
                val emojiChanged = newDisplayEmoji != null
                if (nameChanged || colorChanged || iconChanged || emojiChanged) {
                    val currentHandle = newAgent.identityHandle
                    if (currentHandle.handle.isNotBlank()) {
                        store.deferredDispatch(identity.handle, Action(
                            ActionRegistry.Names.CORE_UPDATE_IDENTITY,
                            buildJsonObject {
                                put("handle", currentHandle.handle)
                                put("newName", newAgent.identity.name)
                                if (colorChanged) {
                                    val colorValue = newDisplayColor?.jsonPrimitive?.contentOrNull
                                    if (colorValue != null) put("displayColor", colorValue)
                                    else put("displayColor", null as String?)
                                }
                                if (iconChanged) {
                                    val iconValue = newDisplayIcon?.jsonPrimitive?.contentOrNull
                                    if (iconValue != null) put("displayIcon", iconValue)
                                    else put("displayIcon", null as String?)
                                }
                                if (emojiChanged) {
                                    val emojiValue = newDisplayEmoji?.jsonPrimitive?.contentOrNull
                                    if (emojiValue != null) put("displayEmoji", emojiValue)
                                    else put("displayEmoji", null as String?)
                                }
                            }
                        ))
                    }
                }

                publishActionResult(store, correlationId, action.name, true, summary = "Agent '${newAgent.identity.name}' configuration updated.")
                broadcastAgentNames(agentState, store)
            }
            ActionRegistry.Names.AGENT_NVRAM_LOADED -> {
                val agentId = action.payload?.agentUUID() ?: return
                val agent = agentState.agents[agentId] ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "NVRAM_LOADED: Agent '$agentId' not found. Cannot persist NVRAM.")
                    return
                }
                saveAgentNvram(agent, store)
            }
            ActionRegistry.Names.AGENT_UPDATE_NVRAM -> {
                val payload = action.payload
                val correlationId = payload?.correlationId()
                val agentId = resolveAgentId(payload, store, correlationId, action.name) ?: return
                val agent = agentState.agents[agentId] ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "UPDATE_NVRAM: Agent '$agentId' not found. Cannot update NVRAM.")
                    publishActionResult(store, correlationId, action.name, false, error = "Agent '$agentId' not found.")
                    return
                }
                saveAgentNvram(agent, store)
                publishActionResult(store, correlationId, action.name, true, summary = "Agent '${agent.identity.name}' cognitive state updated.")
            }
            ActionRegistry.Names.AGENT_DELETE -> {
                val payload = action.payload
                val correlationId = payload?.correlationId()
                val agentId = resolveAgentId(payload, store, correlationId, action.name) ?: return
                agentState.agentAvatarCardIds[agentId]?.forEach { (sessionUUID, messageId) ->
                    val sessionHandle = resolveSessionHandle(sessionUUID, store) ?: return@forEach
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_DELETE_MESSAGE, buildJsonObject {
                        put("session", sessionHandle)
                        put("messageId", messageId)
                    }))
                }
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_DELETE_DIRECTORY, buildJsonObject { put("path", agentId.uuid) }))
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_CONFIRM_DELETE, buildJsonObject { put("agentId", agentId.uuid) }))
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_AGENT_DELETED, buildJsonObject { put("agentId", agentId.uuid) }))
                // Unregister agent identity (cascades any sub-identities)
                val agentToDelete = (previousState as? AgentRuntimeState)?.agents?.get(agentId)
                val deleteName = agentToDelete?.identity?.name ?: agentId.uuid
                val deleteHandle = agentToDelete?.identityHandle
                if (deleteHandle != null && deleteHandle.handle.isNotBlank()) {
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.CORE_UNREGISTER_IDENTITY,
                        buildJsonObject { put("handle", deleteHandle.handle) }
                    ))
                }

                publishActionResult(store, correlationId, action.name, true, summary = "Agent '$deleteName' deleted.")
                broadcastAgentNames(agentState, store)
            }

            // --- Resource CRUD Side Effects ---
            ActionRegistry.Names.AGENT_CREATE_RESOURCE -> {
                val correlationId = action.payload?.correlationId()
                val newResource = agentState.resources.lastOrNull() ?: run {
                    platformDependencies.log(LogLevel.ERROR, identity.handle, "CREATE_RESOURCE: No resources in state after CREATE action. Reducer may have failed.")
                    publishActionResult(store, correlationId, action.name, false, error = "Resource creation failed.")
                    return
                }
                saveResourceConfig(newResource, store)
                publishActionResult(store, correlationId, action.name, true, summary = "Resource '${newResource.name}' created.")
            }
            ActionRegistry.Names.AGENT_SAVE_RESOURCE -> {
                val correlationId = action.payload?.correlationId()
                val resourceId = action.payload?.get("resourceId")?.jsonPrimitive?.contentOrNull ?: return
                val resourceToSave = agentState.resources.find { it.id == resourceId } ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "SAVE_RESOURCE: Resource '$resourceId' not found in state.")
                    publishActionResult(store, correlationId, action.name, false, error = "Resource '$resourceId' not found.")
                    return
                }
                saveResourceConfig(resourceToSave, store)
                publishActionResult(store, correlationId, action.name, true, summary = "Resource '${resourceToSave.name}' saved.")
            }
            ActionRegistry.Names.AGENT_RENAME_RESOURCE -> {
                val correlationId = action.payload?.correlationId()
                val resourceId = action.payload?.get("resourceId")?.jsonPrimitive?.contentOrNull ?: return
                val renamedResource = agentState.resources.find { it.id == resourceId } ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "RENAME_RESOURCE: Resource '$resourceId' not found in state after rename.")
                    publishActionResult(store, correlationId, action.name, false, error = "Resource '$resourceId' not found.")
                    return
                }
                saveResourceConfig(renamedResource, store)
                publishActionResult(store, correlationId, action.name, true, summary = "Resource renamed to '${renamedResource.name}'.")
            }
            ActionRegistry.Names.AGENT_DELETE_RESOURCE -> {
                val correlationId = action.payload?.correlationId()
                val resourceId = action.payload?.get("resourceId")?.jsonPrimitive?.contentOrNull ?: return
                val resourceToDelete = (previousState as? AgentRuntimeState)?.resources?.find { it.id == resourceId } ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "DELETE_RESOURCE: Resource '$resourceId' not found in previousState.")
                    publishActionResult(store, correlationId, action.name, false, error = "Resource '$resourceId' not found.")
                    return
                }
                resourceToDelete.path?.let { path ->
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_DELETE_FILE, buildJsonObject {
                        put("path", path)
                    }))
                }
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_SELECT_RESOURCE, buildJsonObject { put("resourceId", null as String?) }))
                publishActionResult(store, correlationId, action.name, true, summary = "Resource '${resourceToDelete.name}' deleted.")
            }

            // --- Cognitive Pipeline & Peer Updates (Delegated) ---
            ActionRegistry.Names.AGENT_INITIATE_TURN -> {
                val correlationId = action.payload?.correlationId()
                val agentId = resolveAgentId(action.payload, store, correlationId, action.name) ?: return
                val agent = agentState.agents[agentId]
                AgentCognitivePipeline.startCognitiveCycle(agentId, store)
                publishActionResult(store, correlationId, action.name, true, summary = "Turn initiated for agent '${agent?.identity?.name ?: agentId.uuid}'.")
            }
            ActionRegistry.Names.AGENT_STAGE_TURN_CONTEXT -> {
                val agentId = action.payload?.agentUUID() ?: return
                AgentCognitivePipeline.evaluateTurnContext(agentId, store)
            }
            ActionRegistry.Names.AGENT_SET_HKG_CONTEXT -> {
                val agentId = action.payload?.agentUUID() ?: return
                AgentCognitivePipeline.evaluateFullContext(agentId, store)
            }
            ActionRegistry.Names.AGENT_SET_WORKSPACE_CONTEXT -> {
                val agentId = action.payload?.agentUUID() ?: return
                AgentCognitivePipeline.evaluateFullContext(agentId, store)
            }
            ActionRegistry.Names.AGENT_CONTEXT_GATHERING_TIMEOUT -> {
                val agentId = action.payload?.agentUUID() ?: return
                val startedAt = action.payload?.get("startedAt")?.jsonPrimitive?.longOrNull ?: return
                val statusInfo = agentState.agentStatuses[agentId] ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "CONTEXT_GATHERING_TIMEOUT: No status entry for agent '$agentId'. Turn may have been cancelled.")
                    return
                }

                if (statusInfo.contextGatheringStartedAt != startedAt) {
                    platformDependencies.log(LogLevel.DEBUG, identity.handle,
                        "Stale context gathering timeout for agent '$agentId' (expected startedAt=${statusInfo.contextGatheringStartedAt}, got=$startedAt). Ignoring.")
                    return
                }
                AgentCognitivePipeline.evaluateFullContext(agentId, store, isTimeout = true)
            }
            ActionRegistry.Names.AGENT_EXECUTE_PREVIEWED_TURN -> {
                val correlationId = action.payload?.correlationId()
                val agentId = resolveAgentId(action.payload, store, correlationId, action.name) ?: return
                val agent = agentState.agents[agentId] ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "EXECUTE_PREVIEWED_TURN: Agent '$agentId' not found.")
                    publishActionResult(store, correlationId, action.name, false, error = "Agent '$agentId' not found.")
                    return
                }
                val statusInfo = agentState.agentStatuses[agentId]
                val previewData = statusInfo?.stagedPreviewData ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "EXECUTE_PREVIEWED_TURN: No staged preview data for agent '$agentId'. Preview may have been discarded.")
                    publishActionResult(store, correlationId, action.name, false, error = "No staged preview for agent '${agent.identity.name}'.")
                    return
                }

                AgentAvatarLogic.updateAgentAvatars(agentId, store, agentState, AgentStatus.PROCESSING)

                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.GATEWAY_GENERATE_CONTENT, buildJsonObject {
                    put("providerId", agent.modelProvider)
                    put("modelName", previewData.agnosticRequest.modelName)
                    put("correlationId", previewData.agnosticRequest.correlationId)
                    put("contents", json.encodeToJsonElement(previewData.agnosticRequest.contents))
                    previewData.agnosticRequest.systemPrompt?.let { put("systemPrompt", it) }
                }))
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_DISCARD_PREVIEW, buildJsonObject { put("agentId", agentId.uuid) }))
                store.dispatch("agent", Action(ActionRegistry.Names.CORE_SHOW_DEFAULT_VIEW))

                publishActionResult(store, correlationId, action.name, true, summary = "Previewed turn executed for agent '${agent.identity.name}'.")
            }
            ActionRegistry.Names.AGENT_DISCARD_PREVIEW -> {
                val correlationId = action.payload?.correlationId()
                val agentId = resolveAgentId(action.payload, store, correlationId, action.name) ?: return
                val agent = agentState.agents[agentId]
                val statusInfo = agentState.agentStatuses[agentId]
                if (statusInfo?.status != AgentStatus.PROCESSING) {
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_SET_PROCESSING_STEP, buildJsonObject {
                        put("agentId", agentId.uuid); put("step", JsonNull)
                    }))
                }
                store.dispatch("agent", Action(ActionRegistry.Names.CORE_SHOW_DEFAULT_VIEW))
                publishActionResult(store, correlationId, action.name, true, summary = "Preview discarded for agent '${agent?.identity?.name ?: agentId.uuid}'.")
            }
            ActionRegistry.Names.AGENT_CANCEL_TURN -> {
                val correlationId = action.payload?.correlationId()
                val agentId = resolveAgentId(action.payload, store, correlationId, action.name) ?: return
                val agent = agentState.agents[agentId]
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.GATEWAY_CANCEL_REQUEST, buildJsonObject {
                    put("correlationId", agentId.uuid)
                }))
                activeTurnJobs[agentId]?.cancel()
                activeTurnJobs.remove(agentId)
                AgentAvatarLogic.updateAgentAvatars(agentId, store, agentState, AgentStatus.IDLE, "Turn cancelled by user.")
                publishActionResult(store, correlationId, action.name, true, summary = "Turn cancelled for agent '${agent?.identity?.name ?: agentId.uuid}'.")
            }
            ActionRegistry.Names.SESSION_MESSAGE_POSTED -> {
                val prevAgentState = previousState as? AgentRuntimeState ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "MESSAGE_POSTED: previousState is not AgentRuntimeState. Cannot detect auto-turn triggers.")
                    return
                }
                agentState.agents.keys.forEach { agentId ->
                    val prevStatus = prevAgentState.agentStatuses[agentId] ?: AgentStatusInfo()
                    val newStatus = agentState.agentStatuses[agentId] ?: AgentStatusInfo()

                    val statusChanged = prevStatus.status != newStatus.status
                    val frontierMoved = prevStatus.lastSeenMessageId != newStatus.lastSeenMessageId

                    if (statusChanged || frontierMoved) {
                        avatarUpdateJobs[agentId]?.cancel()
                        avatarUpdateJobs[agentId] = coroutineScope.launch {
                            delay(50)
                            AgentAvatarLogic.updateAgentAvatars(agentId, store, agentState)
                        }
                    }
                }
            }
            ActionRegistry.Names.AGENT_CHECK_AUTOMATIC_TRIGGERS -> {
                AgentAutoTriggerLogic.checkAndDispatchTriggers(store, agentState, platformDependencies, identity.handle)
            }
            ActionRegistry.Names.SESSION_SESSION_NAMES_UPDATED -> {
                // Polymorphic infrastructure check.
                dispatchEnsureInfrastructureForAll(agentState, store)
            }
            // ================================================================
            // Phase A: SESSION_CREATED handler — link private sessions to agents
            // ================================================================
            ActionRegistry.Names.SESSION_SESSION_CREATED -> {
                val payload = action.payload ?: return
                val isPrivateTo = payload["isPrivateTo"]?.jsonPrimitive?.contentOrNull ?: return
                val sessionUUID = payload["uuid"]?.jsonPrimitive?.contentOrNull ?: return

                // Find the agent whose identityHandle matches isPrivateTo
                val matchingAgent = agentState.agents.values.find {
                    it.identityHandle.handle == isPrivateTo
                } ?: return

                platformDependencies.log(LogLevel.INFO, identity.handle,
                    "SESSION_CREATED: Linking private session '$sessionUUID' to agent '${matchingAgent.identityUUID}' (matched via isPrivateTo='$isPrivateTo').")

                // 1. Link the session as the agent's output target
                store.deferredDispatch(identity.handle, Action(
                    ActionRegistry.Names.AGENT_UPDATE_CONFIG,
                    buildJsonObject {
                        put("agentId", matchingAgent.identityUUID.uuid)
                        put("outputSessionId", sessionUUID)
                    }
                ))

                // 2. Subscribe the agent to its own private session so it appears
                //    in the conversation log (the agent can see its own prior actions
                //    and internal monologue).
                store.deferredDispatch(identity.handle, Action(
                    ActionRegistry.Names.AGENT_ADD_SESSION_SUBSCRIPTION,
                    buildJsonObject {
                        put("agentId", matchingAgent.identityUUID.uuid)
                        put("sessionId", sessionUUID)
                    }
                ))

                // 3. Clear the pending flag
                store.deferredDispatch(identity.handle, Action(
                    ActionRegistry.Names.AGENT_SET_PENDING_PRIVATE_SESSION,
                    buildJsonObject {
                        put("agentId", matchingAgent.identityUUID.uuid)
                        put("pending", false)
                    }
                ))
            }
            // ================================================================
            // Phase A: Context collapse persistence
            // ================================================================
            ActionRegistry.Names.AGENT_CONTEXT_UNCOLLAPSE,
            ActionRegistry.Names.AGENT_CONTEXT_COLLAPSE -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull?.let { IdentityUUID(it) } ?: return
                val agent = agentState.agents[agentId] ?: return
                saveContextState(agent, agentState, store)
            }
            ActionRegistry.Names.SESSION_SESSION_FEATURE_READY -> {
                agentState.agents.forEach { (agentId, agent) ->
                    if (agent.isAgentActive) {
                        AgentAvatarLogic.updateAgentAvatars(agentId, store, agentState, AgentStatus.IDLE)
                    }
                }
            }

            // --- Identity Registration Response Side Effects ---
            ActionRegistry.Names.CORE_RETURN_REGISTER_IDENTITY -> {
                val uuid = action.payload?.agentUUID("uuid") ?: return
                val agent = agentState.agents[uuid] ?: return
                saveAgentConfig(agent, store)
            }
            ActionRegistry.Names.CORE_RETURN_UPDATE_IDENTITY -> {
                val uuid = action.payload?.agentUUID("uuid") ?: return
                val agent = agentState.agents[uuid] ?: return
                saveAgentConfig(agent, store)
            }

            // ========================================================================
            // ACTION_CREATED: Handle validated commands from CommandBot
            // ========================================================================
            ActionRegistry.Names.COMMANDBOT_ACTION_CREATED -> {
                val payload = action.payload ?: return
                val originatorId = payload["originatorId"]?.jsonPrimitive?.contentOrNull ?: return
                val originatorName = payload["originatorName"]?.jsonPrimitive?.contentOrNull ?: originatorId
                val sessionId = payload["sessionId"]?.jsonPrimitive?.contentOrNull ?: return
                val actionName = payload["actionName"]?.jsonPrimitive?.contentOrNull ?: return
                val actionPayload = payload["actionPayload"]?.jsonObject ?: buildJsonObject {}
                val correlationId = payload["correlationId"]?.jsonPrimitive?.contentOrNull ?: return

                // Resolve originator: originatorId may be a UUID (direct key match)
                // or a handle like "agent.flash" (identity registry format).
                val agent = agentState.agents[IdentityUUID(originatorId)]
                    ?: agentState.agents.values.find { it.identityHandle.handle == originatorId }
                if (agent == null) return  // Not our agent — ignore
                val agentUuid = agent.identityUUID

                platformDependencies.log(
                    LogLevel.INFO, identity.handle,
                    "ACTION_CREATED: Handling '$actionName' for agent '$originatorId' (uuid=$agentUuid, correlationId=$correlationId)."
                )

                // Apply sandbox rewrite for actions that need it.
                // Uses UUID because workspace paths are "{uuid}/workspace/".
                var finalPayload = applySandboxRewrite(actionName, actionPayload, agentUuid)

                // NVRAM self-targeting enforcement: agents with only agent:cognition
                // have agentId rewritten to their own UUID. Only agent:manage
                // holders can target a different agent's NVRAM.
                if (actionName == ActionRegistry.Names.AGENT_UPDATE_NVRAM) {
                    finalPayload = enforceNvramSelfTarget(finalPayload, agent, store)
                }

                // Inject correlationId into the payload
                val enrichedPayload = if (finalPayload["correlationId"] == null) {
                    JsonObject(finalPayload + ("correlationId" to JsonPrimitive(correlationId)))
                } else {
                    finalPayload
                }

                // Dispatch the domain action attributed to the agent.
                // Pre-Phase 1 fix: dispatch as the agent's identity handle (e.g., "agent.coder-1")
                // instead of the feature handle ("agent") so the Store permission guard
                // evaluates the correct identity's effective permissions.
                store.deferredDispatch(agent.identityHandle.handle, Action(name = actionName, payload = enrichedPayload))

                // Track the pending command in state
                store.deferredDispatch(identity.handle, Action(
                    ActionRegistry.Names.AGENT_REGISTER_PENDING_COMMAND,
                    buildJsonObject {
                        put("correlationId", correlationId)
                        put("agentId", agentUuid.uuid)
                        put("agentName", originatorName)
                        put("sessionId", sessionId)
                        put("actionName", actionName)
                    }
                ))
                // Schedule TTL cleanup.
                store.scheduleDelayed(PENDING_COMMAND_TTL_MS, identity.handle, Action(
                    ActionRegistry.Names.AGENT_CLEAR_PENDING_COMMAND,
                    buildJsonObject { put("correlationId", correlationId) }
                ))

                platformDependencies.log(
                    LogLevel.INFO, identity.handle,
                    "Dispatched '$actionName' on behalf of agent '$originatorId' (uuid=$agentUuid, session=$sessionId, correlationId=$correlationId)."
                )
            }
        }
    }

    /**
     * Broadcasts the current agent names and subscriptions snapshot.
     * Called after agent CRUD operations and subscription changes so that
     * other features (e.g., SessionFeature) can discover agents without
     * cross-feature imports.
     */
    private fun broadcastAgentNames(agentState: AgentRuntimeState, store: Store) {
        store.deferredDispatch(identity.handle, Action(
            name = ActionRegistry.Names.AGENT_AGENT_NAMES_UPDATED,
            payload = buildJsonObject {
                putJsonArray("agents") {
                    agentState.agents.values.forEach { agent ->
                        add(buildJsonObject {
                            put("uuid", agent.identityUUID.uuid)
                            put("name", agent.identity.name)
                            putJsonArray("subscribedSessionIds") {
                                agent.subscribedSessionIds.forEach { sid -> add(sid.uuid) }
                            }
                        })
                    }
                }
            }
        ))
    }

    // ========================================================================
    // Polymorphic lifecycle dispatch helpers
    // ========================================================================

    /**
     * Calls [CognitiveStrategy.ensureInfrastructure] for every active agent.
     * Each strategy's implementation is a no-op if the agent doesn't need
     * infrastructure management — so this is safe to call unconditionally.
     */
    private fun dispatchEnsureInfrastructureForAll(agentState: AgentRuntimeState, store: Store) {
        agentState.agents.values.forEach { agent ->
            val strategy = CognitiveStrategyRegistry.get(agent.cognitiveStrategyId)
            strategy.ensureInfrastructure(agent, agentState, store)
        }
    }

    // ========================================================================
    // ACTION_RESULT broadcast helper
    // ========================================================================

    /**
     * Publishes a lightweight broadcast notification after completing a
     * command-dispatchable action. CommandBot matches via `correlationId`
     * to post feedback to the originating session. Other observers
     * (logging plugins, usage monitors) may also subscribe.
     *
     * Follows the same contract as `FileSystemFeature.publishActionResult`.
     */
    private fun publishActionResult(
        store: Store,
        correlationId: String?,
        requestAction: String,
        success: Boolean,
        summary: String? = null,
        error: String? = null
    ) {
        store.deferredDispatch(identity.handle, Action(
            name = ActionRegistry.Names.AGENT_ACTION_RESULT,
            payload = buildJsonObject {
                correlationId?.let { put("correlationId", it) }
                put("requestAction", requestAction)
                put("success", success)
                summary?.let { put("summary", it) }
                error?.let { put("error", it) }
            }
        ))
    }

    // ========================================================================
    // ACTION_CREATED: Sandbox rewrite
    // ========================================================================

    private fun applySandboxRewrite(actionName: String, payload: JsonObject, agentId: IdentityUUID): JsonObject {
        val rule = ActionRegistry.agentSandboxRules[actionName] ?: return payload
        if (rule.strategy != "AGENT_WORKSPACE") return payload

        val mutablePayload = payload.toMutableMap()

        val originalPath = payload["path"]?.jsonPrimitive?.contentOrNull ?: ""
        val prefix = rule.pathPrefixTemplate.replace("{agentId}", agentId.uuid)
        val sandboxedPath = if (originalPath.isNotBlank()) "$prefix/$originalPath" else prefix
        mutablePayload["path"] = JsonPrimitive(sandboxedPath)

        rule.payloadRewrites.forEach { (key, jsonLiteralValue) ->
            mutablePayload[key] = Json.parseToJsonElement(jsonLiteralValue)
        }

        return JsonObject(mutablePayload)
    }

    /**
     * NVRAM self-targeting enforcement for agent-originated UPDATE_NVRAM commands.
     *
     * Agents with only `agent:cognition` have the `agentId` field rewritten to their own
     * UUID — they can only write their own NVRAM. Agents with `agent:manage` (e.g.,
     * a janitorial agent) can target any agent.
     *
     * This is a defense-in-depth measure: the permission guard allows the action
     * (agent:cognition is satisfied), and this sandbox layer constrains the target.
     */
    private fun enforceNvramSelfTarget(payload: JsonObject, agent: AgentInstance, store: Store): JsonObject {
        val agentIdentity = store.state.value.identityRegistry[agent.identityHandle.handle]
            ?: return payload // Identity not found — let downstream validation handle it

        val effective = store.resolveEffectivePermissions(agentIdentity)
        val hasManage = effective["agent:manage"]?.level == PermissionLevel.YES

        if (hasManage) return payload // Full admin — allow cross-agent targeting

        // Self-only: rewrite agentId to the caller's own UUID
        val targetId = payload["agentId"]?.jsonPrimitive?.contentOrNull
        if (targetId != null && targetId != agent.identityUUID.uuid && targetId != agent.identityHandle.handle) {
            platformDependencies.log(
                LogLevel.WARN, identity.handle,
                "NVRAM sandbox: Agent '${agent.identityHandle}' attempted to write NVRAM for '$targetId' " +
                        "without agent:manage. Rewriting target to self (${agent.identityUUID})."
            )
        }

        val mutablePayload = payload.toMutableMap()
        mutablePayload["agentId"] = JsonPrimitive(agent.identityUUID.uuid)
        return JsonObject(mutablePayload)
    }

    // ========================================================================
    // Persistence helpers
    // ========================================================================

    private fun saveAgentConfig(agent: AgentInstance, store: Store) {
        val agentWithoutNvram = agent.copy(cognitiveState = JsonNull)
        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_WRITE, buildJsonObject {
            put("path", "${agent.identityUUID.uuid}/$agentConfigFILENAME")
            put("content", json.encodeToString(agentWithoutNvram))
        }))
    }

    private fun saveAgentNvram(agent: AgentInstance, store: Store) {
        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_WRITE, buildJsonObject {
            put("path", "${agent.identityUUID.uuid}/$nvramFILENAME")
            put("content", json.encodeToString(agent.cognitiveState))
        }))
    }

    /**
     * Persists the agent's context collapse overrides to context.json (§3.8).
     * Written on change (same pattern as nvram.json).
     */
    private fun saveContextState(agent: AgentInstance, agentState: AgentRuntimeState, store: Store) {
        val overrides = agentState.agentStatuses[agent.identityUUID]?.contextCollapseOverrides ?: emptyMap()
        val contextJson = buildJsonObject {
            put("version", JsonPrimitive(1))
            put("collapseOverrides", buildJsonObject {
                overrides.forEach { (key, value) ->
                    put(key, value.name)
                }
            })
        }
        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_WRITE, buildJsonObject {
            put("path", "${agent.identityUUID.uuid}/$contextFILENAME")
            put("content", json.encodeToString(contextJson))
        }))
    }

    private fun saveResourceConfig(resource: AgentResource, store: Store) {
        resource.path?.let { path ->
            store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_WRITE, buildJsonObject {
                put("path", path)
                put("content", json.encodeToString(resource))
            }))
        }
    }

    // ========================================================================
    // Targeted action handling
    // ========================================================================

    private fun handleTargetedResponse(action: Action, store: Store, agentState: AgentRuntimeState) {
        val payload = action.payload ?: return

        // Check if this is a response to a pending command (ACTION_CREATED flow)
        val correlationId = payload["correlationId"]?.jsonPrimitive?.contentOrNull
        if (correlationId != null) {
            val pendingCommand = agentState.pendingCommands[correlationId]
            if (pendingCommand != null) {
                routeCommandResponseToSession(pendingCommand, action, store)
                return
            }
        }

        // Route to appropriate handler (non-command responses)
        when (action.name) {
            ActionRegistry.Names.SESSION_RETURN_LEDGER,
            ActionRegistry.Names.KNOWLEDGEGRAPH_RETURN_CONTEXT,
            ActionRegistry.Names.GATEWAY_RETURN_RESPONSE,
            ActionRegistry.Names.GATEWAY_RETURN_PREVIEW -> {
                AgentCognitivePipeline.handleTargetedAction(action, store)
            }
            ActionRegistry.Names.FILESYSTEM_RETURN_LIST -> {
                val hasCorrelationId = payload["correlationId"]?.jsonPrimitive?.contentOrNull != null
                if (hasCorrelationId) {
                    AgentCognitivePipeline.handleTargetedAction(action, store)
                } else {
                    handleFileSystemListResponse(payload, store)
                }
            }
            ActionRegistry.Names.FILESYSTEM_RETURN_READ -> handleFileSystemReadResponse(payload, store)
            ActionRegistry.Names.FILESYSTEM_RETURN_FILES_CONTENT -> {
                // Currently only received via command responses — would have been handled above
            }
        }
    }

    // ========================================================================
    // ACTION_CREATED: DELIVER_TO_SESSION routing
    // ========================================================================

    private fun routeCommandResponseToSession(
        pending: AgentPendingCommand,
        action: Action,
        store: Store
    ) {
        val formatted = formatResponseForSession(action)

        if (formatted != null) {
            val sessionHandle = resolveSessionHandle(pending.sessionId, store) ?: run {
                platformDependencies.log(LogLevel.ERROR, identity.handle,
                    "routeCommandResponseToSession: Session UUID '${pending.sessionId}' not in registry. Cannot deliver.")
                return
            }
            store.deferredDispatch(identity.handle, Action(
                ActionRegistry.Names.COMMANDBOT_DELIVER_TO_SESSION,
                buildJsonObject {
                    put("correlationId", pending.correlationId)
                    put("sessionId", sessionHandle)
                    put("message", formatted)
                }
            ))
            platformDependencies.log(
                LogLevel.INFO, identity.handle,
                "Routed data for '${pending.actionName}' (correlationId=${pending.correlationId}) to session '${pending.sessionId}' via DELIVER_TO_SESSION."
            )
        } else {
            platformDependencies.log(
                LogLevel.DEBUG, identity.handle,
                "No data to deliver for '${pending.actionName}' (correlationId=${pending.correlationId}). ACTION_RESULT covers the status."
            )
        }

        store.deferredDispatch(identity.handle, Action(
            ActionRegistry.Names.AGENT_CLEAR_PENDING_COMMAND,
            buildJsonObject { put("correlationId", pending.correlationId) }
        ))
    }

    private fun formatResponseForSession(action: Action): String? {
        val payload = action.payload ?: return null
        return when (action.name) {
            ActionRegistry.Names.FILESYSTEM_RETURN_LIST -> {
                val listing = payload["listing"]?.jsonArray
                if (listing == null || listing.isEmpty()) return null
                val path = payload["path"]?.jsonPrimitive?.contentOrNull ?: "."
                val formatted = Json { prettyPrint = true }.encodeToString(
                    buildJsonObject {
                        put("workspace_path", path.ifBlank { "." })
                        put("entries", listing)
                    }
                )
                "```json\n$formatted\n```"
            }
            ActionRegistry.Names.FILESYSTEM_RETURN_READ -> {
                val path = payload["path"]?.jsonPrimitive?.contentOrNull ?: ""
                val content = payload["content"]?.jsonPrimitive?.contentOrNull
                if (content == null) return null
                val ext = path.substringAfterLast('.', "")
                "```$ext \"$path\"\n$content\n```"
            }
            ActionRegistry.Names.FILESYSTEM_RETURN_FILES_CONTENT -> {
                val contents = payload["contents"]?.jsonObject
                if (contents == null || contents.isEmpty()) return null
                contents.entries.joinToString("\n\n") { (path, content) ->
                    val ext = path.substringAfterLast('.', "")
                    "```$ext \"$path\"\n${content.jsonPrimitive.content}\n```"
                }
            }
            else -> {
                "```json\n${Json { prettyPrint = true }.encodeToString(payload)}\n```"
            }
        }
    }

    // ========================================================================
    // Existing filesystem response handlers (non-command paths)
    // ========================================================================

    private fun handleFileSystemListResponse(payload: JsonObject, store: Store) {
        val path = payload["path"]?.jsonPrimitive?.contentOrNull ?: ""
        val listing = payload["listing"]?.jsonArray

        val normalizedPath = path.replace("\\", "/")

        when {
            // Root listing — discover agent directories
            normalizedPath == "" || normalizedPath == "." -> {
                val fileList = listing?.map { json.decodeFromJsonElement<FileEntry>(it) } ?: run {
                    platformDependencies.log(LogLevel.DEBUG, identity.handle, "handleFileSystemListResponse: Empty or null listing payload for root listing.")
                    return
                }
                agentLoadCount = fileList.count { it.isDirectory && it.path != "resources" }

                if (agentLoadCount == 0) {
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_AGENTS_LOADED))
                } else {
                    fileList.forEach { entry ->
                        if (entry.isDirectory && entry.path != "resources") {
                            val agentDir = platformDependencies.getFileName(entry.path)
                            store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_READ, buildJsonObject {
                                put("path", "$agentDir/$agentConfigFILENAME")
                            }))
                            store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_READ, buildJsonObject {
                                put("path", "$agentDir/$nvramFILENAME")
                            }))
                            store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_READ, buildJsonObject {
                                put("path", "$agentDir/$contextFILENAME")
                            }))
                        }
                    }
                }
            }

            // Resource listing
            normalizedPath == "resources" -> {
                listing?.forEach { element ->
                    val entry = json.decodeFromJsonElement<FileEntry>(element)
                    if (!entry.isDirectory && entry.path.endsWith(".json")) {
                        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_READ, buildJsonObject {
                            val fileName = platformDependencies.getFileName(entry.path)
                            val canonicalPath = "resources/$fileName"
                            put("path", canonicalPath)
                        }))
                    }
                }
            }

            // ====== Agent workspace directory listings ======
            normalizedPath.contains("/workspace") -> {
                val agentIdStr = normalizedPath.substringBefore("/workspace")
                val agentId = IdentityUUID(agentIdStr)
                val state = store.state.value.featureStates[identity.handle] as? AgentRuntimeState ?: run {
                    platformDependencies.log(LogLevel.DEBUG, identity.handle, "handleFileSystemListResponse: Agent state unavailable. Dropping workspace listing for '$agentId'.")
                    return
                }
                val agent = state.agents[agentId] ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle,
                        "Workspace list response for unknown agent '$agentId'. Ignoring.")
                    return
                }
                val targetSessionId = getAgentResponseSessionId(agent) ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle,
                        "Agent '${agent.identityUUID}' has no session for workspace list response.")
                    return
                }
                val targetSessionHandle = resolveSessionHandle(targetSessionId, store) ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle,
                        "Agent '${agent.identityUUID}' session UUID '$targetSessionId' not in registry.")
                    return
                }

                val workspaceRelativePath = normalizedPath.substringAfter("$agentIdStr/workspace")
                    .removePrefix("/")
                    .ifBlank { "." }

                val listingJson = listing?.map { element ->
                    val entry = json.decodeFromJsonElement<FileEntry>(element)
                    val relativePath = entry.path.replace("\\", "/")
                        .removePrefix("$agentIdStr/workspace/")
                        .removePrefix("$agentIdStr/workspace")
                    buildJsonObject {
                        put("path", relativePath)
                        put("isDirectory", entry.isDirectory)
                    }
                } ?: emptyList()

                val message = buildString {
                    appendLine("```json")
                    appendLine("{")
                    appendLine("  \"workspace_path\": \"$workspaceRelativePath\",")
                    appendLine("  \"entries\": ${Json.encodeToString(kotlinx.serialization.json.JsonArray(listingJson))}")
                    appendLine("}")
                    appendLine("```")
                }

                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
                    put("session", targetSessionHandle)
                    put("senderId", commandBotSenderId)
                    put("message", message)
                }))
            }
        }
    }

    private fun handleFileSystemReadResponse(payload: JsonObject, store: Store) {
        val path = (payload["path"]?.jsonPrimitive?.contentOrNull ?: "").replace("\\", "/")
        val content = payload["content"]?.jsonPrimitive?.contentOrNull ?: run {
            platformDependencies.log(LogLevel.DEBUG, identity.handle, "handleFileSystemReadResponse: Missing content in read response for path='$path'.")
            return
        }

        when {
            // ====== Agent workspace file responses ======
            path.contains(workspacePathMarker) -> {
                val agentIdStr = path.substringBefore(workspacePathMarker)
                val agentId = IdentityUUID(agentIdStr)
                val relativePath = path.substringAfter(workspacePathMarker)
                val state = store.state.value.featureStates[identity.handle] as? AgentRuntimeState ?: run {
                    platformDependencies.log(LogLevel.DEBUG, identity.handle, "handleFileSystemReadResponse: Agent state unavailable. Dropping file read for '$agentId'.")
                    return
                }
                val agent = state.agents[agentId] ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle,
                        "Workspace read response for unknown agent '$agentId'. Ignoring.")
                    return
                }
                val targetSessionId = getAgentResponseSessionId(agent) ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle,
                        "Agent '${agent.identityUUID}' has no session for workspace read response.")
                    return
                }
                val targetSessionHandle = resolveSessionHandle(targetSessionId, store) ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle,
                        "Agent '${agent.identityUUID}' session UUID '$targetSessionId' not in registry.")
                    return
                }

                val message = if (content != null) {
                    "```text\n[WORKSPACE FILE: $relativePath]\n$content\n```"
                } else {
                    "```text\n[WORKSPACE ERROR] File not found: $relativePath\n```"
                }

                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
                    put("session", targetSessionHandle)
                    put("senderId", commandBotSenderId)
                    put("message", message)
                }))
            }
            // Shared Resource files live under the "resources/" directory
            path.startsWith("resources/") -> {
                try {
                    val resource = json.decodeFromString<AgentResource>(content)
                    val resWithPath = resource.copy(path = path)
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_RESOURCE_LOADED, json.encodeToJsonElement(resWithPath) as JsonObject))
                } catch (e: Exception) {
                    platformDependencies.log(LogLevel.ERROR, identity.handle, "Failed to parse resource: $path. Error: ${e.message}")
                }
            }
            // Agent config files
            path.endsWith("/$agentConfigFILENAME") -> {
                try {
                    val rawAgent = json.decodeFromString<AgentInstance>(content)
                    // Migrate legacy strategy IDs (e.g. "vanilla_v1" → "agent.strategy.vanilla")
                    var agent = rawAgent.copy(
                        cognitiveStrategyId = CognitiveStrategyRegistry.migrateStrategyId(rawAgent.cognitiveStrategyId.handle)
                    )
                    // Migrate old "privateSessionId" → outputSessionId
                    if (agent.outputSessionId == null) {
                        val rawJson = json.parseToJsonElement(content).jsonObject
                        rawJson["privateSessionId"]?.jsonPrimitive?.contentOrNull?.let { oldValue ->
                            // Old value may be a handle — resolve to UUID if possible
                            val registry = store.state.value.identityRegistry
                            val resolved = registry.resolve(oldValue)
                            agent = agent.copy(outputSessionId = IdentityUUID(resolved?.uuid ?: oldValue))
                        }
                    }
                    // Migrate old top-level "knowledgeGraphId" → strategyConfig
                    val rawJson = json.parseToJsonElement(content).jsonObject
                    val legacyKgId = rawJson["knowledgeGraphId"]?.jsonPrimitive?.contentOrNull
                    if (legacyKgId != null && agent.strategyConfig["knowledgeGraphId"] == null) {
                        agent = agent.copy(strategyConfig = buildJsonObject {
                            agent.strategyConfig.forEach { (k, v) -> put(k, v) }
                            put("knowledgeGraphId", legacyKgId)
                        })
                    }
                    // Migrate legacy handle-based session references → UUIDs
                    // IdentityUUID serializes as a plain string, so old handle strings
                    // load into IdentityUUID wrappers. Detect and re-resolve them.
                    val registry = store.state.value.identityRegistry
                    agent = agent.copy(
                        subscribedSessionIds = agent.subscribedSessionIds.map { maybeHandle ->
                            if (maybeHandle.uuid.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) {
                                maybeHandle // Already a UUID
                            } else {
                                // Legacy handle — resolve to UUID
                                val resolved = registry.resolve(maybeHandle.uuid)
                                if (resolved?.uuid != null) IdentityUUID(resolved.uuid) else maybeHandle
                            }
                        },
                        outputSessionId = agent.outputSessionId?.let { maybeHandle ->
                            if (maybeHandle.uuid.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) {
                                maybeHandle
                            } else {
                                val resolved = registry.resolve(maybeHandle.uuid)
                                if (resolved?.uuid != null) IdentityUUID(resolved.uuid) else maybeHandle
                            }
                        }
                    )
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_AGENT_LOADED, json.encodeToJsonElement(agent) as JsonObject))
                } catch (e: Exception) {
                    platformDependencies.log(LogLevel.ERROR, identity.handle, "Failed to parse agent config from file: $path. Error: ${e.message}")
                } finally {
                    agentLoadCount--
                    if (agentLoadCount <= 0) {
                        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_AGENTS_LOADED))
                    }
                }
            }
            // NVRAM files
            path.endsWith("/$nvramFILENAME") -> {
                try {
                    val nvramState = json.decodeFromString<JsonElement>(content)
                    val agentIdStr = path.substringBeforeLast("/")

                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_NVRAM_LOADED, buildJsonObject {
                        put("agentId", agentIdStr)
                        put("state", nvramState)
                    }))
                } catch (e: Exception) {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "Failed to load NVRAM from $path (agent will use initial state): ${e.message}")
                }
            }
            // Phase A: Context collapse overrides (§3.8)
            path.endsWith("/$contextFILENAME") -> {
                val agentIdStr = path.substringBeforeLast("/")
                try {
                    val contextData = json.parseToJsonElement(content).jsonObject
                    val overridesJson = contextData["collapseOverrides"]?.jsonObject ?: buildJsonObject {}

                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_CONTEXT_STATE_LOADED, buildJsonObject {
                        put("agentId", agentIdStr)
                        put("overrides", overridesJson)
                    }))
                } catch (e: Exception) {
                    platformDependencies.log(LogLevel.WARN, identity.handle,
                        "Failed to load context.json from $path (agent '$agentIdStr' will use empty overrides): ${e.message}")
                    // Graceful degradation: dispatch empty overrides
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_CONTEXT_STATE_LOADED, buildJsonObject {
                        put("agentId", agentIdStr)
                        put("overrides", buildJsonObject {})
                    }))
                }
            }
            // Unknown file
            else -> {
                platformDependencies.log(LogLevel.WARN, identity.handle, "Received unexpected file read response for: $path. Ignoring.")
            }
        }
    }

    override val composableProvider: ComposableProvider = object : ComposableProvider {
        override val stageViews: Map<String, @Composable (Store, List<Feature>) -> Unit> =
            mapOf(
                "feature.agent.manager" to { store, _ -> AgentManagerView(store, platformDependencies) },
                "feature.agent.context_viewer" to { store, _ -> AgentContextView(store) }
            )
        @Composable
        override fun RibbonContent(store: Store, activeViewKey: String?) {
            val viewKey = "feature.agent.manager"
            val isActive = activeViewKey == viewKey
            IconButton(onClick = { store.dispatch("agent", Action(ActionRegistry.Names.CORE_SET_ACTIVE_VIEW, buildJsonObject { put("key", viewKey) })) }) {
                Icon(Icons.Default.Bolt, "Agent Manager", tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        @Composable
        override fun PartialView(store: Store, partId: String, context: Any?) {
            if (partId != "agent.avatar") return
            // Context may be a plain String (legacy) or a Map with "senderId" and "sessionUUID"
            val agentId: String?
            val sessionUUID: String?
            when (context) {
                is Map<*, *> -> {
                    agentId = context["senderId"] as? String
                    sessionUUID = context["sessionUUID"] as? String
                }
                is String -> {
                    agentId = context
                    sessionUUID = null
                }
                else -> {
                    platformDependencies.log(LogLevel.DEBUG, identity.handle, "PartialView: Invalid context type for agent.avatar.")
                    return
                }
            }
            if (agentId == null) {
                platformDependencies.log(LogLevel.DEBUG, identity.handle, "PartialView: Null agentId in context for agent.avatar.")
                return
            }
            val appState by store.state.collectAsState()
            val state = appState.featureStates[identity.handle] as? AgentRuntimeState ?: return
            // Context may be a UUID (direct call) or a handle like "agent.flash"
            val agent = state.agents[IdentityUUID(agentId)]
                ?: state.agents.values.find { it.identityHandle.handle == agentId }
            if (agent == null) {
                platformDependencies.log(LogLevel.ERROR, identity.handle,
                    "PartialView: Could not resolve agent for context '$agentId'. " +
                            "Known UUIDs: ${state.agents.keys}, known handles: ${state.agents.values.map { it.identityHandle }}")
                return
            }
            AgentAvatarCard(agent = agent, sessionUUID = sessionUUID, store = store, platformDependencies = platformDependencies)
        }
    }
}

/**
 * Determines the session where workspace operation responses should be posted.
 * Uses the agent's outputSessionId if set, otherwise falls back to the first
 * subscribed session.
 *
 * Returns [IdentityUUID] — callers resolve to a handle via [resolveSessionHandle]
 * at the point of cross-feature dispatch.
 */
private fun getAgentResponseSessionId(agent: AgentInstance): IdentityUUID? {
    return agent.outputSessionId ?: agent.subscribedSessionIds.firstOrNull()
}