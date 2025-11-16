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
                val currentState = store.state.value.featureStates[name] as? AgentRuntimeState
                if (currentState?.agents?.values?.any { it.status == AgentStatus.WAITING && it.automaticMode && it.isAgentActive } == true) {
                    store.deferredDispatch(name, Action(ActionNames.AGENT_INTERNAL_CHECK_AUTOMATIC_TRIGGERS))
                }
            }
        }
    }

    override fun reducer(state: FeatureState?, action: Action): FeatureState? {
        val currentFeatureState = state as? AgentRuntimeState ?: AgentRuntimeState()

        when (action.name) {
            ActionNames.AGENT_CREATE -> {
                val payload = action.payload ?: return currentFeatureState
                val newAgent = AgentInstance(
                    id = platformDependencies.generateUUID(),
                    name = payload["name"]?.jsonPrimitive?.contentOrNull ?: "New Agent",
                    knowledgeGraphId = payload["knowledgeGraphId"]?.jsonPrimitive?.contentOrNull,
                    modelProvider = payload["modelProvider"]?.jsonPrimitive?.contentOrNull ?: "gemini",
                    modelName = payload["modelName"]?.jsonPrimitive?.contentOrNull ?: "gemini-pro",
                    subscribedSessionIds = payload["subscribedSessionIds"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                    automaticMode = payload["automaticMode"]?.jsonPrimitive?.booleanOrNull ?: false,
                    autoWaitTimeSeconds = payload["autoWaitTimeSeconds"]?.jsonPrimitive?.intOrNull ?: 5,
                    autoMaxWaitTimeSeconds = payload["autoMaxWaitTimeSeconds"]?.jsonPrimitive?.intOrNull ?: 30
                )
                return currentFeatureState.copy(agents = currentFeatureState.agents + (newAgent.id to newAgent), editingAgentId = newAgent.id)
            }
            ActionNames.AGENT_UPDATE_CONFIG -> {
                val payload = action.payload ?: return currentFeatureState
                val agentId = payload["agentId"]?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
                val agentToUpdate = currentFeatureState.agents[agentId] ?: return currentFeatureState

                val newSubscribedSessionIds = if ("subscribedSessionIds" in payload) {
                    payload["subscribedSessionIds"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                } else {
                    agentToUpdate.subscribedSessionIds
                }
                val filteredSubscribedSessionIds = newSubscribedSessionIds.filter { sessionId ->
                    currentFeatureState.sessionNames[sessionId]?.startsWith("p-cognition:") == false
                }

                val updatedAgent = agentToUpdate.copy(
                    name = payload["name"]?.jsonPrimitive?.contentOrNull ?: agentToUpdate.name,
                    knowledgeGraphId = if ("knowledgeGraphId" in payload) payload["knowledgeGraphId"]?.jsonPrimitive?.contentOrNull else agentToUpdate.knowledgeGraphId,
                    privateSessionId = if ("privateSessionId" in payload) payload["privateSessionId"]?.jsonPrimitive?.contentOrNull else agentToUpdate.privateSessionId,
                    modelProvider = payload["modelProvider"]?.jsonPrimitive?.contentOrNull ?: agentToUpdate.modelProvider,
                    modelName = payload["modelName"]?.jsonPrimitive?.contentOrNull ?: agentToUpdate.modelName,
                    subscribedSessionIds = filteredSubscribedSessionIds,
                    automaticMode = payload["automaticMode"]?.jsonPrimitive?.booleanOrNull ?: agentToUpdate.automaticMode,
                    autoWaitTimeSeconds = payload["autoWaitTimeSeconds"]?.jsonPrimitive?.intOrNull ?: agentToUpdate.autoWaitTimeSeconds,
                    autoMaxWaitTimeSeconds = payload["autoMaxWaitTimeSeconds"]?.jsonPrimitive?.intOrNull ?: agentToUpdate.autoMaxWaitTimeSeconds
                )
                return currentFeatureState.copy(agents = currentFeatureState.agents + (agentId to updatedAgent))
            }
            ActionNames.AGENT_TOGGLE_AUTOMATIC_MODE -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
                val agentToUpdate = currentFeatureState.agents[agentId] ?: return currentFeatureState
                val updatedAgent = agentToUpdate.copy(automaticMode = !agentToUpdate.automaticMode)
                return currentFeatureState.copy(agents = currentFeatureState.agents + (agentId to updatedAgent))
            }
            ActionNames.AGENT_TOGGLE_ACTIVE -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
                val agentToUpdate = currentFeatureState.agents[agentId] ?: return currentFeatureState
                val updatedAgent = agentToUpdate.copy(isAgentActive = !agentToUpdate.isAgentActive)
                return currentFeatureState.copy(agents = currentFeatureState.agents + (agentId to updatedAgent))
            }
            ActionNames.AGENT_SET_EDITING -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull
                return currentFeatureState.copy(editingAgentId = if (agentId == currentFeatureState.editingAgentId) null else agentId)
            }
            ActionNames.AGENT_INTERNAL_CONFIRM_DELETE -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
                return currentFeatureState.copy(
                    agents = currentFeatureState.agents - agentId,
                    agentAvatarCardIds = currentFeatureState.agentAvatarCardIds - agentId
                )
            }
            ActionNames.AGENT_INTERNAL_SET_STATUS -> return handleSetStatus(action, currentFeatureState)
            ActionNames.AGENT_INTERNAL_SET_PROCESSING_STEP -> {
                val payload = action.payload ?: return currentFeatureState
                val agentId = payload["agentId"]?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
                val agentToUpdate = currentFeatureState.agents[agentId] ?: return currentFeatureState
                val step = payload["step"]?.jsonPrimitive?.contentOrNull
                val updatedAgent = agentToUpdate.copy(processingStep = step)
                return currentFeatureState.copy(agents = currentFeatureState.agents + (agentId to updatedAgent))
            }
            ActionNames.AGENT_INTERNAL_STAGE_TURN_CONTEXT -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<StageTurnContextPayload>(it) } ?: return currentFeatureState
                val agent = currentFeatureState.agents[payload.agentId] ?: return currentFeatureState
                val updatedAgent = agent.copy(stagedTurnContext = payload.messages)
                return currentFeatureState.copy(agents = currentFeatureState.agents + (agent.id to updatedAgent))
            }
            ActionNames.AGENT_INTERNAL_SET_HKG_CONTEXT -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<SetHkgContextPayload>(it) } ?: return currentFeatureState
                val agent = currentFeatureState.agents[payload.agentId] ?: return currentFeatureState
                val updatedAgent = agent.copy(transientHkgContext = payload.context)
                return currentFeatureState.copy(agents = currentFeatureState.agents + (agent.id to updatedAgent))
            }
            ActionNames.AGENT_INTERNAL_AGENT_LOADED -> {
                val agent = action.payload?.let { json.decodeFromJsonElement<AgentInstance>(it) } ?: return currentFeatureState
                return if (!currentFeatureState.agents.containsKey(agent.id)) currentFeatureState.copy(agents = currentFeatureState.agents + (agent.id to agent)) else currentFeatureState
            }
            ActionNames.AGENT_INITIATE_TURN -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<InitiateTurnPayload>(it) } ?: return currentFeatureState
                val agent = currentFeatureState.agents[payload.agentId] ?: return currentFeatureState
                if (agent.status == AgentStatus.PROCESSING) return currentFeatureState
                val updatedAgent = agent.copy(
                    processingFrontierMessageId = agent.lastSeenMessageId,
                    turnMode = if (payload.preview) TurnMode.PREVIEW else TurnMode.DIRECT,
                    stagedTurnContext = null,
                    transientHkgContext = null
                )
                return currentFeatureState.copy(agents = currentFeatureState.agents + (agent.id to updatedAgent))
            }
            ActionNames.AGENT_DISCARD_PREVIEW -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
                val agent = currentFeatureState.agents[agentId] ?: return currentFeatureState
                val updatedAgent = agent.copy(stagedPreviewData = null, processingStep = null)
                return currentFeatureState.copy(
                    agents = currentFeatureState.agents + (agentId to updatedAgent),
                    viewingContextForAgentId = null
                )
            }
            ActionNames.AGENT_INTERNAL_SET_PREVIEW_DATA -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<SetPreviewDataPayload>(it) } ?: return currentFeatureState
                val agent = currentFeatureState.agents[payload.agentId] ?: return currentFeatureState
                val previewData = StagedPreviewData(payload.agnosticRequest, payload.rawRequestJson)
                val updatedAgent = agent.copy(stagedPreviewData = previewData)
                return currentFeatureState.copy(
                    agents = currentFeatureState.agents + (agent.id to updatedAgent),
                    viewingContextForAgentId = agent.id
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
        val agentToUpdate = currentFeatureState.agents[agentId] ?: return currentFeatureState
        val newStatusString = payload["status"]?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
        val newStatus = try {
            AgentStatus.valueOf(newStatusString)
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.ERROR, name, "Received invalid agent status string '$newStatusString' for agent '$agentId'. Action ignored.")
            return currentFeatureState
        }
        val newErrorMessage = if (newStatus == AgentStatus.ERROR) payload["error"]?.jsonPrimitive?.contentOrNull else null
        val clearTimers = agentToUpdate.status == AgentStatus.WAITING && newStatus != AgentStatus.WAITING
        val isStartingProcessing = newStatus == AgentStatus.PROCESSING && agentToUpdate.status != AgentStatus.PROCESSING
        val isStoppingProcessing = newStatus != AgentStatus.PROCESSING && agentToUpdate.status == AgentStatus.PROCESSING

        val shouldClearContext = isStoppingProcessing || newStatus == AgentStatus.IDLE || newStatus == AgentStatus.ERROR

        val updatedAgent = agentToUpdate.copy(
            status = newStatus,
            errorMessage = newErrorMessage,
            waitingSinceTimestamp = if (clearTimers) null else agentToUpdate.waitingSinceTimestamp,
            lastMessageReceivedTimestamp = if (clearTimers) null else agentToUpdate.lastMessageReceivedTimestamp,
            processingSinceTimestamp = if (isStartingProcessing) platformDependencies.getSystemTimeMillis() else if (isStoppingProcessing) null else agentToUpdate.processingSinceTimestamp,
            processingFrontierMessageId = if (isStoppingProcessing) null else agentToUpdate.processingFrontierMessageId,
            processingStep = if (isStoppingProcessing) null else agentToUpdate.processingStep,
            stagedTurnContext = if(shouldClearContext) null else agentToUpdate.stagedTurnContext,
            transientHkgContext = if (shouldClearContext) null else agentToUpdate.transientHkgContext
        )
        return currentFeatureState.copy(
            agents = currentFeatureState.agents + (agentId to updatedAgent),
            agentsToPersist = null
        )
    }

    private fun handleMessagePosted(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState {
        val payload = action.payload?.let { json.decodeFromJsonElement<MessagePostedPayload>(it) } ?: return currentFeatureState
        val entry = payload.entry; val messageId = entry["id"]?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
        val sessionId = payload.sessionId; val senderId = entry["senderId"]?.jsonPrimitive?.contentOrNull
        val currentTime = platformDependencies.getSystemTimeMillis()
        val affectedAgents = currentFeatureState.agents.mapValues { (_, agent) ->
            if ((agent.subscribedSessionIds.contains(sessionId) || agent.privateSessionId == sessionId) && agent.id != senderId) {
                agent.copy(
                    lastSeenMessageId = messageId, lastMessageReceivedTimestamp = currentTime,
                    waitingSinceTimestamp = agent.waitingSinceTimestamp ?: currentTime
                )
            } else if (agent.id == senderId && !isAvatarCard(payload)) {
                agent.copy(lastSeenMessageId = messageId)
            } else {
                agent
            }
        }
        if (isAvatarCard(payload)) {
            val agentId = entry["senderId"]?.jsonPrimitive?.contentOrNull
            if (agentId != null && currentFeatureState.agents.containsKey(agentId)) {
                val newCardInfo = AgentRuntimeState.AvatarCardInfo(messageId = messageId, sessionId = sessionId)
                return currentFeatureState.copy(agents = affectedAgents, agentAvatarCardIds = currentFeatureState.agentAvatarCardIds + (agentId to newCardInfo))
            }
        }
        return currentFeatureState.copy(agents = affectedAgents)
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
                if (oldAgent != null && (oldAgent.subscribedSessionIds != newAgent.subscribedSessionIds || oldAgent.privateSessionId != newAgent.privateSessionId)) {
                    AgentAvatarLogic.updateAgentAvatarCard(agentId, newAgent.status, null, store)
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
            ActionNames.SESSION_PUBLISH_SESSION_DELETED -> {
                agentState.agentsToPersist?.forEach { agentId ->
                    agentState.agents[agentId]?.let { saveAgentConfig(it, store) }
                }
            }
            ActionNames.AGENT_INITIATE_TURN -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                val agent = agentState.agents[agentId] ?: return
                if (agent.status == AgentStatus.PROCESSING || !agent.isAgentActive) return

                val contextSessionId = agent.privateSessionId ?: agent.subscribedSessionIds.firstOrNull() ?: run {
                    AgentAvatarLogic.updateAgentAvatarCard(agentId, AgentStatus.ERROR, "Cannot start turn: Agent has no session for context.", store)
                    return
                }
                if (agent.turnMode == TurnMode.DIRECT) {
                    AgentAvatarLogic.updateAgentAvatarCard(agentId, AgentStatus.PROCESSING, null, store)
                }
                store.deferredDispatch(this.name, Action(ActionNames.AGENT_INTERNAL_SET_PROCESSING_STEP, buildJsonObject {
                    put("agentId", agentId); put("step", "Requesting Ledger")
                }))
                store.deferredDispatch(this.name, Action(ActionNames.SESSION_REQUEST_LEDGER_CONTENT, buildJsonObject {
                    put("sessionId", contextSessionId); put("correlationId", agentId)
                }))
            }
            ActionNames.AGENT_EXECUTE_PREVIEWED_TURN -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                val agent = agentState.agents[agentId] ?: return
                val previewData = agent.stagedPreviewData ?: return

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
                val agent = agentState.agents[agentId]
                if (agent?.status != AgentStatus.PROCESSING) {
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
                if (isAvatarCard(payload)) return
                agentState.agents.values.forEach { agent ->
                    if ((agent.subscribedSessionIds.contains(payload.sessionId) || agent.privateSessionId == payload.sessionId) && agent.id != payload.entry["senderId"]?.jsonPrimitive?.content) {
                        if (agent.status == AgentStatus.IDLE) {
                            AgentAvatarLogic.updateAgentAvatarCard(agent.id, AgentStatus.WAITING, null, store)
                        }
                    }
                }
            }
            ActionNames.AGENT_INTERNAL_CHECK_AUTOMATIC_TRIGGERS -> {
                val currentTime = platformDependencies.getSystemTimeMillis()
                agentState.agents.values.forEach { agent ->
                    if (agent.automaticMode && agent.isAgentActive && agent.status == AgentStatus.WAITING && agent.waitingSinceTimestamp != null && agent.lastMessageReceivedTimestamp != null) {
                        val waitedFor = (currentTime - agent.lastMessageReceivedTimestamp) / 1000
                        val totalWait = (currentTime - agent.waitingSinceTimestamp) / 1000
                        val debounceTrigger = waitedFor >= agent.autoWaitTimeSeconds
                        val timeoutTrigger = totalWait >= agent.autoMaxWaitTimeSeconds
                        if (debounceTrigger || timeoutTrigger) {
                            store.deferredDispatch(this.name, Action(ActionNames.AGENT_INITIATE_TURN, buildJsonObject {
                                put("agentId", agent.id)
                                put("preview", false)
                            }))
                        }
                    }
                }
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
            ActionNames.Envelopes.GATEWAY_RESPONSE -> handleGatewayResponse(envelope.payload, store)
            ActionNames.Envelopes.GATEWAY_RESPONSE_PREVIEW -> handleGatewayPreviewResponse(envelope.payload, store)
            ActionNames.Envelopes.SESSION_RESPONSE_LEDGER -> handleSessionLedgerResponse(envelope.payload, store)
            ActionNames.Envelopes.KNOWLEDGEGRAPH_RESPONSE_CONTEXT -> handleKnowledgeGraphContextResponse(envelope.payload, store)
            ActionNames.Envelopes.FILESYSTEM_RESPONSE_LIST -> handleFileSystemListResponse(envelope.payload, store)
            ActionNames.Envelopes.FILESYSTEM_RESPONSE_READ -> handleFileSystemReadResponse(envelope.payload, store)
        }
    }

    private fun handleKnowledgeGraphContextResponse(payload: JsonObject, store: Store) {
        val agentId = payload["correlationId"]?.jsonPrimitive?.contentOrNull ?: return
        val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
        val agent = agentState.agents[agentId] ?: return
        val ledgerContext = agent.stagedTurnContext ?: run {
            platformDependencies.log(LogLevel.ERROR, name, "HKG context received for agent '$agentId', but staged ledger context was missing. Aborting turn.")
            AgentAvatarLogic.updateAgentAvatarCard(agentId, AgentStatus.ERROR, "Internal Error: Staged context lost.", store)
            return
        }
        val hkgContext = payload["context"]?.jsonObject

        store.dispatch(this.name, Action(ActionNames.AGENT_INTERNAL_SET_HKG_CONTEXT, buildJsonObject {
            put("agentId", agentId)
            put("context", hkgContext ?: buildJsonObject {})
        }))

        val updatedAgentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
        val updatedAgent = updatedAgentState.agents[agentId] ?: return

        AgentCognitiveLogic.assemblePromptAndRequestGeneration(updatedAgent, ledgerContext, updatedAgent.transientHkgContext, updatedAgentState, store)
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

    private fun handleSessionLedgerResponse(payload: JsonObject, store: Store) {
        platformDependencies.log(LogLevel.DEBUG, name, "[INSTRUMENTATION] Entering handleSessionLedgerResponse.")
        val decoded = try {
            json.decodeFromJsonElement<LedgerResponsePayload>(payload)
        } catch (e: Exception) {
            val agentId = payload["correlationId"]?.jsonPrimitive?.contentOrNull
            val errorMessage = "FATAL: Failed to parse session ledger."
            platformDependencies.log(LogLevel.DEBUG, name, "[INSTRUMENTATION] Caught exception in handleSessionLedgerResponse. Calling updateAgentAvatarCard.")
            platformDependencies.log(LogLevel.ERROR, name, "$errorMessage for agent '$agentId'. Error: ${e.message}")
            if (agentId != null) {
                AgentAvatarLogic.updateAgentAvatarCard(agentId, AgentStatus.ERROR, errorMessage, store)
            }
            return
        }
        val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
        val agent = agentState.agents[decoded.correlationId] ?: return

        val enrichedMessages = decoded.messages.mapNotNull { element ->
            try {
                val entryJson = element.jsonObject
                val senderId = entryJson["senderId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val rawContent = entryJson["rawContent"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val timestamp = entryJson["timestamp"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null

                val actingAgentId = agent.id
                val user = agentState.userIdentities.find { it.id == senderId }

                val (senderName, role) = when {
                    senderId == actingAgentId -> agent.name to "model"
                    agentState.agents.containsKey(senderId) -> agentState.agents[senderId]!!.name to "user"
                    user != null -> user.name to "user"
                    else -> "Unknown" to "user"
                }

                GatewayMessage(role, rawContent, senderId, senderName, timestamp)
            } catch (e: Exception) {
                platformDependencies.log(LogLevel.WARN, name, "Skipping corrupted ledger entry during context assembly: ${e.message}")
                null
            }
        }

        store.dispatch(this.name, Action(ActionNames.AGENT_INTERNAL_STAGE_TURN_CONTEXT, buildJsonObject {
            put("agentId", agent.id)
            put("messages", json.encodeToJsonElement(enrichedMessages))
        }))

        val wasHandledBySovereign = SovereignAgentLogic.requestContextIfSovereign(store, agent)
        if (!wasHandledBySovereign) {
            val refreshedAgentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
            AgentCognitiveLogic.assemblePromptAndRequestGeneration(agent, enrichedMessages, null, refreshedAgentState, store)
        }
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
            platformDependencies.log(LogLevel.ERROR, name, "FATAL: Received corrupted gateway response payload for agent '$agentId'. Missing 'rawContent' and 'errorMessage' keys. Payload: $payload")
            if (agentId != null) {
                AgentAvatarLogic.updateAgentAvatarCard(agentId, AgentStatus.ERROR, "FATAL: Corrupted response from gateway.", store)
            }
            return
        }

        val decoded = try {
            json.decodeFromJsonElement<GatewayResponsePayload>(payload)
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.ERROR, name, "FATAL: Failed to parse gateway response for agent '$agentId'. Error: ${e.message}")
            AgentAvatarLogic.updateAgentAvatarCard(agentId, AgentStatus.ERROR, "FATAL: Could not parse gateway response.", store)
            return
        }

        val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
        val agent = agentState.agents[decoded.correlationId] ?: return

        val targetSessionId = agent.privateSessionId ?: agent.subscribedSessionIds.firstOrNull()

        if (targetSessionId == null) {
            val errorMsg = "Agent '${agent.id}' received a gateway response but has no session to post to. Response dropped."
            platformDependencies.log(LogLevel.ERROR, name, errorMsg)
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

    private fun isAvatarCard(payload : MessagePostedPayload) : Boolean {
        val metadata = payload.entry["metadata"]?.jsonObject
        return metadata?.get("render_as_partial")?.jsonPrimitive?.booleanOrNull ?: false
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