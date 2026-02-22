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
 * [PHASE 1] All agent IDs are typed via [IdentityUUID]; session IDs via [IdentityHandle].
 * JSON extraction wraps raw strings at the boundary; internal logic uses typed values.
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

    private val activeTurnJobs = mutableMapOf<IdentityUUID, Job>()
    private val avatarUpdateJobs = mutableMapOf<IdentityUUID, Job>()
    private var agentLoadCount = 0

    companion object {
        /** TTL for pending command entries: 5 minutes. */
        const val PENDING_COMMAND_TTL_MS = 5 * 60 * 1000L
    }

    // ---- Phase 1 boundary helper ----
    private fun JsonObject.agentUUID(field: String = "agentId"): IdentityUUID? =
        this[field]?.jsonPrimitive?.contentOrNull?.let { IdentityUUID(it) }

    override fun init(store: Store) {
        // [PHASE 2] Register all built-in cognitive strategies before any agents boot.
        // This must happen before the heartbeat or any agent loading, so that
        // CognitiveStrategyRegistry.get() and migrateStrategyId() resolve correctly.
        CognitiveStrategyRegistry.register(
            app.auf.feature.agent.strategies.VanillaStrategy,
            legacyId = "vanilla_v1"
        )
        CognitiveStrategyRegistry.register(
            app.auf.feature.agent.strategies.SovereignStrategy,
            legacyId = "sovereign_v1"
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
                        put("uuid", agentToSave.identityUUID.uuid)
                        put("name", agentToSave.identity.name)
                    }
                ))
            }
            ActionRegistry.Names.AGENT_CLONE -> {
                val agentId = action.payload?.agentUUID() ?: return
                val agentToClone = agentState.agents[agentId] ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "AGENT_CLONE: Source agent '$agentId' not found in state.")
                    return
                }
                val createPayload = buildJsonObject {
                    put("name", "${agentToClone.identity.name} (Copy)")
                    agentToClone.knowledgeGraphId?.let { put("knowledgeGraphId", it) }
                    put("modelProvider", agentToClone.modelProvider)
                    put("modelName", agentToClone.modelName)
                    put("subscribedSessionIds", buildJsonArray { agentToClone.subscribedSessionIds.forEach { add(it.handle) } })
                    put("automaticMode", agentToClone.automaticMode)
                    put("autoWaitTimeSeconds", agentToClone.autoWaitTimeSeconds)
                    put("autoMaxWaitTimeSeconds", agentToClone.autoMaxWaitTimeSeconds)
                }
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_CREATE, createPayload))
            }
            ActionRegistry.Names.AGENT_TOGGLE_AUTOMATIC_MODE, ActionRegistry.Names.AGENT_TOGGLE_ACTIVE -> {
                val agentId = action.payload?.agentUUID() ?: return
                val agent = agentState.agents[agentId] ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "AGENT_TOGGLE: Agent '$agentId' not found in state.")
                    return
                }
                saveAgentConfig(agent, store)
                AgentAvatarLogic.touchAgentAvatarCard(agent, agentState, store)
            }
            ActionRegistry.Names.AGENT_UPDATE_CONFIG -> {
                val agentId = action.payload?.agentUUID() ?: return
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
                    val currentHandle = newAgent.identityHandle
                    if (currentHandle.handle.isNotBlank()) {
                        store.deferredDispatch(identity.handle, Action(
                            ActionRegistry.Names.CORE_UPDATE_IDENTITY,
                            buildJsonObject {
                                put("handle", currentHandle.handle)
                                put("newName", newAgent.identity.name)
                            }
                        ))
                    }
                }
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
                val agentId = action.payload?.agentUUID() ?: return
                val agent = agentState.agents[agentId] ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "UPDATE_NVRAM: Agent '$agentId' not found. Cannot update NVRAM.")
                    return
                }
                saveAgentNvram(agent, store)
            }
            ActionRegistry.Names.AGENT_DELETE -> {
                val agentId = action.payload?.agentUUID() ?: return
                agentState.agentAvatarCardIds[agentId]?.forEach { (sessionId, messageId) ->
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_DELETE_MESSAGE, buildJsonObject {
                        put("session", sessionId.handle)
                        put("messageId", messageId)
                    }))
                }
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_DELETE_DIRECTORY, buildJsonObject { put("path", agentId.uuid) }))
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_CONFIRM_DELETE, buildJsonObject { put("agentId", agentId.uuid) }))
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_AGENT_DELETED, buildJsonObject { put("agentId", agentId.uuid) }))
                // Unregister agent identity (cascades any sub-identities)
                val agentToDelete = (previousState as? AgentRuntimeState)?.agents?.get(agentId)
                val deleteHandle = agentToDelete?.identityHandle
                if (deleteHandle != null && deleteHandle.handle.isNotBlank()) {
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.CORE_UNREGISTER_IDENTITY,
                        buildJsonObject { put("handle", deleteHandle.handle) }
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
                    platformDependencies.log(LogLevel.WARN, identity.handle, "DELETE_RESOURCE: Resource '$resourceId' not found in previousState.")
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
                val agentId = action.payload?.agentUUID() ?: return
                AgentCognitivePipeline.startCognitiveCycle(agentId, store)
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
                val agentId = action.payload?.agentUUID() ?: return
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
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_DISCARD_PREVIEW, buildJsonObject { put("agentId", agentId.uuid) }))
                store.dispatch("ui.agent", Action(ActionRegistry.Names.CORE_SHOW_DEFAULT_VIEW))
            }
            ActionRegistry.Names.AGENT_DISCARD_PREVIEW -> {
                val agentId = action.payload?.agentUUID() ?: return
                val statusInfo = agentState.agentStatuses[agentId]
                if (statusInfo?.status != AgentStatus.PROCESSING) {
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.AGENT_SET_PROCESSING_STEP, buildJsonObject {
                        put("agentId", agentId.uuid); put("step", JsonNull)
                    }))
                }
                store.dispatch("ui.agent", Action(ActionRegistry.Names.CORE_SHOW_DEFAULT_VIEW))
            }
            ActionRegistry.Names.AGENT_CANCEL_TURN -> {
                val agentId = action.payload?.agentUUID() ?: return
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.GATEWAY_CANCEL_REQUEST, buildJsonObject {
                    put("correlationId", agentId.uuid)
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
                agentState.agents.forEach { (agentId, agent) ->
                    if (agent.isAgentActive) {
                        AgentAvatarLogic.updateAgentAvatars(agentId, store, AgentStatus.IDLE)
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
                val finalPayload = applySandboxRewrite(actionName, actionPayload, agentUuid)

                // Inject correlationId into the payload
                val enrichedPayload = if (finalPayload["correlationId"] == null) {
                    JsonObject(finalPayload + ("correlationId" to JsonPrimitive(correlationId)))
                } else {
                    finalPayload
                }

                // Dispatch the domain action attributed to the agent.
                store.deferredDispatch(identity.handle, Action(name = actionName, payload = enrichedPayload))

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
            store.deferredDispatch(identity.handle, Action(
                ActionRegistry.Names.COMMANDBOT_DELIVER_TO_SESSION,
                buildJsonObject {
                    put("correlationId", pending.correlationId)
                    put("sessionId", pending.sessionId.handle)
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
                    put("session", targetSessionId.handle)
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

                val message = if (content != null) {
                    "```text\n[WORKSPACE FILE: $relativePath]\n$content\n```"
                } else {
                    "```text\n[WORKSPACE ERROR] File not found: $relativePath\n```"
                }

                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
                    put("session", targetSessionId.handle)
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
                    // [PHASE 2] Migrate legacy strategy IDs (e.g. "vanilla_v1" → "agent.strategy.vanilla")
                    var agent = rawAgent.copy(
                        cognitiveStrategyId = CognitiveStrategyRegistry.migrateStrategyId(rawAgent.cognitiveStrategyId.handle)
                    )
                    // [PHASE 3] Migrate old "privateSessionId" → outputSessionId
                    if (agent.outputSessionId == null) {
                        val rawJson = json.parseToJsonElement(content).jsonObject
                        rawJson["privateSessionId"]?.jsonPrimitive?.contentOrNull?.let { oldValue ->
                            agent = agent.copy(outputSessionId = IdentityHandle(oldValue))
                        }
                    }
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
            val agent = state.agents[IdentityUUID(agentId)]
                ?: state.agents.values.find { it.identityHandle.handle == agentId }
            if (agent == null) {
                platformDependencies.log(LogLevel.ERROR, identity.handle,
                    "PartialView: Could not resolve agent for context '$agentId'. " +
                            "Known UUIDs: ${state.agents.keys}, known handles: ${state.agents.values.map { it.identityHandle }}")
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
 *
 * [PHASE 1] Returns typed [IdentityHandle].
 */
private fun getAgentResponseSessionId(agent: AgentInstance): IdentityHandle? {
    return agent.outputSessionId ?: agent.subscribedSessionIds.firstOrNull()
}