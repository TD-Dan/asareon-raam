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
import app.auf.core.generated.ActionNames
import app.auf.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/**
 * ## Slice 5: The Executor (Simplified)
 * The Feature class is now a lean orchestrator.
 * It delegates:
 * - CRUD -> AgentCrudLogic
 * - UI -> AgentAvatarLogic
 * - Cognition -> AgentCognitivePipeline
 * - Auto-Triggers -> AgentAutoTriggerLogic
 */
class AgentRuntimeFeature(
    private val platformDependencies: PlatformDependencies,
    private val coroutineScope: CoroutineScope
) : Feature {
    override val name: String = "agent"

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val agentConfigFILENAME = "agent.json"
    private val activeTurnJobs = mutableMapOf<String, Job>()
    private val redundantHeaderRegex = Regex("""^.+? \([^)]+\) @ \d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z:\s*""")
    private var agentLoadCount = 0

    override fun init(store: Store) {
        coroutineScope.launch {
            while (true) {
                delay(1000)
                // Delegate auto-trigger check to the Watcher
                val state = store.state.value.featureStates[name] as? AgentRuntimeState
                if (state != null) {
                    AgentAutoTriggerLogic.checkAndDispatchTriggers(store, state, platformDependencies, name)
                }
            }
        }
    }

    override fun reducer(state: FeatureState?, action: Action): FeatureState? {
        val currentFeatureState = state as? AgentRuntimeState ?: AgentRuntimeState()

        // 1. CRUD Logic
        val crudState = AgentCrudLogic.reduce(currentFeatureState, action, platformDependencies)
        if (crudState !== currentFeatureState) {
            return crudState
        }

        // 2. Runtime State Updates
        when (action.name) {
            ActionNames.AGENT_INTERNAL_SET_STATUS -> return handleSetStatus(action, currentFeatureState)
            ActionNames.AGENT_INTERNAL_SET_PROCESSING_STEP -> {
                val payload = action.payload ?: return currentFeatureState
                val agentId = payload["agentId"]?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
                val currentStatus = currentFeatureState.agentStatuses[agentId] ?: AgentStatusInfo()
                val step = payload["step"]?.jsonPrimitive?.contentOrNull
                val updatedStatus = currentStatus.copy(processingStep = step)
                return currentFeatureState.copy(agentStatuses = currentFeatureState.agentStatuses + (agentId to updatedStatus))
            }
            ActionNames.AGENT_INTERNAL_STAGE_TURN_CONTEXT -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<StageTurnContextPayload>(it) } ?: return currentFeatureState
                val currentStatus = currentFeatureState.agentStatuses[payload.agentId] ?: AgentStatusInfo()
                val updatedStatus = currentStatus.copy(stagedTurnContext = payload.messages)
                return currentFeatureState.copy(agentStatuses = currentFeatureState.agentStatuses + (payload.agentId to updatedStatus))
            }
            ActionNames.AGENT_INTERNAL_SET_HKG_CONTEXT -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<SetHkgContextPayload>(it) } ?: return currentFeatureState
                val currentStatus = currentFeatureState.agentStatuses[payload.agentId] ?: AgentStatusInfo()
                val updatedStatus = currentStatus.copy(transientHkgContext = payload.context)
                return currentFeatureState.copy(agentStatuses = currentFeatureState.agentStatuses + (payload.agentId to updatedStatus))
            }
            ActionNames.AGENT_INITIATE_TURN -> {
                // Reducer only handles the state transition for 'INITIATE_TURN' (setting mode).
                val payload = action.payload?.let { json.decodeFromJsonElement<InitiateTurnPayload>(it) } ?: return currentFeatureState
                val agentId = payload.agentId
                val currentStatus = currentFeatureState.agentStatuses[agentId] ?: AgentStatusInfo()

                if (currentStatus.status == AgentStatus.PROCESSING) return currentFeatureState

                val updatedStatus = currentStatus.copy(
                    processingFrontierMessageId = currentStatus.lastSeenMessageId,
                    turnMode = if (payload.preview) TurnMode.PREVIEW else TurnMode.DIRECT,
                    stagedTurnContext = null,
                    transientHkgContext = null
                )
                return currentFeatureState.copy(agentStatuses = currentFeatureState.agentStatuses + (agentId to updatedStatus))
            }
            ActionNames.AGENT_DISCARD_PREVIEW -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
                val currentStatus = currentFeatureState.agentStatuses[agentId] ?: AgentStatusInfo()
                val updatedStatus = currentStatus.copy(stagedPreviewData = null, processingStep = null)
                return currentFeatureState.copy(
                    agentStatuses = currentFeatureState.agentStatuses + (agentId to updatedStatus),
                    viewingContextForAgentId = null
                )
            }
            ActionNames.AGENT_INTERNAL_SET_PREVIEW_DATA -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<SetPreviewDataPayload>(it) } ?: return currentFeatureState
                val agentId = payload.agentId
                val currentStatus = currentFeatureState.agentStatuses[agentId] ?: AgentStatusInfo()
                val previewData = StagedPreviewData(payload.agnosticRequest, payload.rawRequestJson)
                val updatedStatus = currentStatus.copy(stagedPreviewData = previewData)
                return currentFeatureState.copy(
                    agentStatuses = currentFeatureState.agentStatuses + (agentId to updatedStatus),
                    viewingContextForAgentId = agentId
                )
            }
            ActionNames.SESSION_PUBLISH_MESSAGE_POSTED -> return handleMessagePosted(action, currentFeatureState)
            ActionNames.SESSION_PUBLISH_MESSAGE_DELETED -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<MessageDeletedPayload>(it) } ?: return currentFeatureState
                val agentId = currentFeatureState.agentAvatarCardIds.entries.find { it.value.messageId == payload.messageId }?.key ?: return currentFeatureState
                return currentFeatureState.copy(agentAvatarCardIds = currentFeatureState.agentAvatarCardIds - agentId)
            }
            ActionNames.SESSION_PUBLISH_SESSION_DELETED -> {
                val deletedSessionId = action.payload?.get("sessionId")?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
                val agentsToUpdate = currentFeatureState.agents.values
                    .filter { it.subscribedSessionIds.contains(deletedSessionId) || it.privateSessionId == deletedSessionId }
                if (agentsToUpdate.isEmpty()) return currentFeatureState

                val newAgents = currentFeatureState.agents.mapValues { (_, agent) ->
                    if (agentsToUpdate.any { it.id == agent.id }) {
                        agent.copy(
                            subscribedSessionIds = agent.subscribedSessionIds - deletedSessionId,
                            privateSessionId = if (agent.privateSessionId == deletedSessionId) null else agent.privateSessionId
                        )
                    } else {
                        agent
                    }
                }
                return currentFeatureState.copy(agents = newAgents, agentsToPersist = agentsToUpdate.map { it.id }.toSet())
            }
            ActionNames.GATEWAY_PUBLISH_AVAILABLE_MODELS_UPDATED -> {
                val decodedModels: Map<String, List<String>>? = try { action.payload?.let { json.decodeFromJsonElement(it) } } catch (e: Exception) { null }
                return currentFeatureState.copy(availableModels = decodedModels ?: emptyMap())
            }
            ActionNames.SESSION_PUBLISH_SESSION_NAMES_UPDATED -> {
                val decoded = try { action.payload?.let { json.decodeFromJsonElement<SessionNamesPayload>(it) } } catch(e: Exception) { null }
                return if (decoded != null) currentFeatureState.copy(sessionNames = decoded.names) else currentFeatureState
            }
            ActionNames.KNOWLEDGEGRAPH_PUBLISH_AVAILABLE_PERSONAS_UPDATED -> {
                val decoded = try { action.payload?.let { json.decodeFromJsonElement<GraphNamesPayload>(it) } } catch(e: Exception) { null }
                return if (decoded != null) currentFeatureState.copy(knowledgeGraphNames = decoded.names) else currentFeatureState
            }
            ActionNames.KNOWLEDGEGRAPH_PUBLISH_RESERVATIONS_UPDATED -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<ReservedIdsPayload>(it) }
                return if (payload != null) currentFeatureState.copy(hkgReservedIds = payload.reservedIds) else currentFeatureState
            }
            ActionNames.CORE_PUBLISH_IDENTITIES_UPDATED -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<IdentitiesUpdatedPayload>(it) }
                return if (payload != null) currentFeatureState.copy(userIdentities = payload.identities) else currentFeatureState
            }
            else -> return currentFeatureState
        }
    }

    private fun handleSetStatus(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState {
        val payload = action.payload ?: return currentFeatureState
        val agentId = payload["agentId"]?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
        val currentStatus = currentFeatureState.agentStatuses[agentId] ?: AgentStatusInfo()

        val newStatusString = payload["status"]?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
        val newStatus = try { AgentStatus.valueOf(newStatusString) } catch (e: Exception) { return currentFeatureState }
        val newErrorMessage = if (newStatus == AgentStatus.ERROR) payload["error"]?.jsonPrimitive?.contentOrNull else null

        val clearTimers = currentStatus.status == AgentStatus.WAITING && newStatus != AgentStatus.WAITING
        val isStartingProcessing = newStatus == AgentStatus.PROCESSING && currentStatus.status != AgentStatus.PROCESSING
        val isStoppingProcessing = newStatus != AgentStatus.PROCESSING && currentStatus.status == AgentStatus.PROCESSING
        val shouldClearContext = isStoppingProcessing || newStatus == AgentStatus.IDLE || newStatus == AgentStatus.ERROR

        val updatedStatus = currentStatus.copy(
            status = newStatus,
            errorMessage = newErrorMessage,
            waitingSinceTimestamp = if (clearTimers) null else currentStatus.waitingSinceTimestamp,
            lastMessageReceivedTimestamp = if (clearTimers) null else currentStatus.lastMessageReceivedTimestamp,
            processingSinceTimestamp = if (isStartingProcessing) platformDependencies.getSystemTimeMillis() else if (isStoppingProcessing) null else currentStatus.processingSinceTimestamp,
            processingFrontierMessageId = if (isStoppingProcessing) null else currentStatus.processingFrontierMessageId,
            processingStep = if (isStoppingProcessing) null else currentStatus.processingStep,
            stagedTurnContext = if(shouldClearContext) null else currentStatus.stagedTurnContext,
            transientHkgContext = if (shouldClearContext) null else currentStatus.transientHkgContext
        )
        return currentFeatureState.copy(agentStatuses = currentFeatureState.agentStatuses + (agentId to updatedStatus), agentsToPersist = null)
    }

    private fun handleMessagePosted(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState {
        val payload = action.payload?.let { json.decodeFromJsonElement<MessagePostedPayload>(it) } ?: return currentFeatureState
        val entry = payload.entry; val messageId = entry["id"]?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
        val sessionId = payload.sessionId; val senderId = entry["senderId"]?.jsonPrimitive?.contentOrNull
        val currentTime = platformDependencies.getSystemTimeMillis()

        val updatedStatuses = currentFeatureState.agentStatuses.toMutableMap()
        currentFeatureState.agents.values.forEach { agent ->
            val currentStatus = updatedStatuses[agent.id] ?: AgentStatusInfo()
            if ((agent.subscribedSessionIds.contains(sessionId) || agent.privateSessionId == sessionId) && agent.id != senderId) {
                updatedStatuses[agent.id] = currentStatus.copy(lastSeenMessageId = messageId, lastMessageReceivedTimestamp = currentTime, waitingSinceTimestamp = currentStatus.waitingSinceTimestamp ?: currentTime)
            } else if (agent.id == senderId) {
                updatedStatuses[agent.id] = currentStatus.copy(lastSeenMessageId = messageId)
            }
        }
        var newCardMap = currentFeatureState.agentAvatarCardIds
        val metadata = entry["metadata"]?.jsonObject
        val isAvatar = metadata?.get("render_as_partial")?.jsonPrimitive?.booleanOrNull ?: false
        if (isAvatar) {
            val agentId = entry["senderId"]?.jsonPrimitive?.contentOrNull
            if (agentId != null && currentFeatureState.agents.containsKey(agentId)) {
                newCardMap = newCardMap + (agentId to AgentRuntimeState.AvatarCardInfo(messageId = messageId, sessionId = sessionId))
            }
        }
        return currentFeatureState.copy(agentStatuses = updatedStatuses, agentAvatarCardIds = newCardMap)
    }

    override fun onAction(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {
        val agentState = newState as? AgentRuntimeState ?: return
        when (action.name) {
            ActionNames.SYSTEM_PUBLISH_STARTING -> {
                store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_LIST))
                store.deferredDispatch(this.name, Action(ActionNames.GATEWAY_REQUEST_AVAILABLE_MODELS))
            }
            ActionNames.AGENT_INTERNAL_AGENTS_LOADED -> {
                store.deferredDispatch(this.name, Action(ActionNames.AGENT_INTERNAL_VALIDATE_SOVEREIGN_STATE))
            }
            ActionNames.AGENT_INTERNAL_VALIDATE_SOVEREIGN_STATE -> {
                SovereignAgentLogic.validateAndCorrectStartupState(store, agentState)
            }
            ActionNames.AGENT_INTERNAL_AGENT_LOADED -> {
                val agent = action.payload?.let { json.decodeFromJsonElement<AgentInstance>(it) } ?: return
                val targetSessionId = agent.privateSessionId ?: agent.subscribedSessionIds.firstOrNull()
                if (targetSessionId != null) {
                    AgentAvatarLogic.updateAgentAvatarCard(agent.id, AgentStatus.IDLE, null, store)
                }
                broadcastAgentNames(agentState, store)
            }
            ActionNames.AGENT_CREATE -> {
                val agentToSave = agentState.agents.values.lastOrNull() ?: return
                saveAgentConfig(agentToSave, store)
                broadcastAgentNames(agentState, store)
            }
            ActionNames.AGENT_CLONE -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                val agentToClone = agentState.agents[agentId] ?: return
                val createPayload = buildJsonObject {
                    put("name", "${agentToClone.name} (Copy)")
                    agentToClone.knowledgeGraphId?.let { put("knowledgeGraphId", it) }
                    put("modelProvider", agentToClone.modelProvider)
                    put("modelName", agentToClone.modelName)
                    put("subscribedSessionIds", buildJsonArray { agentToClone.subscribedSessionIds.forEach { add(it) } })
                    put("automaticMode", agentToClone.automaticMode)
                    put("autoWaitTimeSeconds", agentToClone.autoWaitTimeSeconds)
                    put("autoMaxWaitTimeSeconds", agentToClone.autoMaxWaitTimeSeconds)
                }
                store.deferredDispatch(this.name, Action(ActionNames.AGENT_CREATE, createPayload))
            }
            ActionNames.AGENT_TOGGLE_AUTOMATIC_MODE, ActionNames.AGENT_TOGGLE_ACTIVE -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                val agent = agentState.agents[agentId] ?: return
                saveAgentConfig(agent, store)
                AgentAvatarLogic.touchAgentAvatarCard(agent, agentState, store)
            }
            ActionNames.AGENT_UPDATE_CONFIG -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                val oldAgent = (previousState as? AgentRuntimeState)?.agents?.get(agentId)
                val newAgent = agentState.agents[agentId] ?: return

                SovereignAgentLogic.handleSovereignAssignment(store, oldAgent, newAgent)
                SovereignAgentLogic.handleSovereignRevocation(store, oldAgent, newAgent)

                saveAgentConfig(newAgent, store)
                broadcastAgentNames(agentState, store)

                val currentStatus = agentState.agentStatuses[agentId]?.status ?: AgentStatus.IDLE
                if (oldAgent != null && (oldAgent.subscribedSessionIds != newAgent.subscribedSessionIds || oldAgent.privateSessionId != newAgent.privateSessionId)) {
                    AgentAvatarLogic.updateAgentAvatarCard(agentId, currentStatus, null, store)
                }
            }
            ActionNames.AGENT_DELETE -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                agentState.agentAvatarCardIds[agentId]?.let { cardInfo ->
                    store.deferredDispatch(this.name, Action(ActionNames.SESSION_DELETE_MESSAGE, buildJsonObject {
                        put("session", cardInfo.sessionId)
                        put("messageId", cardInfo.messageId)
                    }))
                }
                store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_DELETE_DIRECTORY, buildJsonObject { put("subpath", agentId) }))
                store.deferredDispatch(this.name, Action(ActionNames.AGENT_INTERNAL_CONFIRM_DELETE, buildJsonObject { put("agentId", agentId) }))
                store.deferredDispatch(this.name, Action(ActionNames.AGENT_PUBLISH_AGENT_DELETED, buildJsonObject { put("agentId", agentId) }))
                broadcastAgentNames(agentState, store)
            }
            ActionNames.AGENT_INITIATE_TURN -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                // Delegate immediately to the Pipeline
                AgentCognitivePipeline.startCognitiveCycle(agentId, store)
            }
            ActionNames.AGENT_EXECUTE_PREVIEWED_TURN -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                val agent = agentState.agents[agentId] ?: return
                val statusInfo = agentState.agentStatuses[agentId]
                val previewData = statusInfo?.stagedPreviewData ?: return

                AgentAvatarLogic.updateAgentAvatarCard(agentId, AgentStatus.PROCESSING, null, store)

                store.deferredDispatch(this.name, Action(ActionNames.GATEWAY_GENERATE_CONTENT, buildJsonObject {
                    put("providerId", agent.modelProvider)
                    put("modelName", previewData.agnosticRequest.modelName)
                    put("correlationId", previewData.agnosticRequest.correlationId)
                    put("contents", json.encodeToJsonElement(previewData.agnosticRequest.contents))
                    previewData.agnosticRequest.systemPrompt?.let { put("systemPrompt", it) }
                }))
                store.deferredDispatch(this.name, Action(ActionNames.AGENT_DISCARD_PREVIEW, buildJsonObject { put("agentId", agentId) }))
                store.dispatch("ui.agent", Action(ActionNames.CORE_SHOW_DEFAULT_VIEW))
            }
            ActionNames.AGENT_DISCARD_PREVIEW -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                val statusInfo = agentState.agentStatuses[agentId]
                if (statusInfo?.status != AgentStatus.PROCESSING) {
                    store.deferredDispatch(this.name, Action(ActionNames.AGENT_INTERNAL_SET_PROCESSING_STEP, buildJsonObject {
                        put("agentId", agentId); put("step", JsonNull)
                    }))
                }
                store.dispatch("ui.agent", Action(ActionNames.CORE_SHOW_DEFAULT_VIEW))
            }
            ActionNames.AGENT_CANCEL_TURN -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                store.deferredDispatch(this.name, Action(ActionNames.GATEWAY_CANCEL_REQUEST, buildJsonObject {
                    put("correlationId", agentId)
                }))
                activeTurnJobs[agentId]?.cancel()
                activeTurnJobs.remove(agentId)
                AgentAvatarLogic.updateAgentAvatarCard(agentId, AgentStatus.IDLE, "Turn cancelled by user.", store)
            }
            ActionNames.SESSION_PUBLISH_MESSAGE_POSTED -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<MessagePostedPayload>(it) } ?: return
                val entry = payload.entry
                val metadata = entry["metadata"]?.jsonObject
                val isAvatar = metadata?.get("render_as_partial")?.jsonPrimitive?.booleanOrNull ?: false
                if (isAvatar) return

                agentState.agents.values.forEach { agent ->
                    if ((agent.subscribedSessionIds.contains(payload.sessionId) || agent.privateSessionId == payload.sessionId) && agent.id != payload.entry["senderId"]?.jsonPrimitive?.content) {
                        val status = agentState.agentStatuses[agent.id]?.status ?: AgentStatus.IDLE
                        if (status == AgentStatus.IDLE) {
                            AgentAvatarLogic.updateAgentAvatarCard(agent.id, AgentStatus.WAITING, null, store)
                        }
                    }
                }
            }
            ActionNames.AGENT_INTERNAL_CHECK_AUTOMATIC_TRIGGERS -> {
                AgentAutoTriggerLogic.checkAndDispatchTriggers(store, agentState, platformDependencies, name)
            }
            ActionNames.SESSION_PUBLISH_SESSION_NAMES_UPDATED -> {
                SovereignAgentLogic.linkPrivateSessionOnCreation(store, agentState)
            }
        }
    }

    private fun saveAgentConfig(agent: AgentInstance, store: Store) {
        store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
            put("subpath", "${agent.id}/$agentConfigFILENAME")
            put("content", json.encodeToString(agent))
        }))
    }

    private fun broadcastAgentNames(state: AgentRuntimeState, store: Store) {
        val nameMap = state.agents.mapValues { it.value.name }
        store.deferredDispatch(this.name, Action(ActionNames.AGENT_PUBLISH_AGENT_NAMES_UPDATED, buildJsonObject {
            put("names", Json.encodeToJsonElement(nameMap))
        }))
    }

    override fun onPrivateData(envelope: PrivateDataEnvelope, store: Store) {
        platformDependencies.log(LogLevel.DEBUG, name, "[INSTRUMENTATION] onPrivateData << ${envelope.type}")
        when (envelope.type) {
            ActionNames.Envelopes.SESSION_RESPONSE_LEDGER,
            ActionNames.Envelopes.KNOWLEDGEGRAPH_RESPONSE_CONTEXT -> {
                AgentCognitivePipeline.handlePrivateData(envelope, store)
            }

            ActionNames.Envelopes.GATEWAY_RESPONSE -> handleGatewayResponse(envelope.payload, store)
            ActionNames.Envelopes.GATEWAY_RESPONSE_PREVIEW -> handleGatewayPreviewResponse(envelope.payload, store)
            ActionNames.Envelopes.FILESYSTEM_RESPONSE_LIST -> handleFileSystemListResponse(envelope.payload, store)
            ActionNames.Envelopes.FILESYSTEM_RESPONSE_READ -> handleFileSystemReadResponse(envelope.payload, store)
        }
    }

    private fun handleGatewayPreviewResponse(payload: JsonObject, store: Store) {
        val decoded = try { json.decodeFromJsonElement<GatewayPreviewResponsePayload>(payload) } catch (e: Exception) { return }
        val agent = (store.state.value.featureStates[name] as? AgentRuntimeState)?.agents?.get(decoded.correlationId) ?: return

        store.deferredDispatch(this.name, Action(ActionNames.AGENT_INTERNAL_SET_PREVIEW_DATA, buildJsonObject {
            put("agentId", agent.id)
            put("agnosticRequest", json.encodeToJsonElement(decoded.agnosticRequest))
            put("rawRequestJson", decoded.rawRequestJson)
        }))
        store.dispatch("ui.agent", Action(ActionNames.CORE_SET_ACTIVE_VIEW, buildJsonObject {
            put("key", "feature.agent.context_viewer")
        }))
    }

    private fun handleFileSystemListResponse(payload: JsonObject, store: Store) {
        val fileList = payload["listing"]?.jsonArray?.map { json.decodeFromJsonElement<FileEntry>(it) } ?: return
        agentLoadCount = fileList.count { it.isDirectory }
        if (agentLoadCount == 0) {
            store.deferredDispatch(this.name, Action(ActionNames.AGENT_INTERNAL_AGENTS_LOADED))
        } else {
            fileList.forEach { entry ->
                if (entry.isDirectory) {
                    store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_READ, buildJsonObject {
                        put("subpath", "${platformDependencies.getFileName(entry.path)}/$agentConfigFILENAME")
                    }))
                }
            }
        }
    }

    private fun handleFileSystemReadResponse(payload: JsonObject, store: Store) {
        val content = payload["content"]?.jsonPrimitive?.contentOrNull ?: return
        try {
            val agent = json.decodeFromString<AgentInstance>(content)
            store.deferredDispatch(this.name, Action(ActionNames.AGENT_INTERNAL_AGENT_LOADED, json.encodeToJsonElement(agent) as JsonObject))
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.ERROR, name, "Failed to parse agent config from file: ${payload["subpath"]}. Error: ${e.message}")
        } finally {
            agentLoadCount--
            if (agentLoadCount <= 0) {
                store.deferredDispatch(this.name, Action(ActionNames.AGENT_INTERNAL_AGENTS_LOADED))
            }
        }
    }

    private fun handleGatewayResponse(payload: JsonObject, store: Store) {
        val agentId = payload["correlationId"]?.jsonPrimitive?.contentOrNull
        if (agentId == null || ("rawContent" !in payload && "errorMessage" !in payload)) {
            platformDependencies.log(LogLevel.ERROR, name, "FATAL: Received corrupted gateway response payload. Payload: $payload")
            return
        }

        val decoded = try {
            json.decodeFromJsonElement<GatewayResponsePayload>(payload)
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.ERROR, name, "FATAL: Failed to parse gateway response. Error: ${e.message}")
            AgentAvatarLogic.updateAgentAvatarCard(agentId, AgentStatus.ERROR, "FATAL: Could not parse gateway response.", store)
            return
        }

        val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
        val agent = agentState.agents[decoded.correlationId] ?: return

        val targetSessionId = agent.privateSessionId ?: agent.subscribedSessionIds.firstOrNull()

        if (targetSessionId == null) {
            AgentAvatarLogic.updateAgentAvatarCard(agent.id, AgentStatus.ERROR, "No session to post response.", store)
            return
        }

        if (decoded.errorMessage != null) {
            AgentAvatarLogic.updateAgentAvatarCard(agent.id, AgentStatus.ERROR, "[AGENT ERROR] Generation failed: ${decoded.errorMessage}", store)
        } else {
            var contentToPost = decoded.rawContent ?: ""
            val match = redundantHeaderRegex.find(contentToPost)
            if (match != null) {
                contentToPost = contentToPost.substring(match.range.last + 1).trimStart()
                val warningMessage = """SYSTEM SENTINEL (llm-output-sanitizer): Warning for [${agent.name}]: Please do not include the standard system "name (id) @timestamp:" part in your output. This is added automatically by the application."""
                store.deferredDispatch(this.name, Action(ActionNames.SESSION_POST, buildJsonObject {
                    put("session", targetSessionId)
                    put("senderId", "system")
                    put("message", warningMessage)
                }))
            }

            store.deferredDispatch(this.name, Action(ActionNames.SESSION_POST, buildJsonObject {
                put("session", targetSessionId); put("senderId", agent.id); put("message", contentToPost)
            }))
            AgentAvatarLogic.updateAgentAvatarCard(agent.id, AgentStatus.IDLE, null, store)
        }
    }

    override val composableProvider: ComposableProvider = object : ComposableProvider {
        override val stageViews: Map<String, @Composable (Store, List<Feature>) -> Unit> =
            mapOf(
                "feature.agent.manager" to { store, _, -> AgentManagerView(store, platformDependencies) },
                "feature.agent.context_viewer" to { store, _ -> AgentContextView(store) }
            )
        @Composable
        override fun RibbonContent(store: Store, activeViewKey: String?) {
            val viewKey = "feature.agent.manager"
            val isActive = activeViewKey == viewKey
            IconButton(onClick = { store.dispatch("ui.ribbon", Action(ActionNames.CORE_SET_ACTIVE_VIEW, buildJsonObject { put("key", viewKey) })) }) {
                Icon(Icons.Default.Bolt, "Agent Manager", tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        @Composable
        override fun PartialView(store: Store, partId: String, context: Any?) {
            if (partId != "agent.avatar") return
            val agentId = context as? String ?: return
            val appState by store.state.collectAsState()
            val state = appState.featureStates[name] as? AgentRuntimeState ?: return
            val agent = state.agents[agentId] ?: return
            AgentAvatarCard(agent = agent, store = store, platformDependencies = platformDependencies)
        }
    }
}