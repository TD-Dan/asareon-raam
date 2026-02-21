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
 * applies workspace sandboxing, dispatches domain actions, and routes results
 * back to the session via the DELIVER_TO_SESSION / ACTION_RESULT protocol.
 */
class AgentRuntimeFeature(
    private val platformDependencies: PlatformDependencies,
    private val coroutineScope: CoroutineScope
) : Feature {
    override val identity: Identity = Identity(uuid = null, handle = "agent", localHandle = "agent", name="Agent Runtime")

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val agentConfigFILENAME = "agent.json"
    private val nvramFILENAME = "nvram.json"

    private val workspacePathMarker = "/workspace/"

    private val commandBotSenderId = "commandbot"

    private val activeTurnJobs = mutableMapOf<String, Job>()
    private val avatarUpdateJobs = mutableMapOf<String, Job>()
    private var agentLoadCount = 0

    companion object {
        /** TTL for pending command entries: 5 minutes. */
        const val PENDING_COMMAND_TTL_MS = 5 * 60 * 1000L
    }

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
                // Inject built-in resources FIRST (ensures they're always available)
                AgentDefaults.builtInResources.forEach { resource ->
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
                if (store.state.value.identityRegistry.values.any { it.parentHandle == "session" }) {
                    SovereignHKGResourceLogic.ensureSovereignSessions(store, agentState)
                }
            }
            ActionRegistry.Names.AGENT_VALIDATE_SOVEREIGN_STATE -> {
                SovereignHKGResourceLogic.ensureSovereignSessions(store, agentState)
            }
            ActionRegistry.Names.AGENT_AGENT_LOADED -> {
                val agent = action.payload?.let { json.decodeFromJsonElement<AgentInstance>(it) } ?: return
                val uuid = agent.identity.uuid ?: return
                // NOTE: Avatar posting deferred to SESSION_SESSION_FEATURE_READY.
                // At startup, agent files may load before session files. The session feature
                // broadcasts SESSION_FEATURE_READY once sessions are in its map, at which point
                // we reconcile avatars for all active agents.
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
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_DELETE_DIRECTORY, buildJsonObject { put("path", agentId) }))
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_CONFIRM_DELETE, buildJsonObject { put("agentId", agentId) }))
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_AGENT_DELETED, buildJsonObject { put("agentId", agentId) }))
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
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_DELETE_FILE, buildJsonObject {
                        put("path", path)
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
            ActionRegistry.Names.SESSION_SESSION_FEATURE_READY -> {
                // Sessions are now in the sessions map and resolvable. Post avatar cards
                // for all active agents. This is idempotent — updateAgentAvatars deletes
                // old cards before posting new ones, so duplicate signals are harmless.
                agentState.agents.forEach { (agentId, agent) ->
                    if (agent.isAgentActive) {
                        AgentAvatarLogic.updateAgentAvatars(agentId, store, AgentStatus.IDLE)
                    }
                }
            }

            // --- Identity Registration Response Side Effects ---
            ActionRegistry.Names.CORE_RETURN_REGISTER_IDENTITY -> {
                // Reducer already updated the agent's identity — re-save config with full identity
                val uuid = action.payload?.get("uuid")?.jsonPrimitive?.contentOrNull ?: return
                val agent = agentState.agents[uuid] ?: return
                saveAgentConfig(agent, store)
            }
            ActionRegistry.Names.CORE_RETURN_UPDATE_IDENTITY -> {
                // Reducer already updated the agent's identity — re-save config with new identity
                val uuid = action.payload?.get("uuid")?.jsonPrimitive?.contentOrNull ?: return
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
                // Agents are keyed by UUID internally, so we must resolve handles.
                val agent = agentState.agents[originatorId]
                    ?: agentState.agents.values.find { it.identity.handle == originatorId }
                if (agent == null) return  // Not our agent — ignore
                val agentUuid = agent.identity.uuid ?: return

                platformDependencies.log(
                    LogLevel.INFO, identity.handle,
                    "ACTION_CREATED: Handling '$actionName' for agent '$originatorId' (uuid=$agentUuid, correlationId=$correlationId)."
                )

                // Apply sandbox rewrite for actions that need it.
                // Uses UUID because workspace paths are "{uuid}/workspace/".
                val finalPayload = applySandboxRewrite(actionName, actionPayload, agentUuid)

                // Inject correlationId into the payload so the handling feature can thread it
                // to ACTION_RESULT and RETURN_* responses.
                // Guard: don't overwrite a correlationId the agent explicitly included.
                val enrichedPayload = if (finalPayload["correlationId"] == null) {
                    JsonObject(finalPayload + ("correlationId" to JsonPrimitive(correlationId)))
                } else {
                    finalPayload
                }

                // Dispatch the domain action attributed to the agent.
                store.deferredDispatch(identity.handle, Action(name = actionName, payload = enrichedPayload))

                // Track the pending command in state so we can route RETURN_* data
                // to the session via DELIVER_TO_SESSION.
                store.deferredDispatch(identity.handle, Action(
                    ActionRegistry.Names.AGENT_REGISTER_PENDING_COMMAND,
                    buildJsonObject {
                        put("correlationId", correlationId)
                        put("agentId", agentUuid)
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

    // ========================================================================
    // ACTION_CREATED: Sandbox rewrite (moved from CommandBot)
    // ========================================================================

    /**
     * Applies workspace sandboxing to an agent's action payload.
     * The agent feature owns the "{agentId}/workspace/" path convention.
     *
     * For actions with sandbox rules defined in ActionRegistry, the path field
     * is prefixed with the agent's workspace path. For other actions, the payload
     * is returned unchanged.
     */
    private fun applySandboxRewrite(actionName: String, payload: JsonObject, agentId: String): JsonObject {
        val rule = ActionRegistry.agentSandboxRules[actionName] ?: return payload
        if (rule.strategy != "AGENT_WORKSPACE") return payload

        val mutablePayload = payload.toMutableMap()

        val originalPath = payload["path"]?.jsonPrimitive?.contentOrNull ?: ""
        val prefix = rule.pathPrefixTemplate.replace("{agentId}", agentId)
        val sandboxedPath = if (originalPath.isNotBlank()) "$prefix/$originalPath" else prefix
        mutablePayload["path"] = JsonPrimitive(sandboxedPath)

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
        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_WRITE, buildJsonObject {
            put("path", "${agent.identity.uuid}/$agentConfigFILENAME")
            put("content", json.encodeToString(agentWithoutNvram))
        }))
    }

    private fun saveAgentNvram(agent: AgentInstance, store: Store) {
        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_WRITE, buildJsonObject {
            put("path", "${agent.identity.uuid}/$nvramFILENAME")
            put("content", json.encodeToString(agent.cognitiveState))
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
    // Targeted action handling (Phase 3 — migrated from onPrivateData)
    // ========================================================================

    /**
     * Handles all targeted responses delivered to the agent feature.
     * Called from handleSideEffects for any action whose name matches a targeted response type.
     *
     * Command-originated responses (those with a correlationId matching a pending command)
     * are routed to the session via DELIVER_TO_SESSION. All other targeted responses
     * are routed to their existing handlers (cognitive pipeline, workspace reads, etc.).
     */
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
            ActionRegistry.Names.FILESYSTEM_RETURN_READ -> handleFileSystemReadResponse(payload, store)
            ActionRegistry.Names.FILESYSTEM_RETURN_FILES_CONTENT -> {
                // Currently only received via command responses — would have been handled above
            }
        }
    }

    // ========================================================================
    // ACTION_CREATED: DELIVER_TO_SESSION routing
    // ========================================================================

    /**
     * Routes a command-originated targeted response to the originating session
     * via the DELIVER_TO_SESSION protocol.
     *
     * This replaces the old postCommandResponse/formatResponseForSession approach:
     * instead of posting directly via SESSION_POST, we dispatch to CommandBot's
     * DELIVER_TO_SESSION channel, which provides consistent formatting and attribution.
     */
    private fun routeCommandResponseToSession(
        pending: AgentPendingCommand,
        action: Action,
        store: Store
    ) {
        // Format the response data. Returns null if the response indicates an error
        // or contains no useful data — in that case, ACTION_RESULT already provides
        // the error status line, so we skip DELIVER_TO_SESSION to avoid duplicates.
        val formatted = formatResponseForSession(action)

        if (formatted != null) {
            store.deferredDispatch(identity.handle, Action(
                ActionRegistry.Names.COMMANDBOT_DELIVER_TO_SESSION,
                buildJsonObject {
                    put("correlationId", pending.correlationId)
                    put("sessionId", pending.sessionId)
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

        // Always clear the pending command — whether we delivered data or not.
        store.deferredDispatch(identity.handle, Action(
            ActionRegistry.Names.AGENT_CLEAR_PENDING_COMMAND,
            buildJsonObject { put("correlationId", pending.correlationId) }
        ))
    }

    /**
     * Formats a targeted response action into a human-readable session message.
     * Returns null if the response indicates an error or contains no useful data —
     * the caller should skip DELIVER_TO_SESSION in that case, since ACTION_RESULT
     * already provides the error feedback to the session.
     */
    private fun formatResponseForSession(action: Action): String? {
        val payload = action.payload ?: return null
        return when (action.name) {
            ActionRegistry.Names.FILESYSTEM_RETURN_LIST -> {
                val listing = payload["listing"]?.jsonArray
                // Empty listing on error — ACTION_RESULT covers it
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
                // Null content = file not found / read error — ACTION_RESULT covers it
                if (content == null) return null
                val ext = path.substringAfterLast('.', "")
                "```$ext \"$path\"\n$content\n```"
            }
            ActionRegistry.Names.FILESYSTEM_RETURN_FILES_CONTENT -> {
                val contents = payload["contents"]?.jsonObject
                // No contents = error — ACTION_RESULT covers it
                if (contents == null || contents.isEmpty()) return null
                contents.entries.joinToString("\n\n") { (path, content) ->
                    val ext = path.substringAfterLast('.', "")
                    "```$ext \"$path\"\n${content.jsonPrimitive.content}\n```"
                }
            }
            else -> {
                // Generic fallback: pretty-print the payload
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
                            store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_READ, buildJsonObject {
                                put("path", "$agentDir/$agentConfigFILENAME")
                            }))
                            store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_READ, buildJsonObject {
                                put("path", "$agentDir/$nvramFILENAME")
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
        val path = (payload["path"]?.jsonPrimitive?.contentOrNull ?: "").replace("\\", "/")
        val content = payload["content"]?.jsonPrimitive?.contentOrNull ?: run {
            platformDependencies.log(LogLevel.DEBUG, identity.handle, "handleFileSystemReadResponse: Missing content in read response for path='$path'.")
            return
        }

        when {
            // ====== Agent workspace file responses ======
            path.contains(workspacePathMarker) -> {
                val agentId = path.substringBefore(workspacePathMarker)
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
                        "Agent '${agent.identity.uuid}' has no session for workspace read response.")
                    return
                }

                val message = if (content != null) {
                    "```text\n[WORKSPACE FILE: $relativePath]\n$content\n```"
                } else {
                    "```text\n[WORKSPACE ERROR] File not found: $relativePath\n```"
                }

                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
                    put("session", targetSessionId)
                    put("senderId", commandBotSenderId)
                    put("message", message)
                }))
            }
            // Shared Resource files live under the "resources/" directory
            path.startsWith("resources/") -> {
                try {
                    val resource = json.decodeFromString<AgentResource>(content)
                    // Ensure the in-memory resource has a normalized path
                    val resWithPath = resource.copy(path = path)
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_RESOURCE_LOADED, json.encodeToJsonElement(resWithPath) as JsonObject))
                } catch (e: Exception) {
                    platformDependencies.log(LogLevel.ERROR, identity.handle, "Failed to parse resource: $path. Error: ${e.message}")
                }
            }
            // Agent config files
            path.endsWith("/$agentConfigFILENAME") -> {
                try {
                    val agent = json.decodeFromString<AgentInstance>(content)
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
                    // Extract agent ID from path (e.g., "agent-xyz/nvram.json" -> "agent-xyz")
                    val agentId = path.substringBeforeLast("/")

                    // Dispatch to merge NVRAM into the agent's state
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_NVRAM_LOADED, buildJsonObject {
                        put("agentId", agentId)
                        put("state", nvramState)
                    }))
                } catch (e: Exception) {
                    // NVRAM file missing or corrupted is non-fatal - agent will use initial state
                    platformDependencies.log(LogLevel.WARN, identity.handle, "Failed to load NVRAM from $path (agent will use initial state): ${e.message}")
                }
            }
            // Unknown file — log and ignore, don't corrupt agent load tracking
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
            // Context may be a UUID (direct call) or a handle like "agent.flash"
            // (from SessionView which passes entry.senderId). Try UUID first, then
            // fall back to searching by identity handle.
            val agent = state.agents[agentId]
                ?: state.agents.values.find { it.identity.handle == agentId }
            if (agent == null) {
                platformDependencies.log(LogLevel.ERROR, identity.handle,
                    "PartialView: Could not resolve agent for context '$agentId'. " +
                            "Known UUIDs: ${state.agents.keys}, known handles: ${state.agents.values.map { it.identity.handle }}")
                return
            }
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