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
import app.auf.core.generated.ExposedActions
import app.auf.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/**
 * ## The Executor (Refined)
 * A pure switchboard feature.
 * - Config/Persistence -> AgentCrudLogic
 * - Runtime State -> AgentRuntimeReducer
 * - Cognition -> AgentCognitivePipeline
 * - Side Effects -> AgentAvatarLogic / Self
 *
 * Subscribes to ACTION_CREATED from CommandBot to handle validated agent commands:
 * applies workspace sandboxing, dispatches domain actions, and posts attributed results.
 */
class AgentRuntimeFeature(
    private val platformDependencies: PlatformDependencies,
    private val coroutineScope: CoroutineScope
) : Feature {
    override val identity: Identity = Identity(uuid = null, handle = "agent", localHandle = "agent", name="Agent Runtime")

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val agentConfigFILENAME = "agent.json"
    private val nvramFILENAME = "nvram.json"

    private val workspaceSubpathMarker = "/workspace/"

    private val commandBotSenderId = "commandbot"

    private val activeTurnJobs = mutableMapOf<String, Job>()
    private val avatarUpdateJobs = mutableMapOf<String, Job>()
    private var agentLoadCount = 0

    // ========================================================================
    // ACTION_CREATED: Pending command response tracking
    // ========================================================================

    /**
     * Tracks commands dispatched on behalf of agents, keyed by correlationId.
     * Used to attribute PrivateData responses back to the requesting agent.
     */
    private data class PendingCommandResponse(
        val correlationId: String,
        val agentId: String,
        val agentName: String,
        val sessionId: String,
        val actionName: String,
        val dispatchedAt: Long
    )

    private val pendingCommandResponses = mutableMapOf<String, PendingCommandResponse>()

    override fun init(store: Store) {
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
            // Phase 3: Targeted responses — migrated from onPrivateData.
            ActionRegistry.Names.SESSION_RESPONSE_LEDGER,
            ActionRegistry.Names.KNOWLEDGEGRAPH_RESPONSE_CONTEXT,
            ActionRegistry.Names.GATEWAY_RESPONSE_RESPONSE,
            ActionRegistry.Names.GATEWAY_RESPONSE_PREVIEW,
            ActionRegistry.Names.FILESYSTEM_RESPONSE_LIST,
            ActionRegistry.Names.FILESYSTEM_RESPONSE_READ,
            ActionRegistry.Names.FILESYSTEM_RESPONSE_FILES_CONTENT -> {
                handleTargetedResponse(action, store)
            }
            // --- Startup ---
            ActionRegistry.Names.SYSTEM_STARTING -> {
                // Inject built-in resources FIRST (ensures they're always available)
                AgentDefaults.builtInResources.forEach { resource ->
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.AGENT_RESOURCE_LOADED,
                        json.encodeToJsonElement(resource) as JsonObject
                    ))
                }
                // Then load user-defined resources from disk
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_LIST))
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_LIST, buildJsonObject {
                    put("subpath", "resources")
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
                if (agentState.sessionNames.isNotEmpty()) {
                    SovereignHKGResourceLogic.ensureSovereignSessions(store, agentState)
                }
            }
            ActionRegistry.Names.AGENT_VALIDATE_SOVEREIGN_STATE -> {
                SovereignHKGResourceLogic.ensureSovereignSessions(store, agentState)
            }
            ActionRegistry.Names.AGENT_AGENT_LOADED -> {
                val agent = action.payload?.let { json.decodeFromJsonElement<AgentInstance>(it) } ?: return
                val uuid = agent.identity.uuid ?: return
                AgentAvatarLogic.updateAgentAvatars(uuid, store, AgentStatus.IDLE)
                broadcastAgentNames(agentState, store)
                // Register agent identity
                store.deferredDispatch(identity.handle, Action(
                    ActionRegistry.Names.CORE_REGISTER_IDENTITY,
                    buildJsonObject {
                        put("uuid", uuid)
                        put("name", agent.identity.name)
                    }
                ))
            }
            ActionRegistry.Names.AGENT_RESOURCE_LOADED -> {
                // No side effects needed, pure reducer handles state merge
            }

            // --- Agent CRUD Side Effects ---
            ActionRegistry.Names.AGENT_CREATE -> {
                val agentToSave = agentState.agents.values.lastOrNull() ?: run {
                    platformDependencies.log(LogLevel.ERROR, identity.handle, "AGENT_CREATE: No agents in state after CREATE action. Reducer may have failed.")
                    return
                }
                saveAgentConfig(agentToSave, store)
                broadcastAgentNames(agentState, store)
                SovereignHKGResourceLogic.ensureSovereignSessions(store, agentState)
                // Register agent identity (no localHandle — CoreFeature generates via slugifyName)
                store.deferredDispatch(identity.handle, Action(
                    ActionRegistry.Names.CORE_REGISTER_IDENTITY,
                    buildJsonObject {
                        put("uuid", agentToSave.identity.uuid)
                        put("name", agentToSave.identity.name)
                    }
                ))
            }
            ActionRegistry.Names.AGENT_CLONE -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                val agentToClone = agentState.agents[agentId] ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "AGENT_CLONE: Source agent '$agentId' not found in state.")
                    return
                }
                val createPayload = buildJsonObject {
                    put("name", "${agentToClone.identity.name} (Copy)")
                    agentToClone.knowledgeGraphId?.let { put("knowledgeGraphId", it) }
                    put("modelProvider", agentToClone.modelProvider)
                    put("modelName", agentToClone.modelName)
                    put("subscribedSessionIds", buildJsonArray { agentToClone.subscribedSessionIds.forEach { add(it) } })
                    put("automaticMode", agentToClone.automaticMode)
                    put("autoWaitTimeSeconds", agentToClone.autoWaitTimeSeconds)
                    put("autoMaxWaitTimeSeconds", agentToClone.autoMaxWaitTimeSeconds)
                }
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_CREATE, createPayload))
            }
            ActionRegistry.Names.AGENT_TOGGLE_AUTOMATIC_MODE, ActionRegistry.Names.AGENT_TOGGLE_ACTIVE -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                val agent = agentState.agents[agentId] ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "AGENT_TOGGLE: Agent '$agentId' not found in state.")
                    return
                }
                saveAgentConfig(agent, store)
                AgentAvatarLogic.touchAgentAvatarCard(agent, agentState, store)
            }
            ActionRegistry.Names.AGENT_UPDATE_CONFIG -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                val oldAgent = (previousState as? AgentRuntimeState)?.agents?.get(agentId)
                val newAgent = agentState.agents[agentId] ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "AGENT_UPDATE_CONFIG: Agent '$agentId' not found in state after update.")
                    return
                }

                SovereignHKGResourceLogic.handleSovereignAssignment(store, oldAgent, newAgent)
                SovereignHKGResourceLogic.handleSovereignRevocation(store, oldAgent, newAgent)

                saveAgentConfig(newAgent, store)
                broadcastAgentNames(agentState, store)
                AgentAvatarLogic.updateAgentAvatars(agentId, store)
                SovereignHKGResourceLogic.ensureSovereignSessions(store, agentState)
                // If agent name changed, update identity atomically
                if (oldAgent != null && oldAgent.identity.name != newAgent.identity.name) {
                    val currentHandle = newAgent.identity.handle
                    if (currentHandle.isNotBlank()) {
                        store.deferredDispatch(identity.handle, Action(
                            ActionRegistry.Names.CORE_UPDATE_IDENTITY,
                            buildJsonObject {
                                put("handle", currentHandle)
                                put("newName", newAgent.identity.name)
                            }
                        ))
                    }
                }
            }
            ActionRegistry.Names.AGENT_NVRAM_LOADED -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                val agent = agentState.agents[agentId] ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "NVRAM_LOADED: Agent '$agentId' not found. Cannot persist NVRAM.")
                    return
                }
                // Reducer replaced cognitiveState
                // Save to disk (handles both: strategy transitions AND redundant save from disk loading)
                saveAgentNvram(agent, store)
            }
            ActionRegistry.Names.AGENT_UPDATE_NVRAM -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                val agent = agentState.agents[agentId] ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "UPDATE_NVRAM: Agent '$agentId' not found. Cannot update NVRAM.")
                    return
                }
                // Reducer already merged updates into cognitiveState
                // Save directly to disk
                saveAgentNvram(agent, store)
            }
            ActionRegistry.Names.AGENT_DELETE -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                agentState.agentAvatarCardIds[agentId]?.forEach { (sessionId, messageId) ->
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_DELETE_MESSAGE, buildJsonObject {
                        put("session", sessionId)
                        put("messageId", messageId)
                    }))
                }
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_DELETE_DIRECTORY, buildJsonObject { put("subpath", agentId) }))
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_CONFIRM_DELETE, buildJsonObject { put("agentId", agentId) }))
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_AGENT_DELETED, buildJsonObject { put("agentId", agentId) }))
                broadcastAgentNames(agentState, store)
                // Unregister agent identity (cascades any sub-identities)
                val agentToDelete = (previousState as? AgentRuntimeState)?.agents?.get(agentId)
                val deleteHandle = agentToDelete?.identity?.handle
                if (!deleteHandle.isNullOrBlank()) {
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.CORE_UNREGISTER_IDENTITY,
                        buildJsonObject { put("handle", deleteHandle) }
                    ))
                }
            }

            // --- Resource CRUD Side Effects ---
            ActionRegistry.Names.AGENT_CREATE_RESOURCE -> {
                val newResource = agentState.resources.lastOrNull() ?: run {
                    platformDependencies.log(LogLevel.ERROR, identity.handle, "CREATE_RESOURCE: No resources in state after CREATE action. Reducer may have failed.")
                    return
                }
                saveResourceConfig(newResource, store)
            }
            ActionRegistry.Names.AGENT_SAVE_RESOURCE -> {
                val resourceId = action.payload?.get("resourceId")?.jsonPrimitive?.contentOrNull ?: return
                val resourceToSave = agentState.resources.find { it.id == resourceId } ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "SAVE_RESOURCE: Resource '$resourceId' not found in state.")
                    return
                }
                saveResourceConfig(resourceToSave, store)
            }
            ActionRegistry.Names.AGENT_DELETE_RESOURCE -> {
                val resourceId = action.payload?.get("resourceId")?.jsonPrimitive?.contentOrNull ?: return
                val resourceToDelete = (previousState as? AgentRuntimeState)?.resources?.find { it.id == resourceId } ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "DELETE_RESOURCE: Resource '$resourceId' not found in previous state.")
                    return
                }
                resourceToDelete.path?.let { path ->
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_DELETE, buildJsonObject {
                        put("subpath", path)
                    }))
                }
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_SELECT_RESOURCE, buildJsonObject { put("resourceId", null as String?) }))
            }

            // --- Cognitive Pipeline & Peer Updates (Delegated) ---
            ActionRegistry.Names.AGENT_INITIATE_TURN -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                AgentCognitivePipeline.startCognitiveCycle(agentId, store)
            }
            ActionRegistry.Names.AGENT_STAGE_TURN_CONTEXT -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                AgentCognitivePipeline.evaluateTurnContext(agentId, store)
            }
            ActionRegistry.Names.AGENT_SET_HKG_CONTEXT -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                AgentCognitivePipeline.evaluateFullContext(agentId, store)
            }
            ActionRegistry.Names.AGENT_SET_WORKSPACE_CONTEXT -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                AgentCognitivePipeline.evaluateFullContext(agentId, store)
            }
            ActionRegistry.Names.AGENT_CONTEXT_GATHERING_TIMEOUT -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                val startedAt = action.payload?.get("startedAt")?.jsonPrimitive?.longOrNull ?: return
                val statusInfo = agentState.agentStatuses[agentId] ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "CONTEXT_GATHERING_TIMEOUT: No status entry for agent '$agentId'. Turn may have been cancelled.")
                    return
                }

                // Validate: only proceed if this timeout belongs to the current turn.
                // contextGatheringStartedAt is the canonical indicator — it's set at the
                // start and cleared on turn completion, regardless of direct vs preview mode.
                if (statusInfo.contextGatheringStartedAt != startedAt) {
                    platformDependencies.log(LogLevel.DEBUG, identity.handle,
                        "Stale context gathering timeout for agent '$agentId' (expected startedAt=${statusInfo.contextGatheringStartedAt}, got=$startedAt). Ignoring.")
                    return
                }
                AgentCognitivePipeline.evaluateFullContext(agentId, store, isTimeout = true)
            }
            ActionRegistry.Names.AGENT_EXECUTE_PREVIEWED_TURN -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                val agent = agentState.agents[agentId] ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "EXECUTE_PREVIEWED_TURN: Agent '$agentId' not found.")
                    return
                }
                val statusInfo = agentState.agentStatuses[agentId]
                val previewData = statusInfo?.stagedPreviewData ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "EXECUTE_PREVIEWED_TURN: No staged preview data for agent '$agentId'. Preview may have been discarded.")
                    return
                }

                AgentAvatarLogic.updateAgentAvatars(agentId, store, AgentStatus.PROCESSING)

                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.GATEWAY_GENERATE_CONTENT, buildJsonObject {
                    put("providerId", agent.modelProvider)
                    put("modelName", previewData.agnosticRequest.modelName)
                    put("correlationId", previewData.agnosticRequest.correlationId)
                    put("contents", json.encodeToJsonElement(previewData.agnosticRequest.contents))
                    previewData.agnosticRequest.systemPrompt?.let { put("systemPrompt", it) }
                }))
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_DISCARD_PREVIEW, buildJsonObject { put("agentId", agentId) }))
                store.dispatch("ui.agent", Action(ActionRegistry.Names.CORE_SHOW_DEFAULT_VIEW))
            }
            ActionRegistry.Names.AGENT_DISCARD_PREVIEW -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                val statusInfo = agentState.agentStatuses[agentId]
                if (statusInfo?.status != AgentStatus.PROCESSING) {
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_SET_PROCESSING_STEP, buildJsonObject {
                        put("agentId", agentId); put("step", JsonNull)
                    }))
                }
                store.dispatch("ui.agent", Action(ActionRegistry.Names.CORE_SHOW_DEFAULT_VIEW))
            }
            ActionRegistry.Names.AGENT_CANCEL_TURN -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.GATEWAY_CANCEL_REQUEST, buildJsonObject {
                    put("correlationId", agentId)
                }))
                activeTurnJobs[agentId]?.cancel()
                activeTurnJobs.remove(agentId)
                AgentAvatarLogic.updateAgentAvatars(agentId, store, AgentStatus.IDLE, "Turn cancelled by user.")
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
                            AgentAvatarLogic.updateAgentAvatars(agentId, store)
                        }
                    }
                }
            }
            ActionRegistry.Names.AGENT_CHECK_AUTOMATIC_TRIGGERS -> {
                AgentAutoTriggerLogic.checkAndDispatchTriggers(store, agentState, platformDependencies, identity.handle)
            }
            ActionRegistry.Names.SESSION_SESSION_NAMES_UPDATED -> {
                SovereignHKGResourceLogic.ensureSovereignSessions(store, agentState)
            }

            // --- Identity Registration Response Side Effects ---
            ActionRegistry.Names.CORE_RESPONSE_REGISTER_IDENTITY -> {
                // Reducer already updated the agent's identity — re-save config with full identity
                val uuid = action.payload?.get("uuid")?.jsonPrimitive?.contentOrNull ?: return
                val agent = agentState.agents[uuid] ?: return
                saveAgentConfig(agent, store)
                broadcastAgentNames(agentState, store)
            }
            ActionRegistry.Names.CORE_RESPONSE_UPDATE_IDENTITY -> {
                // Reducer already updated the agent's identity — re-save config with new identity
                val uuid = action.payload?.get("uuid")?.jsonPrimitive?.contentOrNull ?: return
                val agent = agentState.agents[uuid] ?: return
                saveAgentConfig(agent, store)
                broadcastAgentNames(agentState, store)
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

                // Check if this originator is one of our managed agents
                if (originatorId !in agentState.agents.keys) return  // Not our agent — ignore

                platformDependencies.log(
                    LogLevel.INFO, identity.handle,
                    "ACTION_CREATED: Handling '$actionName' for agent '$originatorId' (correlationId=$correlationId)."
                )

                // Apply sandbox rewrite for actions that need it
                val finalPayload = applySandboxRewrite(actionName, actionPayload, originatorId)

                // Inject correlationId into the payload so it survives the round-trip
                // through PrivateData. Receiving features MUST use ignoreUnknownKeys = true.
                val payloadWithCorrelation = buildJsonObject {
                    finalPayload.forEach { (key, value) -> put(key, value) }
                    put("correlationId", correlationId)
                }

                // Track for response attribution
                pendingCommandResponses[correlationId] = PendingCommandResponse(
                    correlationId = correlationId,
                    agentId = originatorId,
                    agentName = originatorName,
                    sessionId = sessionId,
                    actionName = actionName,
                    dispatchedAt = platformDependencies.currentTimeMillis()
                )

                // Dispatch the domain action — originator is "agent" because WE ARE the agent feature
                store.deferredDispatch(identity.handle, Action(name = actionName, payload = payloadWithCorrelation))
            }
        }
    }

    // ========================================================================
    // ACTION_CREATED: Sandbox rewrite (moved from CommandBot)
    // ========================================================================

    /**
     * Applies workspace sandboxing to an agent's action payload.
     * The agent feature owns the "{agentId}/workspace/" path convention.
     *
     * For actions with sandbox rules defined in ExposedActions, the subpath field
     * is prefixed with the agent's workspace path. For other actions, the payload
     * is returned unchanged.
     */
    private fun applySandboxRewrite(actionName: String, payload: JsonObject, agentId: String): JsonObject {
        val rule = ExposedActions.sandboxRules[actionName] ?: return payload
        if (rule.strategy != "AGENT_WORKSPACE") return payload

        val mutablePayload = payload.toMutableMap()

        val originalSubpath = payload["subpath"]?.jsonPrimitive?.contentOrNull ?: ""
        val prefix = rule.subpathPrefixTemplate.replace("{agentId}", agentId)
        val sandboxedSubpath = if (originalSubpath.isNotBlank()) "$prefix/$originalSubpath" else prefix
        mutablePayload["subpath"] = JsonPrimitive(sandboxedSubpath)

        rule.payloadRewrites.forEach { (key, jsonLiteralValue) ->
            mutablePayload[key] = Json.parseToJsonElement(jsonLiteralValue)
        }

        return JsonObject(mutablePayload)
    }

    // ========================================================================
    // Persistence helpers
    // ========================================================================

    private fun saveAgentConfig(agent: AgentInstance, store: Store) {
        // Exclude cognitiveState - it's saved separately in nvram.json
        val agentWithoutNvram = agent.copy(cognitiveState = JsonNull)
        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
            put("subpath", "${agent.identity.uuid}/$agentConfigFILENAME")
            put("content", json.encodeToString(agentWithoutNvram))
        }))
    }

    private fun saveAgentNvram(agent: AgentInstance, store: Store) {
        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
            put("subpath", "${agent.identity.uuid}/$nvramFILENAME")
            put("content", json.encodeToString(agent.cognitiveState))
        }))
    }

    private fun saveResourceConfig(resource: AgentResource, store: Store) {
        resource.path?.let { path ->
            store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
                put("subpath", path)
                put("content", json.encodeToString(resource))
            }))
        }
    }

    private fun broadcastAgentNames(state: AgentRuntimeState, store: Store) {
        val nameMap = state.agents.values.associate { it.identity.handle to it.identity.name }
        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_AGENT_NAMES_UPDATED, buildJsonObject {
            put("names", Json.encodeToJsonElement(nameMap))
        }))
    }

    // ========================================================================
    // Targeted action handling (Phase 3 — migrated from onPrivateData)
    // ========================================================================

    /**
     * Handles all targeted responses delivered to the agent feature.
     * Called from handleSideEffects for any action whose name matches a targeted response type.
     */
    private fun handleTargetedResponse(action: Action, store: Store) {
        val payload = action.payload ?: return

        // Check if this is a response to a pending command (ACTION_CREATED flow)
        val correlationId = payload["correlationId"]?.jsonPrimitive?.contentOrNull
        if (correlationId != null) {
            val pending = pendingCommandResponses.remove(correlationId)
            if (pending != null) {
                postCommandResponse(pending, action, store)
                return
            }
        }

        // Route to appropriate handler
        when (action.name) {
            ActionRegistry.Names.SESSION_RESPONSE_LEDGER,
            ActionRegistry.Names.KNOWLEDGEGRAPH_RESPONSE_CONTEXT,
            ActionRegistry.Names.GATEWAY_RESPONSE_RESPONSE,
            ActionRegistry.Names.GATEWAY_RESPONSE_PREVIEW -> {
                AgentCognitivePipeline.handleTargetedAction(action, store)
            }
            ActionRegistry.Names.FILESYSTEM_RESPONSE_LIST -> {
                // If the listing has a correlationId, it was requested by the cognitive pipeline
                // for workspace context injection — route it to the pipeline.
                val hasCorrelationId = payload["correlationId"]?.jsonPrimitive?.contentOrNull != null
                if (hasCorrelationId) {
                    AgentCognitivePipeline.handleTargetedAction(action, store)
                } else {
                    // Agent-initiated listing — handle locally (post to session)
                    handleFileSystemListResponse(payload, store)
                }
            }
            ActionRegistry.Names.FILESYSTEM_RESPONSE_READ -> handleFileSystemReadResponse(payload, store)
            ActionRegistry.Names.FILESYSTEM_RESPONSE_FILES_CONTENT -> {
                // Currently only received via command responses — would have been handled above
            }
        }
    }

    // ========================================================================
    // ACTION_CREATED: Attributed response posting
    // ========================================================================

    /**
     * Posts an attributed command response to the originating session.
     */
    private fun postCommandResponse(
        pending: PendingCommandResponse,
        action: Action,
        store: Store
    ) {
        val resultContent = formatResponseForSession(pending.actionName, action)

        val message = "Responding to '${pending.actionName}' invoked by '${pending.agentName}':\n$resultContent"
        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
            put("session", pending.sessionId)
            put("senderId", commandBotSenderId)
            put("message", message)
        }))

        platformDependencies.log(
            LogLevel.INFO, identity.handle,
            "Posted attributed response for '${pending.actionName}' (correlationId=${pending.correlationId}) to session '${pending.sessionId}'."
        )
    }

    /**
     * Formats a targeted response action into a human-readable session message.
     */
    private fun formatResponseForSession(actionName: String, action: Action): String {
        val payload = action.payload ?: return "No payload in response."
        return when (action.name) {
            ActionRegistry.Names.FILESYSTEM_RESPONSE_LIST -> {
                val listing = payload["listing"]?.jsonArray
                val subpath = payload["subpath"]?.jsonPrimitive?.contentOrNull ?: "."
                val formatted = Json { prettyPrint = true }.encodeToString(
                    buildJsonObject {
                        put("workspace_path", subpath.ifBlank { "." })
                        put("entries", listing ?: JsonArray(emptyList()))
                    }
                )
                "```json\n$formatted\n```"
            }
            ActionRegistry.Names.FILESYSTEM_RESPONSE_READ -> {
                val subpath = payload["subpath"]?.jsonPrimitive?.contentOrNull ?: ""
                val content = payload["content"]?.jsonPrimitive?.contentOrNull
                if (content != null) {
                    "File: `$subpath`\n```\n$content\n```"
                } else {
                    "File not found: `$subpath`"
                }
            }
            ActionRegistry.Names.FILESYSTEM_RESPONSE_FILES_CONTENT -> {
                val contents = payload["contents"]?.jsonObject
                if (contents != null && contents.isNotEmpty()) {
                    contents.entries.joinToString("\n\n") { (path, content) ->
                        "File: `$path`\n```\n${content.jsonPrimitive.content}\n```"
                    }
                } else {
                    "No file contents returned."
                }
            }
            else -> {
                // Generic fallback: pretty-print the payload
                "```json\n${Json { prettyPrint = true }.encodeToString(payload)}\n```"
            }
        }
    }

    // ========================================================================
    // Existing filesystem response handlers
    // ========================================================================

    private fun handleFileSystemListResponse(payload: JsonObject, store: Store) {
        val path = payload["subpath"]?.jsonPrimitive?.contentOrNull ?: ""
        val listing = payload["listing"]?.jsonArray

        // Normalize for matching
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
                            store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_READ, buildJsonObject {
                                put("subpath", "$agentDir/$agentConfigFILENAME")
                            }))
                            store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_READ, buildJsonObject {
                                put("subpath", "$agentDir/$nvramFILENAME")
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
                        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_READ, buildJsonObject {
                            val fileName = platformDependencies.getFileName(entry.path)
                            val canonicalPath = "resources/$fileName"
                            put("subpath", canonicalPath)
                        }))
                    }
                }
            }

            // ====== Agent workspace directory listings ======
            normalizedPath.contains("/workspace") -> {
                // Extract agent ID: "agent-xyz/workspace" or "agent-xyz/workspace/subdir"
                val agentId = normalizedPath.substringBefore("/workspace")
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
                        "Agent '${agent.identity.uuid}' has no session for workspace list response.")
                    return
                }

                // Format as JSON code block for machine readability
                val workspaceRelativePath = normalizedPath.substringAfter("$agentId/workspace")
                    .removePrefix("/")
                    .ifBlank { "." }

                val listingJson = listing?.map { element ->
                    val entry = json.decodeFromJsonElement<FileEntry>(element)
                    // Strip the agent+workspace prefix so the agent sees workspace-relative paths
                    val relativePath = entry.path.replace("\\", "/")
                        .removePrefix("$agentId/workspace/")
                        .removePrefix("$agentId/workspace")
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
                    put("session", targetSessionId)
                    put("senderId", commandBotSenderId)
                    put("message", message)
                }))
            }
        }
    }

    private fun handleFileSystemReadResponse(payload: JsonObject, store: Store) {
        // Normalize separators to '/' to ensure cross-platform matching logic
        val subpath = (payload["subpath"]?.jsonPrimitive?.contentOrNull ?: "").replace("\\", "/")
        val content = payload["content"]?.jsonPrimitive?.contentOrNull ?: run {
            platformDependencies.log(LogLevel.DEBUG, identity.handle, "handleFileSystemReadResponse: Missing content in read response for subpath='$subpath'.")
            return
        }

        when {
            // ====== Agent workspace file responses ======
            subpath.contains(workspaceSubpathMarker) -> {
                val agentId = subpath.substringBefore(workspaceSubpathMarker)
                val relativeSubpath = subpath.substringAfter(workspaceSubpathMarker)
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
                        "Agent '${agent.identity.uuid}' has no session for workspace read response.")
                    return
                }

                val message = if (content != null) {
                    "```text\n[WORKSPACE FILE: $relativeSubpath]\n$content\n```"
                } else {
                    "```text\n[WORKSPACE ERROR] File not found: $relativeSubpath\n```"
                }

                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
                    put("session", targetSessionId)
                    put("senderId", commandBotSenderId)
                    put("message", message)
                }))
            }
            // Shared Resource files live under the "resources/" directory
            subpath.startsWith("resources/") -> {
                try {
                    val resource = json.decodeFromString<AgentResource>(content)
                    // Ensure the in-memory resource has a normalized path
                    val resWithPath = resource.copy(path = subpath)
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_RESOURCE_LOADED, json.encodeToJsonElement(resWithPath) as JsonObject))
                } catch (e: Exception) {
                    platformDependencies.log(LogLevel.ERROR, identity.handle, "Failed to parse resource: $subpath. Error: ${e.message}")
                }
            }
            // Agent config files
            subpath.endsWith("/$agentConfigFILENAME") -> {
                try {
                    val agent = json.decodeFromString<AgentInstance>(content)
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_AGENT_LOADED, json.encodeToJsonElement(agent) as JsonObject))
                } catch (e: Exception) {
                    platformDependencies.log(LogLevel.ERROR, identity.handle, "Failed to parse agent config from file: $subpath. Error: ${e.message}")
                } finally {
                    agentLoadCount--
                    if (agentLoadCount <= 0) {
                        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_AGENTS_LOADED))
                    }
                }
            }
            // NVRAM files
            subpath.endsWith("/$nvramFILENAME") -> {
                try {
                    val nvramState = json.decodeFromString<JsonElement>(content)
                    // Extract agent ID from path (e.g., "agent-xyz/nvram.json" -> "agent-xyz")
                    val agentId = subpath.substringBeforeLast("/")

                    // Dispatch to merge NVRAM into the agent's state
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_NVRAM_LOADED, buildJsonObject {
                        put("agentId", agentId)
                        put("state", nvramState)
                    }))
                } catch (e: Exception) {
                    // NVRAM file missing or corrupted is non-fatal - agent will use initial state
                    platformDependencies.log(LogLevel.WARN, identity.handle, "Failed to load NVRAM from $subpath (agent will use initial state): ${e.message}")
                }
            }
            // Unknown file — log and ignore, don't corrupt agent load tracking
            else -> {
                platformDependencies.log(LogLevel.WARN, identity.handle, "Received unexpected file read response for: $subpath. Ignoring.")
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
            IconButton(onClick = { store.dispatch("ui.ribbon", Action(ActionRegistry.Names.CORE_SET_ACTIVE_VIEW, buildJsonObject { put("key", viewKey) })) }) {
                Icon(Icons.Default.Bolt, "Agent Manager", tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        @Composable
        override fun PartialView(store: Store, partId: String, context: Any?) {
            if (partId != "agent.avatar") return
            val agentId = context as? String ?: run {
                platformDependencies.log(LogLevel.DEBUG, identity.handle, "PartialView: Invalid context type for agent.avatar.")
                return
            }
            val appState by store.state.collectAsState()
            val state = appState.featureStates[identity.handle] as? AgentRuntimeState ?: return
            val agent = state.agents[agentId] ?: return
            AgentAvatarCard(agent = agent, store = store, platformDependencies = platformDependencies)
        }
    }
}

/**
 * Determines the session where workspace operation responses should be posted.
 * For sovereign agents: their private session (isolated cognition).
 * For vanilla agents: their first subscribed session (where they participate).
 */
private fun getAgentResponseSessionId(agent: AgentInstance): String? {
    return agent.privateSessionId ?: agent.subscribedSessionIds.firstOrNull()
}