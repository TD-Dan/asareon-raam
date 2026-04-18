package asareon.raam.feature.agent

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import asareon.raam.core.*
import asareon.raam.core.Feature.ComposableProvider
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.feature.agent.contextformatters.WorkspaceContextFormatter
import asareon.raam.feature.agent.ui.AgentAvatarCard
import asareon.raam.feature.agent.ui.AgentAvatarLogic
import asareon.raam.feature.agent.ui.AgentManagerView
import asareon.raam.feature.agent.ui.ManageContextView
import asareon.raam.ui.components.IconRegistry
import asareon.raam.ui.components.colorToHex
import asareon.raam.ui.components.hslToColor
import asareon.raam.util.*
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
    private val previewDebounceJobs = mutableMapOf<IdentityUUID, Job>()
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
            asareon.raam.feature.agent.strategies.MinimalStrategy)
        CognitiveStrategyRegistry.register(
            asareon.raam.feature.agent.strategies.VanillaStrategy,
            legacyId = "vanilla_v1"
        )
        CognitiveStrategyRegistry.register(
            asareon.raam.feature.agent.strategies.SovereignStrategy,
            legacyId = "sovereign_v1"
        )
        CognitiveStrategyRegistry.register(
            asareon.raam.feature.agent.strategies.StateMachineStrategy
        )
        CognitiveStrategyRegistry.register(
            asareon.raam.feature.agent.strategies.PrivateSessionStrategy
        )
        CognitiveStrategyRegistry.register(
            asareon.raam.feature.agent.strategies.HKGStrategy
        )

        coroutineScope.launch {
            while (true) {
                delay(1000)
                val state = store.state.value.featureStates[identity.handle] as? AgentRuntimeState
                if (state != null) {
                    AutoTriggerLogic.checkAndDispatchTriggers(store, state, platformDependencies, identity.handle)
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
            ActionRegistry.Names.FILESYSTEM_RETURN_FILES_CONTENT,
            ActionRegistry.Names.SESSION_RETURN_WORKSPACE_FILES,
            ActionRegistry.Names.SESSION_RETURN_WORKSPACE_FILE -> {
                handleTargetedResponse(action, store, agentState)
            }
            // --- Startup ---
            ActionRegistry.Names.SYSTEM_RUNNING -> {
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
            // --- External Strategy Registration ---
            ActionRegistry.Names.AGENT_REGISTER_EXTERNAL_STRATEGY -> {
                handleRegisterExternalStrategy(action, store)
            }
            ActionRegistry.Names.AGENT_UNREGISTER_EXTERNAL_STRATEGY -> {
                handleUnregisterExternalStrategy(action, store)
            }
            ActionRegistry.Names.AGENT_EXTERNAL_TURN_RESULT -> {
                // Reducer already stored the result — now trigger the pipeline gate
                val agentId = action.payload?.agentUUID("correlationId")
                if (agentId != null) {
                    CognitivePipeline.handleExternalTurnResult(agentId, store)
                }
            }
            ActionRegistry.Names.SYSTEM_INITIALIZING -> {
                // Register compression settings
                CompressionConfig.settingDefinitions.forEach { (key, label, desc) ->
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SETTINGS_ADD, buildJsonObject {
                        put("key", key); put("type", "BOOLEAN"); put("label", label)
                        put("description", desc); put("section", "Token Compression"); put("defaultValue", "false")
                    }))
                }
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
                // Broadcast agent roster so other features can discover agents.
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

                // Register agent identity (no localHandle — CoreFeature generates via slugifyName).
                // Prefer user-provided display fields from the CREATE payload; fall back to
                // golden-angle hue rotation from the primary teal (H≈182, S≈0.66, L≈0.61)
                // so auto-created agents still get distinct hues.
                val resolvedColor = agentToSave.identity.displayColor ?: run {
                    val agentIndex = agentState.agents.size - 1 // -1 because new agent is already in state
                    val autoHue = ((agentIndex * 137.5f) % 360f)
                    colorToHex(hslToColor(autoHue, 0.66f, 0.61f))
                }

                store.deferredDispatch(identity.handle, Action(
                    ActionRegistry.Names.CORE_REGISTER_IDENTITY,
                    buildJsonObject {
                        put("uuid", agentToSave.identityUUID.uuid)
                        put("name", agentToSave.identity.name)
                        put("displayColor", resolvedColor)
                        if (agentToSave.identity.displayIcon != null) {
                            put("displayIcon", agentToSave.identity.displayIcon)
                        }
                        if (agentToSave.identity.displayEmoji != null) {
                            put("displayEmoji", agentToSave.identity.displayEmoji)
                        }
                    }
                ))

                publishActionResult(store, correlationId, action.name, true, summary = "Agent '${agentToSave.identity.name}' created.")
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
            ActionRegistry.Names.AGENT_SET_SESSION_SUBSCRIPTION -> {
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

                // Broadcast updated roster so observers refresh subscription views.
                broadcastAgentNames(agentState, store)

                val sessionIdStr = payload?.get("sessionId")?.jsonPrimitive?.contentOrNull ?: ""
                val subscribed = payload?.get("subscribed")?.jsonPrimitive?.booleanOrNull ?: false
                val verb = if (subscribed) "added to" else "removed from"
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
                CognitivePipeline.startCognitiveCycle(agentId, store)
                publishActionResult(store, correlationId, action.name, true, summary = "Turn initiated for agent '${agent?.identity?.name ?: agentId.uuid}'.")
            }
            ActionRegistry.Names.AGENT_STAGE_TURN_CONTEXT -> {
                val agentId = action.payload?.agentUUID() ?: return
                CognitivePipeline.evaluateTurnContext(agentId, store)
            }
            ActionRegistry.Names.AGENT_ACCUMULATE_SESSION_LEDGER -> {
                // After the reducer stores this session's ledger and removes it
                // from pendingLedgerSessionIds, check if all sessions have arrived.
                val agentId = action.payload?.agentUUID() ?: return
                val updatedState = store.state.value.featureStates["agent"] as? AgentRuntimeState ?: return
                val statusInfo = updatedState.agentStatuses[agentId] ?: return
                if (statusInfo.pendingLedgerSessionIds.isEmpty()) {
                    // All session ledgers accumulated — proceed to context gathering
                    CognitivePipeline.evaluateTurnContext(agentId, store)
                }
            }
            ActionRegistry.Names.AGENT_SET_HKG_CONTEXT -> {
                val agentId = action.payload?.agentUUID() ?: return
                CognitivePipeline.evaluateFullContext(agentId, store)
            }
            // Trigger initial API preview when managed context is first set (view opened)
            ActionRegistry.Names.AGENT_SET_MANAGED_CONTEXT -> {
                val agentId = action.payload?.agentUUID() ?: return
                resetDebouncedPreview(agentId, store)
            }
            ActionRegistry.Names.AGENT_SET_WORKSPACE_LISTING -> {
                // Listing received — evaluateFullContext checks if file reads are pending.
                // If no expanded files, workspace is ready and the gate proceeds.
                val agentId = action.payload?.agentUUID() ?: return
                CognitivePipeline.evaluateFullContext(agentId, store)
            }
            ActionRegistry.Names.AGENT_SET_WORKSPACE_FILE_CONTENTS -> {
                // Expanded file contents received — workspace context is now complete.
                val agentId = action.payload?.agentUUID() ?: return
                CognitivePipeline.evaluateFullContext(agentId, store)
            }
            ActionRegistry.Names.AGENT_STORE_SESSION_FILES -> {
                // Session file listing+contents received — check if all sessions are done.
                val agentId = action.payload?.agentUUID() ?: return
                CognitivePipeline.evaluateFullContext(agentId, store)
            }
            ActionRegistry.Names.AGENT_MERGE_SESSION_FILE_CONTENT -> {
                // On-demand session file content merged — trigger reassembly if Manage Context is open
                val agentId = action.payload?.agentUUID() ?: return
                coroutineScope.launch {
                    kotlinx.coroutines.yield()
                    val updatedAgentState = store.state.value.featureStates[identity.handle] as? AgentRuntimeState ?: return@launch
                    val updatedStatusInfo = updatedAgentState.agentStatuses[agentId] ?: return@launch
                    if (updatedStatusInfo.managedContext != null) {
                        reassembleOnToggle(agentId, store)
                    }
                }
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
                CognitivePipeline.evaluateFullContext(agentId, store, isTimeout = true)
            }
            ActionRegistry.Names.AGENT_EXECUTE_MANAGED_TURN -> {
                val correlationId = action.payload?.correlationId()
                val agentId = resolveAgentId(action.payload, store, correlationId, action.name) ?: return
                val agent = agentState.agents[agentId] ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "EXECUTE_MANAGED_TURN: Agent '$agentId' not found.")
                    publishActionResult(store, correlationId, action.name, false, error = "Agent '$agentId' not found.")
                    return
                }
                val statusInfo = agentState.agentStatuses[agentId]
                val managedContext = statusInfo?.managedContext ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "EXECUTE_MANAGED_TURN: No managed context for agent '$agentId'.")
                    publishActionResult(store, correlationId, action.name, false, error = "No managed context for agent '${agent.identity.name}'.")
                    return
                }

                // Cancel any pending debounce job (Red Team C4)
                previewDebounceJobs[agentId]?.cancel()
                previewDebounceJobs.remove(agentId)

                AgentAvatarLogic.updateAgentAvatars(agentId, store, agentState, AgentStatus.PROCESSING)

                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.GATEWAY_GENERATE_CONTENT, buildJsonObject {
                    put("providerId", agent.modelProvider)
                    put("modelName", managedContext.gatewayRequest.modelName)
                    put("correlationId", managedContext.gatewayRequest.correlationId)
                    put("contents", json.encodeToJsonElement(managedContext.gatewayRequest.contents))
                    managedContext.gatewayRequest.systemPrompt?.let { put("systemPrompt", it) }
                }))
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_DISCARD_MANAGED_CONTEXT, buildJsonObject { put("agentId", agentId.uuid) }))
                store.deferredDispatch("agent", Action(ActionRegistry.Names.CORE_SHOW_DEFAULT_VIEW))

                publishActionResult(store, correlationId, action.name, true, summary = "Managed turn executed for agent '${agent.identity.name}'.")
            }
            ActionRegistry.Names.AGENT_DISCARD_MANAGED_CONTEXT -> {
                val correlationId = action.payload?.correlationId()
                val agentId = resolveAgentId(action.payload, store, correlationId, action.name) ?: return
                val agent = agentState.agents[agentId]

                // Cancel debounce job (Red Team C4)
                previewDebounceJobs[agentId]?.cancel()
                previewDebounceJobs.remove(agentId)

                val statusInfo = agentState.agentStatuses[agentId]
                if (statusInfo?.status != AgentStatus.PROCESSING) {
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_SET_PROCESSING_STEP, buildJsonObject {
                        put("agentId", agentId.uuid); put("step", JsonNull)
                    }))
                }
                store.deferredDispatch("agent", Action(ActionRegistry.Names.CORE_SHOW_DEFAULT_VIEW))
                publishActionResult(store, correlationId, action.name, true, summary = "Managed context discarded for agent '${agent?.identity?.name ?: agentId.uuid}'.")
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
                AutoTriggerLogic.checkAndDispatchTriggers(store, agentState, platformDependencies, identity.handle)
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
                    ActionRegistry.Names.AGENT_SET_SESSION_SUBSCRIPTION,
                    buildJsonObject {
                        put("agentId", matchingAgent.identityUUID.uuid)
                        put("sessionId", sessionUUID)
                        put("subscribed", true)
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
            // Phase A+C: Context collapse persistence + feedback
            // ================================================================
            ActionRegistry.Names.AGENT_CONTEXT_UNCOLLAPSE,
            ActionRegistry.Names.AGENT_CONTEXT_COLLAPSE -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull?.let { IdentityUUID(it) } ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle,
                        "${action.name} side-effect: Missing agentId in payload. Context state not persisted.")
                    return
                }
                val agent = agentState.agents[agentId] ?: run {
                    platformDependencies.log(LogLevel.DEBUG, identity.handle,
                        "${action.name} side-effect: Agent '$agentId' not found (may have been deleted). Context state not persisted.")
                    return
                }

                // ============================================================
                // Workspace subtree scope expansion (Two-Axis Collapse Model)
                //
                // When scope == "subtree" and the partitionKey targets a workspace
                // directory ("ws:src/"), expand all nested sub-directories so the
                // agent sees the full tree structure — but do NOT expand any files
                // (the agent explicitly opens files it needs).
                //
                // The reducer has already set the target key to EXPANDED. Here we
                // fan out individual CONTEXT_UNCOLLAPSE dispatches for each child
                // directory in the subtree.
                // ============================================================
                if (action.name == ActionRegistry.Names.AGENT_CONTEXT_UNCOLLAPSE) {
                    val scope = action.payload?.get("scope")?.jsonPrimitive?.contentOrNull ?: "single"
                    val partitionKey = action.payload?.get("partitionKey")?.jsonPrimitive?.contentOrNull

                    if (scope == "subtree" && partitionKey != null && partitionKey.startsWith("ws:")) {
                        val directoryPath = partitionKey.removePrefix("ws:")
                        val statusInfo = agentState.agentStatuses[agentId]
                        val workspaceListing = statusInfo?.transientWorkspaceListing

                        if (workspaceListing != null) {
                            val safeAgentIdStr = agentId.uuid.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                            val workspacePrefix = "$safeAgentIdStr/workspace"
                            val entries = WorkspaceContextFormatter.parseListingEntries(
                                workspaceListing, workspacePrefix, platformDependencies
                            )
                            val subtreeDirs = WorkspaceContextFormatter.getSubtreeDirectoryPaths(directoryPath, entries)

                            // Dispatch individual CONTEXT_UNCOLLAPSE for each sub-directory
                            // (the target directory itself was already handled by the reducer)
                            subtreeDirs
                                .filter { it != directoryPath } // Skip the target — already expanded
                                .forEach { dirPath ->
                                    store.deferredDispatch(identity.handle, Action(
                                        ActionRegistry.Names.AGENT_CONTEXT_UNCOLLAPSE,
                                        buildJsonObject {
                                            put("agentId", agentId.uuid)
                                            put("partitionKey", "ws:$dirPath")
                                            // scope = "single" for children — prevent recursive re-entry
                                        }
                                    ))
                                }

                            platformDependencies.log(LogLevel.DEBUG, identity.handle,
                                "CONTEXT_UNCOLLAPSE subtree: Expanded ${subtreeDirs.size} directories under '$directoryPath' for agent '$agentId'.")
                        } else {
                            platformDependencies.log(LogLevel.WARN, identity.handle,
                                "CONTEXT_UNCOLLAPSE subtree: No workspace listing available for agent '$agentId'. " +
                                        "Subtree expansion deferred to next turn when listing is refreshed.")
                        }
                    }
                }

                // ============================================================
                // On-demand workspace file content fetch
                //
                // When a workspace FILE (not directory) is expanded, check if
                // its content is already in transientWorkspaceFileContents.
                // If not, dispatch a filesystem.READ_MULTIPLE to fetch it.
                // Reassembly is deferred until the content arrives to avoid
                // showing "[File not loaded]" for expanded files.
                //
                // Uses "wsod:" (workspace on-demand) correlation ID prefix to
                // distinguish from turn-initiation "ws:" reads.
                // ============================================================
                var deferReassembly = false

                if (action.name == ActionRegistry.Names.AGENT_CONTEXT_UNCOLLAPSE) {
                    val wsPartitionKey = action.payload?.get("partitionKey")?.jsonPrimitive?.contentOrNull
                    if (wsPartitionKey != null && wsPartitionKey.startsWith("ws:") && !wsPartitionKey.endsWith("/")) {
                        // This is a workspace file (not directory) being expanded
                        val relativePath = wsPartitionKey.removePrefix("ws:")
                        val statusInfo = agentState.agentStatuses[agentId] ?: AgentStatusInfo()

                        if (relativePath !in statusInfo.transientWorkspaceFileContents) {
                            // Content not yet fetched — dispatch a read
                            val safeAgentIdStr = agentId.uuid.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                            val workspacePrefix = "$safeAgentIdStr/workspace"
                            val sandboxPath = "$workspacePrefix/$relativePath"

                            store.deferredDispatch(identity.handle, Action(
                                ActionRegistry.Names.FILESYSTEM_READ_MULTIPLE,
                                buildJsonObject {
                                    put("paths", buildJsonArray { add(sandboxPath) })
                                    put("correlationId", "wsod:${agentId.uuid}")
                                }
                            ))

                            platformDependencies.log(LogLevel.DEBUG, identity.handle,
                                "CONTEXT_UNCOLLAPSE: Dispatched on-demand READ_MULTIPLE for workspace file " +
                                        "'$relativePath' (agent '${agentId}'). Deferring reassembly until content arrives.")

                            deferReassembly = true
                        }
                    }
                }

                // ============================================================
                // On-demand SESSION FILE content fetch (sf: keys)
                //
                // Same pattern as workspace files, but uses cross-sandbox
                // delegation via session.READ_WORKSPACE_FILE instead of
                // direct filesystem access.
                // ============================================================
                if (action.name == ActionRegistry.Names.AGENT_CONTEXT_UNCOLLAPSE) {
                    val sfPartitionKey = action.payload?.get("partitionKey")?.jsonPrimitive?.contentOrNull
                    if (sfPartitionKey != null && sfPartitionKey.startsWith("sf:") && !sfPartitionKey.endsWith("/")) {
                        // Parse sf:<sessionHandle>:<relativePath>
                        val sfBody = sfPartitionKey.removePrefix("sf:")
                        val colonIndex = sfBody.indexOf(':')
                        if (colonIndex > 0) {
                            val sessionHandle = sfBody.substring(0, colonIndex)
                            val relativePath = sfBody.substring(colonIndex + 1)

                            if (relativePath.isNotBlank()) {
                                val statusInfo = agentState.agentStatuses[agentId] ?: AgentStatusInfo()

                                // Resolve session UUID from handle
                                val identityRegistry = store.state.value.identityRegistry
                                val sessionIdentity = identityRegistry[sessionHandle]
                                val sessionUUID = sessionIdentity?.uuid

                                if (sessionUUID != null) {
                                    val existingContents = statusInfo.transientSessionFileContents[IdentityUUID(sessionUUID)]
                                    if (existingContents == null || relativePath !in existingContents) {
                                        store.deferredDispatch(identity.handle, Action(
                                            ActionRegistry.Names.SESSION_READ_WORKSPACE_FILE,
                                            buildJsonObject {
                                                put("sessionId", sessionUUID)
                                                put("path", relativePath)
                                                put("requesterId", agent.identityHandle.handle)
                                                put("correlationId", "sfod:${agentId.uuid}:$sessionUUID")
                                            }
                                        ))

                                        platformDependencies.log(LogLevel.DEBUG, identity.handle,
                                            "CONTEXT_UNCOLLAPSE: Dispatched on-demand session file read for " +
                                                    "'$relativePath' (session '$sessionHandle', agent '$agentId'). " +
                                                    "Deferring reassembly.")

                                        deferReassembly = true
                                    }
                                } else {
                                    platformDependencies.log(LogLevel.WARN, identity.handle,
                                        "CONTEXT_UNCOLLAPSE: Cannot resolve session handle '$sessionHandle' to UUID. " +
                                                "On-demand session file read skipped.")
                                }
                            } else {
                                platformDependencies.log(LogLevel.WARN, identity.handle,
                                    "CONTEXT_UNCOLLAPSE: Empty relativePath in sf: key '$sfPartitionKey' " +
                                            "for agent '$agentId'. Likely a trailing colon in the partition key. Ignoring.")
                            }
                        } else {
                            platformDependencies.log(LogLevel.WARN, identity.handle,
                                "CONTEXT_UNCOLLAPSE: Malformed sf: partition key '$sfPartitionKey' — " +
                                        "expected 'sf:<sessionHandle>:<relativePath>'. Ignoring.")
                        }
                    }
                }

                saveContextState(agent, agentState, store)

                // If Manage Context is open for this agent, reassemble instantly —
                // unless we're waiting for an on-demand file content fetch.
                if (!deferReassembly) {
                    val updatedAgentState = store.state.value.featureStates["agent"] as? AgentRuntimeState
                    val updatedStatusInfo = updatedAgentState?.agentStatuses?.get(agentId)
                    if (updatedStatusInfo?.managedContext != null) {
                        reassembleOnToggle(agentId, store)
                    }
                }

                // Publish ACTION_RESULT for all callers (CommandBot matches via correlationId)
                val correlationId = action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull
                val partitionKey = action.payload?.get("partitionKey")?.jsonPrimitive?.contentOrNull ?: "unknown"
                val verb = if (action.name == ActionRegistry.Names.AGENT_CONTEXT_UNCOLLAPSE) "Expanded" else "Collapsed"
                publishActionResult(store, correlationId, action.name, success = true, summary = "$verb partition '$partitionKey'.")
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
                val payload = action.payload ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "ACTION_CREATED: Missing payload. Cannot handle command.")
                    return
                }
                val correlationId = payload["correlationId"]?.jsonPrimitive?.contentOrNull ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "ACTION_CREATED: Missing 'correlationId'. Cannot handle command.")
                    return
                }
                val originatorId = payload["originatorId"]?.jsonPrimitive?.contentOrNull ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "ACTION_CREATED: Missing 'originatorId' (correlationId=$correlationId). Cannot resolve agent.")
                    return
                }
                val originatorName = payload["originatorName"]?.jsonPrimitive?.contentOrNull ?: originatorId
                val sessionId = payload["sessionId"]?.jsonPrimitive?.contentOrNull ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "ACTION_CREATED: Missing 'sessionId' for originator '$originatorId' (correlationId=$correlationId).")
                    publishActionResult(store, correlationId, "unknown", false, error = "Malformed ACTION_CREATED: missing sessionId.")
                    return
                }
                val actionName = payload["actionName"]?.jsonPrimitive?.contentOrNull ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "ACTION_CREATED: Missing 'actionName' for originator '$originatorId' (correlationId=$correlationId).")
                    publishActionResult(store, correlationId, "unknown", false, error = "Malformed ACTION_CREATED: missing actionName.")
                    return
                }
                val actionPayload = payload["actionPayload"]?.jsonObject ?: buildJsonObject {}

                // Resolve originator: originatorId may be a UUID (direct key match)
                // or a handle like "agent.flash" (identity registry format).
                val agent = agentState.agents[IdentityUUID(originatorId)]
                    ?: agentState.agents.values.find { it.identityHandle.handle == originatorId }
                if (agent == null) {
                    // Not our agent — publish a failure result so CommandBot can report the
                    // unhandled command to the session instead of silently timing out.
                    platformDependencies.log(
                        LogLevel.WARN, identity.handle,
                        "ACTION_CREATED: Originator '$originatorId' is not a known agent. " +
                                "Ignoring action '$actionName' (correlationId=$correlationId). " +
                                "The command will go unhandled unless another feature picks it up."
                    )
                    // NOTE: We intentionally do NOT publish ACTION_RESULT here. The originator
                    // may be a non-agent entity (e.g., a human user dispatching through CommandBot)
                    // whose command is routed to a different feature. Publishing a failure here
                    // would produce false negatives. CommandBot's TTL timeout will catch truly
                    // unhandled commands.
                    return
                }
                val agentUuid = agent.identityUUID

                platformDependencies.log(
                    LogLevel.INFO, identity.handle,
                    "ACTION_CREATED: Handling '$actionName' for agent '$originatorId' (uuid=$agentUuid, correlationId=$correlationId)."
                )

                // Apply self-targeting enforcement for identity-scoped actions.
                var finalPayload = actionPayload

                // NVRAM self-targeting enforcement: agents with only agent:cognition
                // can only target their own NVRAM. Cross-agent targeting requires
                // agent:manage and is rejected with an error if missing.
                if (actionName == ActionRegistry.Names.AGENT_UPDATE_NVRAM) {
                    finalPayload = enforceSelfTargetOrReject(
                        finalPayload, agent, agentUuid, store, correlationId, actionName
                    ) ?: return // Rejected — error already published
                }

                // Context collapse/uncollapse self-targeting: same enforcement model.
                // Agents can only manage their own context unless they hold agent:manage.
                if (actionName == ActionRegistry.Names.AGENT_CONTEXT_UNCOLLAPSE ||
                    actionName == ActionRegistry.Names.AGENT_CONTEXT_COLLAPSE) {
                    finalPayload = enforceSelfTargetOrReject(
                        finalPayload, agent, agentUuid, store, correlationId, actionName
                    ) ?: return // Rejected — error already published
                }

                // ============================================================
                // HKG Write Guard
                //
                // For agent-dispatched KG write actions, apply appropriate
                // guards based on the action type:
                //
                // - CREATE_HOLON:  No expand check needed (holon is new).
                //                  Only verify the *parent* is in state.
                // - REPLACE_HOLON: Require target holon EXPANDED in context
                //                  (agent must have seen full content).
                // - UPDATE_HOLON_CONTENT: Should not be reachable by agents
                //                  (now internal), but guard defensively.
                // ============================================================
                if (actionName == ActionRegistry.Names.KNOWLEDGEGRAPH_CREATE_HOLON) {
                    // CREATE: No expand guard on the new holon (it doesn't exist yet).
                    // The KnowledgeGraphFeature handler will reject if parent is missing.
                    // Pass through.
                }
                else if (actionName == ActionRegistry.Names.KNOWLEDGEGRAPH_REPLACE_HOLON ||
                    actionName == ActionRegistry.Names.KNOWLEDGEGRAPH_UPDATE_HOLON_CONTENT) {
                    val targetHolonId = finalPayload["holonId"]?.jsonPrimitive?.contentOrNull
                    if (targetHolonId != null) {
                        val agentStatusInfo = agentState.agentStatuses[agentUuid] ?: AgentStatusInfo()
                        val holonCollapseState = agentStatusInfo.contextCollapseOverrides["hkg:$targetHolonId"]

                        if (holonCollapseState != CollapseState.EXPANDED) {
                            // Block — post sentinel error to agent's output session
                            val targetSessionUUID = agent.outputSessionId ?: agent.subscribedSessionIds.firstOrNull()
                            if (targetSessionUUID != null) {
                                store.deferredDispatch(identity.handle, Action(
                                    ActionRegistry.Names.SESSION_POST,
                                    buildJsonObject {
                                        put("session", targetSessionUUID.uuid)
                                        put("senderId", "system")
                                        put("message", "SYSTEM SENTINEL: Error: Write blocked! You are attempting to modify holon " +
                                                "'$targetHolonId' which is not fully expanded in your context. Expand the file first:\n" +
                                                "```raam_agent.CONTEXT_UNCOLLAPSE\n" +
                                                "{ \"partitionKey\": \"hkg:$targetHolonId\", \"scope\": \"single\" }\n" +
                                                "```\n" +
                                                "Then retry your write to ensure you are not omitting data.")
                                    }
                                ))
                            }

                            platformDependencies.log(
                                LogLevel.WARN, identity.handle,
                                "HKG Write Guard: Blocked write to collapsed holon '$targetHolonId' by agent '$agentUuid'."
                            )

                            publishActionResult(
                                store, correlationId, actionName, success = false,
                                error = "Write blocked: holon '$targetHolonId' is not expanded in agent context."
                            )
                            return // Do not forward the action
                        }
                    } else {
                        platformDependencies.log(
                            LogLevel.DEBUG, identity.handle,
                            "HKG Write Guard: $actionName from agent '$agentUuid' has no 'holonId'. " +
                                    "Guard bypassed — KnowledgeGraphFeature will reject downstream."
                        )
                    }
                }

                // ============================================================
                // Workspace Write Guard
                //
                // Agent-dispatched writes are sandboxed to {uuid}/workspace/
                // by FileSystemFeature's identity-aware sandbox. The path in
                // the payload is a clean relative path. Verify the target file
                // is EXPANDED in the agent's context before allowing overwrites.
                // New file creation (file not in listing) is always allowed.
                // Same rationale as the HKG Write Guard above.
                // ============================================================
                if (actionName == ActionRegistry.Names.FILESYSTEM_WRITE) {
                    val relativePath = finalPayload["path"]?.jsonPrimitive?.contentOrNull
                    if (relativePath != null) {
                        val agentStatusInfo = agentState.agentStatuses[agentUuid] ?: AgentStatusInfo()
                        val fileCollapseState = agentStatusInfo.contextCollapseOverrides["ws:$relativePath"]

                        // Workspace listing entries are relative to the feature sandbox
                        // ({APP_ZONE}/agent/) and include the {uuid}/workspace/ prefix.
                        // Strip that prefix to compare against the clean relative path.
                        val safeAgentIdStr = agentUuid.uuid.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                        val workspacePrefix = "$safeAgentIdStr/workspace/"
                        val workspaceListing = agentStatusInfo.transientWorkspaceListing
                        val fileExistsInListing = workspaceListing != null && workspaceListing.any { entry ->
                            val entryPath = entry.jsonObject["path"]?.jsonPrimitive?.contentOrNull
                                ?.replace("\\", "/")
                                ?.removePrefix(workspacePrefix)
                            entryPath == relativePath
                        }

                        if (fileExistsInListing && fileCollapseState != CollapseState.EXPANDED) {
                            // Block — post sentinel error to agent's output session
                            val targetSessionUUID = agent.outputSessionId ?: agent.subscribedSessionIds.firstOrNull()
                            if (targetSessionUUID != null) {
                                store.deferredDispatch(identity.handle, Action(
                                    ActionRegistry.Names.SESSION_POST,
                                    buildJsonObject {
                                        put("session", targetSessionUUID.uuid)
                                        put("senderId", "system")
                                        put("message", "SYSTEM SENTINEL: Error: Write blocked! You are attempting to modify workspace file " +
                                                "'$relativePath' which is not expanded in your context. Expand the file first:\n" +
                                                "```raam_agent.CONTEXT_UNCOLLAPSE\n" +
                                                "{ \"partitionKey\": \"ws:$relativePath\", \"scope\": \"single\" }\n" +
                                                "```\n" +
                                                "Then retry your write to ensure you are not omitting data.")
                                    }
                                ))
                            }

                            platformDependencies.log(
                                LogLevel.WARN, identity.handle,
                                "Workspace Write Guard: Blocked write to collapsed file '$relativePath' by agent '$agentUuid'."
                            )

                            publishActionResult(
                                store, correlationId, actionName, success = false,
                                error = "Write blocked: workspace file '$relativePath' is not expanded in agent context."
                            )
                            return // Do not forward the action
                        }
                    }
                }

                // Inject correlationId into the payload
                val enrichedPayload = if (finalPayload["correlationId"] == null) {
                    JsonObject(finalPayload + ("correlationId" to JsonPrimitive(correlationId)))
                } else {
                    finalPayload
                }

                // Track the pending command in state BEFORE dispatching the domain
                // action. The domain action's targeted response (e.g., FILESYSTEM_RETURN_LIST)
                // is routed via handleTargetedResponse → routeCommandResponseToSession, which
                // looks up pendingCommands[correlationId]. If the domain action is dispatched
                // first, its response may arrive before REGISTER_PENDING_COMMAND is processed,
                // causing the lookup to miss and the data to be misrouted to the cognitive
                // pipeline instead of the session.
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

                // Dispatch the domain action attributed to the agent.
                // Pre-Phase 1 fix: dispatch as the agent's identity handle (e.g., "agent.coder-1")
                // instead of the feature handle ("agent") so the Store permission guard
                // evaluates the correct identity's effective permissions.
                store.deferredDispatch(agent.identityHandle.handle, Action(name = actionName, payload = enrichedPayload))

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

    // =========================================================================
    // Managed Context: Instant Reassembly + Debounced Preview
    // =========================================================================

    /**
     * Called when a collapse toggle happens while Manage Context is open.
     * Runs the fast-path assembly and resets the debounced full preview.
     */
    private fun reassembleOnToggle(agentId: IdentityUUID, store: Store) {
        val agentState = store.state.value.featureStates["agent"] as? AgentRuntimeState ?: return
        val agent = agentState.agents[agentId] ?: return
        val statusInfo = agentState.agentStatuses[agentId] ?: return
        val snapshot = statusInfo.managedContext?.transientDataSnapshot ?: return

        // Fast path: partition metadata only, no string assembly
        val result = CognitivePipeline.assemblePartitions(
            agent, snapshot.sessionLedgers, snapshot.hkgContext, agentState, store
        )
        if (result != null) {
            CognitivePipeline.pendingManagedPartitions = result
            store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_SET_MANAGED_PARTITIONS, buildJsonObject {
                put("agentId", agentId.uuid)
            }))
        }

        // Reset the debounced full preview (5s timer)
        resetDebouncedPreview(agentId, store)
    }

    /**
     * Resets the debounced preview timer for the Manage Context view.
     * After 5 seconds, runs full assembly + gateway preview for token estimation.
     * Cancels any existing timer for this agent (Red Team C4).
     */
    private fun resetDebouncedPreview(agentId: IdentityUUID, store: Store) {
        previewDebounceJobs[agentId]?.cancel()
        previewDebounceJobs[agentId] = coroutineScope.launch {
            delay(5_000L)
            val agentState = store.state.value.featureStates["agent"] as? AgentRuntimeState ?: return@launch
            val agent = agentState.agents[agentId] ?: return@launch
            val statusInfo = agentState.agentStatuses[agentId] ?: return@launch
            val snapshot = statusInfo.managedContext?.transientDataSnapshot ?: return@launch

            // Full assembly for updated system prompt
            val result = CognitivePipeline.assembleContext(
                agent, snapshot.sessionLedgers, snapshot.hkgContext, agentState, store
            ) ?: return@launch

            // Dispatch gateway preview for token estimation
            store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.GATEWAY_PREPARE_PREVIEW, buildJsonObject {
                put("providerId", agent.modelProvider)
                put("modelName", agent.modelName)
                put("correlationId", agentId.uuid)
                put("contents", buildJsonArray {})
                put("systemPrompt", result.systemPrompt)
            }))
        }
    }

    /**
     * Phase 5 extension point: forward broadcast actions to Lua strategy listeners.
     *
     * Currently a no-op stub. Phase 5 will implement:
     * - Per-agent filter by on_action_filter manifest
     * - Pre-computed observable action sets
     * - Rate limiting
     * - Sandboxed execution
     *
     * See: on_action Routing in the context architecture redesign doc.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun forwardToLuaStrategies(action: Action, agentState: AgentRuntimeState, store: Store) {
        // No-op — Phase 5 implementation
    }

    // ========================================================================
    // Polymorphic lifecycle dispatch helpers
    // ========================================================================

    // ========================================================================
    // External Strategy Registration
    // ========================================================================

    private fun handleRegisterExternalStrategy(action: Action, store: Store) {
        val correlationId = action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull
        val payload = action.payload ?: run {
            platformDependencies.log(LogLevel.ERROR, identity.handle, "REGISTER_EXTERNAL_STRATEGY: missing payload")
            publishActionResult(store, correlationId, ActionRegistry.Names.AGENT_REGISTER_EXTERNAL_STRATEGY,
                success = false, error = "Missing payload")
            return
        }
        val strategyId = payload["strategyId"]?.jsonPrimitive?.contentOrNull ?: run {
            platformDependencies.log(LogLevel.ERROR, identity.handle, "REGISTER_EXTERNAL_STRATEGY: missing strategyId")
            publishActionResult(store, correlationId, ActionRegistry.Names.AGENT_REGISTER_EXTERNAL_STRATEGY,
                success = false, error = "Missing strategyId")
            return
        }
        val displayName = payload["displayName"]?.jsonPrimitive?.contentOrNull ?: strategyId
        val featureHandle = payload["featureHandle"]?.jsonPrimitive?.contentOrNull ?: run {
            platformDependencies.log(LogLevel.ERROR, identity.handle, "REGISTER_EXTERNAL_STRATEGY: missing featureHandle")
            publishActionResult(store, correlationId, ActionRegistry.Names.AGENT_REGISTER_EXTERNAL_STRATEGY,
                success = false, error = "Missing featureHandle")
            return
        }

        // Parse resource slots
        val slots = payload["resourceSlots"]?.jsonArray?.mapNotNull { elem ->
            val obj = elem.jsonObject
            val slotId = obj["slotId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val typeName = obj["type"]?.jsonPrimitive?.contentOrNull ?: "SYSTEM_INSTRUCTION"
            val type = try { AgentResourceType.valueOf(typeName) } catch (_: Exception) { AgentResourceType.SYSTEM_INSTRUCTION }
            ResourceSlot(
                slotId = slotId,
                type = type,
                displayName = obj["displayName"]?.jsonPrimitive?.contentOrNull ?: slotId,
                description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
                isRequired = obj["isRequired"]?.jsonPrimitive?.booleanOrNull ?: true
            )
        } ?: emptyList()

        // Parse config fields
        val configFields = payload["configFields"]?.jsonArray?.mapNotNull { elem ->
            val obj = elem.jsonObject
            val key = obj["key"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val typeName = obj["type"]?.jsonPrimitive?.contentOrNull ?: "OUTPUT_SESSION"
            val type = try { StrategyConfigFieldType.valueOf(typeName) } catch (_: Exception) { StrategyConfigFieldType.OUTPUT_SESSION }
            StrategyConfigField(
                key = key,
                type = type,
                displayName = obj["displayName"]?.jsonPrimitive?.contentOrNull ?: key,
                description = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
            )
        } ?: emptyList()

        val initialState = payload["initialState"] ?: JsonNull

        val proxy = ExternalStrategyProxy(
            identityHandle = IdentityHandle(strategyId),
            displayName = displayName,
            featureHandle = featureHandle,
            declaredSlots = slots,
            declaredConfigFields = configFields,
            declaredInitialState = initialState
        )

        CognitiveStrategyRegistry.register(proxy)
        platformDependencies.log(LogLevel.INFO, identity.handle,
            "External strategy registered: $strategyId (provider: $featureHandle)")

        // Respond to the registrant
        val originator = action.originator
        if (originator != null) {
            store.deferredDispatch(identity.handle, Action(
                name = ActionRegistry.Names.AGENT_RETURN_STRATEGY_REGISTERED,
                payload = buildJsonObject {
                    put("strategyId", strategyId)
                    put("success", true)
                },
                targetRecipient = originator
            ))
        }

        publishActionResult(store, correlationId, ActionRegistry.Names.AGENT_REGISTER_EXTERNAL_STRATEGY,
            success = true, summary = "Registered external strategy '$strategyId'.")
    }

    private fun handleUnregisterExternalStrategy(action: Action, store: Store) {
        val correlationId = action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull
        val strategyId = action.payload?.get("strategyId")?.jsonPrimitive?.contentOrNull ?: run {
            platformDependencies.log(LogLevel.ERROR, identity.handle, "UNREGISTER_EXTERNAL_STRATEGY: missing strategyId")
            publishActionResult(store, correlationId, ActionRegistry.Names.AGENT_UNREGISTER_EXTERNAL_STRATEGY,
                success = false, error = "Missing strategyId")
            return
        }
        // CognitiveStrategyRegistry doesn't have an unregister method yet —
        // for now, log that the strategy was requested to be removed.
        // Agents using it will fall back to the default strategy via the registry's
        // get() fallback behavior.
        platformDependencies.log(LogLevel.INFO, identity.handle,
            "External strategy unregister requested: $strategyId (not yet implemented in registry)")
        publishActionResult(store, correlationId, ActionRegistry.Names.AGENT_UNREGISTER_EXTERNAL_STRATEGY,
            success = true, summary = "Unregister requested for strategy '$strategyId'.")
    }

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

    /**
     * Shared self-targeting enforcement for agent-originated commands.
     *
     * Used by UPDATE_NVRAM, CONTEXT_UNCOLLAPSE, and CONTEXT_COLLAPSE to ensure
     * agents can only target themselves unless they hold `agent:manage`.
     *
     * Resolution logic:
     * 1. agentId absent       → inject caller's UUID (self-target)
     * 2. agentId resolves to self → normalize to UUID
     * 3. agentId resolves to another agent + caller holds agent:manage → allow, resolve to UUID
     * 4. agentId resolves to another agent + caller lacks agent:manage → reject with error
     * 5. agentId doesn't resolve to any known agent → reject with error
     *
     * @return Rewritten payload with agentId set to the resolved UUID, or null if
     *         the request was rejected (ACTION_RESULT error already published).
     */
    private fun enforceSelfTargetOrReject(
        payload: JsonObject,
        callerAgent: AgentInstance,
        callerUuid: IdentityUUID,
        store: Store,
        correlationId: String?,
        actionName: String
    ): JsonObject? {
        val rawTargetId = payload["agentId"]?.jsonPrimitive?.contentOrNull

        if (rawTargetId == null) {
            // Case 1: No agentId provided — self-target
            return JsonObject(payload + ("agentId" to JsonPrimitive(callerUuid.uuid)))
        }

        // Resolve the target through the identity registry
        val registry = store.state.value.identityRegistry
        val targetIdentity = registry.resolve(rawTargetId, parentHandle = "agent")
        val targetUuid = targetIdentity?.identityUUID

        if (targetUuid == null) {
            // Case 5: Target doesn't exist
            val suggestions = registry.suggestMatches(rawTargetId, parentHandle = "agent")
                .joinToString(", ") { "'${it.name}' (${it.uuid})" }
            val hint = if (suggestions.isNotEmpty()) " Did you mean: $suggestions?" else ""
            publishActionResult(store, correlationId, actionName, false,
                error = "Agent '$rawTargetId' not found.$hint")
            platformDependencies.log(LogLevel.WARN, identity.handle,
                "enforceSelfTarget: Agent '$rawTargetId' not found for $actionName " +
                        "(caller=${callerAgent.identityHandle.handle}).$hint")
            return null
        }

        if (targetUuid == callerUuid) {
            // Case 2: Targeting self (by handle, name, or UUID) — normalize to UUID
            return JsonObject(payload + ("agentId" to JsonPrimitive(callerUuid.uuid)))
        }

        // Cross-agent targeting — check permissions
        val callerIdentity = registry[callerAgent.identityHandle.handle]
        val effective = callerIdentity?.let { store.resolveEffectivePermissions(it) }
        val hasManage = effective?.get("agent:manage")?.level == PermissionLevel.YES

        if (!hasManage) {
            // Case 4: No permission — reject
            publishActionResult(store, correlationId, actionName, false,
                error = "Permission denied: '${callerAgent.identity.name}' cannot target " +
                        "agent '${targetIdentity.name}' for $actionName. Requires agent:manage permission.")
            platformDependencies.log(LogLevel.WARN, identity.handle,
                "enforceSelfTarget: Agent '${callerAgent.identityHandle.handle}' attempted " +
                        "cross-agent $actionName targeting '${targetIdentity.name}' without agent:manage. Rejected.")
            return null
        }

        // Case 3: Has agent:manage — allow cross-agent targeting, resolve to UUID
        platformDependencies.log(LogLevel.INFO, identity.handle,
            "enforceSelfTarget: Agent '${callerAgent.identityHandle.handle}' targeting " +
                    "'${targetIdentity.name}' for $actionName (permitted via agent:manage).")
        return JsonObject(payload + ("agentId" to JsonPrimitive(targetUuid.uuid)))
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

    /**
     * Broadcasts the current snapshot of all agents (uuid, name, subscribedSessionIds)
     * so other features can discover agents without cross-feature imports.
     * Fired on startup after AGENTS_LOADED and whenever subscriptions change.
     */
    private fun broadcastAgentNames(agentState: AgentRuntimeState, store: Store) {
        store.deferredDispatch(identity.handle, Action(
            name = ActionRegistry.Names.AGENT_AGENT_NAMES_UPDATED,
            payload = buildJsonObject {
                put("agents", buildJsonArray {
                    agentState.agents.values.forEach { agent ->
                        add(buildJsonObject {
                            put("uuid", agent.identityUUID.uuid)
                            put("name", agent.identity.name)
                            put("subscribedSessionIds", buildJsonArray {
                                agent.subscribedSessionIds.forEach { add(it.uuid) }
                            })
                        })
                    }
                })
            }
        ))
    }

    private fun saveAgentNvram(agent: AgentInstance, store: Store) {
        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_WRITE, buildJsonObject {
            put("path", "${agent.identityUUID.uuid}/$nvramFILENAME")
            put("content", json.encodeToString(agent.cognitiveState))
        }))
    }

    /**
     * Persists the agent's context collapse overrides to context.json.
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
                CognitivePipeline.handleTargetedAction(action, store)
            }
            ActionRegistry.Names.FILESYSTEM_RETURN_LIST -> {
                val hasCorrelationId = payload["correlationId"]?.jsonPrimitive?.contentOrNull != null
                if (hasCorrelationId) {
                    CognitivePipeline.handleTargetedAction(action, store)
                } else {
                    handleFileSystemListResponse(payload, store)
                }
            }
            ActionRegistry.Names.FILESYSTEM_RETURN_READ -> handleFileSystemReadResponse(payload, store)
            ActionRegistry.Names.FILESYSTEM_RETURN_FILES_CONTENT -> {
                val fileContentCorrelationId = payload["correlationId"]?.jsonPrimitive?.contentOrNull
                if (fileContentCorrelationId != null && fileContentCorrelationId.startsWith("ws:")) {
                    // Turn-initiation workspace file reads → pipeline handler
                    CognitivePipeline.handleTargetedAction(action, store)
                } else if (fileContentCorrelationId != null && fileContentCorrelationId.startsWith("wsod:")) {
                    // On-demand workspace file reads (from CONTEXT_UNCOLLAPSE) → merge + reassemble
                    handleOnDemandWorkspaceFileResponse(payload, store)
                }
                // If not a workspace response and not handled as a pending command above, ignore.
            }
            ActionRegistry.Names.SESSION_RETURN_WORKSPACE_FILES,
            ActionRegistry.Names.SESSION_RETURN_WORKSPACE_FILE -> {
                CognitivePipeline.handleTargetedAction(action, store)
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
            // The sessionId from CommandBot (via MESSAGE_POSTED) is the session's
            // localHandle (e.g., "pet-studies"), not a UUID. Pass it through directly
            // to DELIVER_TO_SESSION → CommandBot.postRawToSession → SESSION_POST,
            // which accepts localHandles for session identification.
            // Note: resolveSessionHandle(findByUUID) cannot be used here because
            // the sessionId is a localHandle, not a UUID.
            val sessionId = pending.sessionId.uuid
            store.deferredDispatch(identity.handle, Action(
                ActionRegistry.Names.COMMANDBOT_DELIVER_TO_SESSION,
                buildJsonObject {
                    put("correlationId", pending.correlationId)
                    put("sessionId", sessionId)
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
                // Filter to UUID-named directories only. Non-UUID entries (e.g., "resources",
                // stray "session" folders, or other debris) are not agent directories.
                val agentDirs = fileList.filter { entry ->
                    if (!entry.isDirectory) return@filter false
                    val dirName = platformDependencies.getFileName(entry.path)
                    if (!stringIsUUID(dirName)) {
                        if (dirName != "resources") {
                            platformDependencies.log(LogLevel.WARN, identity.handle,
                                "Skipping non-UUID directory in agent sandbox: '$dirName'")
                        }
                        return@filter false
                    }
                    true
                }
                agentLoadCount = agentDirs.size

                if (agentLoadCount == 0) {
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_AGENTS_LOADED))
                } else {
                    agentDirs.forEach { entry ->
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

    /**
     * Handles the response from an on-demand workspace file read triggered by
     * CONTEXT_UNCOLLAPSE. Merges the file content into transientWorkspaceFileContents
     * and triggers reassembly of the Manage Context view.
     *
     * Uses "wsod:" (workspace on-demand) correlation ID prefix to distinguish from
     * turn-initiation "ws:" reads handled by the CognitivePipeline.
     */
    private fun handleOnDemandWorkspaceFileResponse(payload: JsonObject, store: Store) {
        val correlationId = payload["correlationId"]?.jsonPrimitive?.contentOrNull ?: return
        if (!correlationId.startsWith("wsod:")) return

        val agentIdStr = correlationId.removePrefix("wsod:")
        val agentId = IdentityUUID(agentIdStr)
        val contentsJson = payload["contents"]?.jsonObject

        if (contentsJson == null || contentsJson.isEmpty()) {
            platformDependencies.log(LogLevel.WARN, identity.handle,
                "handleOnDemandWorkspaceFileResponse: Empty contents for agent '$agentIdStr'. " +
                        "File may not exist on disk.")
            // Still trigger reassembly so the UI updates (will show placeholder)
            val agentState = store.state.value.featureStates[identity.handle] as? AgentRuntimeState
            val statusInfo = agentState?.agentStatuses?.get(agentId)
            if (statusInfo?.managedContext != null) {
                reassembleOnToggle(agentId, store)
            }
            return
        }

        // Strip the sandbox prefix to get workspace-relative paths,
        // matching the convention used by CognitivePipeline.handleWorkspaceFileContentsResponse.
        val safeAgentId = agentIdStr.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val workspacePrefix = "$safeAgentId/workspace/"

        val relativeContents = buildJsonObject {
            contentsJson.forEach { (path, content) ->
                val normalizedPath = path.replace("\\", "/")
                val relativePath = normalizedPath.removePrefix(workspacePrefix)
                put(relativePath, content)
            }
        }

        // Merge (not replace) into transientWorkspaceFileContents
        store.deferredDispatch(identity.handle, Action(
            ActionRegistry.Names.AGENT_MERGE_WORKSPACE_FILE_CONTENT,
            buildJsonObject {
                put("agentId", agentIdStr)
                put("contents", relativeContents)
            }
        ))

        platformDependencies.log(LogLevel.DEBUG, identity.handle,
            "handleOnDemandWorkspaceFileResponse: Merged ${contentsJson.size} file(s) for agent '$agentIdStr'. " +
                    "Scheduling reassembly.")

        // Schedule reassembly after the MERGE action processes.
        // deferredDispatch is FIFO, so MERGE will be in the queue before this runs,
        // but we launch a coroutine to ensure we read state AFTER the merge is applied.
        coroutineScope.launch {
            kotlinx.coroutines.yield()
            val agentState = store.state.value.featureStates[identity.handle] as? AgentRuntimeState ?: return@launch
            val statusInfo = agentState.agentStatuses[agentId] ?: return@launch
            if (statusInfo.managedContext != null) {
                reassembleOnToggle(agentId, store)
            }
        }
    }

    private fun handleFileSystemReadResponse(payload: JsonObject, store: Store) {
        val path = (payload["path"]?.jsonPrimitive?.contentOrNull ?: "").replace("\\", "/")
        val content = payload["content"]?.jsonPrimitive?.contentOrNull

        if (content == null) {
            // Even without content, we must decrement agentLoadCount for missing agent.json
            // files so the load-completion gate (AGENT_AGENTS_LOADED) still fires.
            if (path.endsWith("/$agentConfigFILENAME")) {
                val dirName = path.substringBeforeLast("/")
                platformDependencies.log(LogLevel.WARN, identity.handle,
                    "Agent directory '$dirName' has no $agentConfigFILENAME — skipping (orphaned directory?).")
                agentLoadCount--
                if (agentLoadCount <= 0) {
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_AGENTS_LOADED))
                }
            } else {
                platformDependencies.log(LogLevel.DEBUG, identity.handle,
                    "handleFileSystemReadResponse: Missing content in read response for path='$path'.")
            }
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
            // Context collapse overrides
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
        private val viewKeyManager = "feature.agent.manager"

        override val stageViews: Map<String, @Composable (Store, List<Feature>) -> Unit> =
            mapOf(
                viewKeyManager to { store, _ -> AgentManagerView(store, platformDependencies) },
                "feature.agent.context_viewer" to { store, _ -> ManageContextView(store) }
            )

        override fun ribbonEntries(store: Store, activeViewKey: String?): List<RibbonEntry> = listOf(
            RibbonEntry(
                id = "agent.manager",
                label = "Agent Manager",
                icon = Icons.Default.Bolt,
                priority = 90,
                isActive = activeViewKey == viewKeyManager,
                onClick = {
                    store.dispatch(
                        "agent",
                        Action(
                            ActionRegistry.Names.CORE_SET_ACTIVE_VIEW,
                            buildJsonObject { put("key", viewKeyManager) },
                        ),
                    )
                },
            ),
        )
        @Composable
        override fun PartialView(store: Store, partId: String, context: Any?) {
            when (partId) {
                "agent.avatar" -> AgentAvatarPartial(store, context)
                "session.message.menu" -> AddAgentMenuPartial(store, context)
            }
        }

        @Composable
        private fun AgentAvatarPartial(store: Store, context: Any?) {
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
            AgentAvatarCard(
                agent = agent,
                sessionUUID = sessionUUID,
                store = store,
                platformDependencies = platformDependencies
            )
        }

        @Suppress("UNCHECKED_CAST")
        @Composable
        private fun AddAgentMenuPartial(store: Store, context: Any?) {
            val ctx = context as? Map<*, *> ?: return
            val sessionUUID = ctx["sessionUUID"] as? String ?: return
            val onDismiss = ctx["onDismiss"] as? () -> Unit ?: {}

            val appState by store.state.collectAsState()
            val agentState = appState.featureStates[identity.handle] as? AgentRuntimeState ?: return

            val unsubscribedAgents = agentState.agents.values.filter { agent ->
                agent.subscribedSessionIds.none { it.uuid == sessionUUID }
            }
            if (unsubscribedAgents.isEmpty()) return

            var submenuExpanded by remember { mutableStateOf(false) }
            HorizontalDivider()
            Box {
                DropdownMenuItem(
                    text = { Text("Add agent") },
                    onClick = { submenuExpanded = true },
                    leadingIcon = { Icon(Icons.Default.PersonAdd, null) },
                    trailingIcon = { Icon(Icons.Default.ArrowRight, null) }
                )
                DropdownMenu(
                    expanded = submenuExpanded,
                    onDismissRequest = { submenuExpanded = false }
                ) {
                    unsubscribedAgents.forEach { agent ->
                        val agentIdentity = appState.identityRegistry.values.find { it.uuid == agent.identityUUID.uuid }
                        val agentColor = agentIdentity?.resolveDisplayColor()
                            ?: MaterialTheme.colorScheme.primary

                        DropdownMenuItem(
                            text = { Text(agent.identity.name, color = agentColor) },
                            onClick = {
                                store.dispatch(identity.handle, Action(
                                    ActionRegistry.Names.AGENT_SET_SESSION_SUBSCRIPTION,
                                    buildJsonObject {
                                        put("agentId", agent.identityUUID.uuid)
                                        put("sessionId", sessionUUID)
                                        put("subscribed", true)
                                    }
                                ))
                                submenuExpanded = false
                                onDismiss()
                            },
                            leadingIcon = {
                                if (agentIdentity?.displayEmoji != null) {
                                    Text(
                                        agentIdentity.displayEmoji!!,
                                        fontSize = 20.sp,
                                        color = agentColor,
                                        textAlign = TextAlign.Center
                                    )
                                } else {
                                    val iconVector = IconRegistry.resolve(agentIdentity?.displayIcon)
                                        ?: IconRegistry.defaultAgentIcon
                                    Icon(iconVector, null, tint = agentColor)
                                }
                            }
                        )
                    }
                }
            }
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