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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class AgentRuntimeFeature(
    private val platformDependencies: PlatformDependencies,
    private val coroutineScope: CoroutineScope
) : Feature {
    override val name: String = "agent"

    @Serializable private data class SessionNamesPayload(val names: Map<String, String>)
    @Serializable private data class GraphNamesPayload(val names: Map<String, String>)
    @Serializable private data class GatewayResponsePayload(val correlationId: String, val rawContent: String? = null, val errorMessage: String? = null)
    @Serializable private data class LedgerResponsePayload(val correlationId: String, val messages: List<JsonObject>) // Generic JsonObject representing LedgerEntry
    @Serializable private data class MessagePostedPayload(val sessionId: String, val entry: JsonObject)
    @Serializable private data class MessageDeletedPayload(val sessionId: String, val messageId: String)
    @Serializable private data class InitiateTurnPayload(val agentId: String, val preview: Boolean = false)
    @Serializable private data class SetPreviewDataPayload(val agentId: String, val agnosticRequest: GatewayRequest, val rawRequestJson: String)
    @Serializable private data class GatewayPreviewResponsePayload(val correlationId: String, val agnosticRequest: GatewayRequest, val rawRequestJson: String)
    @Serializable private data class IdentitiesUpdatedPayload(val identities: List<Identity>, val activeId: String?)
    @Serializable private data class StageTurnContextPayload(val agentId: String, val messages: List<GatewayMessage>)


    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val agentConfigFILENAME = "agent.json"
    private val activeTurnJobs = mutableMapOf<String, Job>()
    private val redundantHeaderRegex = Regex("""^.+? \([^)]+\) @ \d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z:\s*""")

    override fun init(store: Store) {
        coroutineScope.launch {
            while (true) {
                delay(1000)
                val currentState = store.state.value.featureStates[name] as? AgentRuntimeState
                if (currentState?.agents?.values?.any { it.status == AgentStatus.WAITING && it.automaticMode && it.isAgentActive } == true) {
                    store.dispatch(name, Action(ActionNames.AGENT_INTERNAL_CHECK_AUTOMATIC_TRIGGERS))
                }
            }
        }
    }

    override fun reducer(state: AppState, action: Action): AppState {
        platformDependencies.log(LogLevel.DEBUG, name, "REDUCER << $action")
        val (stateWithFeature, currentFeatureState) = state.featureStates[name]
            ?.let { state to (it as AgentRuntimeState) }
            ?: (state.copy(featureStates = state.featureStates + (name to AgentRuntimeState())) to AgentRuntimeState())

        var newFeatureState: AgentRuntimeState? = null
        when (action.name) {
            ActionNames.AGENT_CREATE -> handleCreateAgent(action, currentFeatureState)?.let { newFeatureState = it }
            ActionNames.AGENT_UPDATE_CONFIG -> handleUpdateConfig(action, currentFeatureState)?.let { newFeatureState = it }
            ActionNames.AGENT_TOGGLE_AUTOMATIC_MODE -> handleToggleAutomaticMode(action, currentFeatureState)?.let { newFeatureState = it }
            ActionNames.AGENT_TOGGLE_ACTIVE -> handleToggleActive(action, currentFeatureState)?.let { newFeatureState = it }
            ActionNames.AGENT_SET_EDITING -> handleSetEditing(action, currentFeatureState)?.let { newFeatureState = it }
            ActionNames.AGENT_INTERNAL_CONFIRM_DELETE -> handleDeleteAgent(action, currentFeatureState)?.let { newFeatureState = it }
            ActionNames.AGENT_INTERNAL_SET_STATUS -> handleSetStatus(action, currentFeatureState)?.let { newFeatureState = it }
            ActionNames.AGENT_INTERNAL_SET_PROCESSING_STEP -> handleSetProcessingStep(action, currentFeatureState)?.let { newFeatureState = it }
            ActionNames.AGENT_INTERNAL_STAGE_TURN_CONTEXT -> handleStageTurnContext(action, currentFeatureState)?.let { newFeatureState = it }
            ActionNames.AGENT_INTERNAL_AGENT_LOADED -> handleAgentLoaded(action, currentFeatureState)?.let { newFeatureState = it }
            ActionNames.AGENT_INITIATE_TURN -> handleInitiateTurn(action, currentFeatureState)?.let { newFeatureState = it }
            ActionNames.AGENT_DISCARD_PREVIEW -> handleDiscardPreview(action, currentFeatureState)?.let { newFeatureState = it }
            ActionNames.AGENT_INTERNAL_SET_PREVIEW_DATA -> handleSetPreviewData(action, currentFeatureState)?.let { newFeatureState = it }
            ActionNames.SESSION_PUBLISH_MESSAGE_POSTED -> handleMessagePosted(action, currentFeatureState)?.let { newFeatureState = it }
            ActionNames.SESSION_PUBLISH_MESSAGE_DELETED -> handleMessageDeleted(action, currentFeatureState)?.let { newFeatureState = it }
            ActionNames.SESSION_PUBLISH_SESSION_DELETED -> handleSessionDeleted(action, currentFeatureState)?.let { newFeatureState = it }
            ActionNames.GATEWAY_PUBLISH_AVAILABLE_MODELS_UPDATED -> {
                val decodedModels: Map<String, List<String>>? = try { action.payload?.let { json.decodeFromJsonElement(it) } } catch (e: Exception) { null }
                newFeatureState = currentFeatureState.copy(availableModels = decodedModels ?: emptyMap())
            }
            ActionNames.SESSION_PUBLISH_SESSION_NAMES_UPDATED -> {
                val decoded = try { action.payload?.let { json.decodeFromJsonElement<SessionNamesPayload>(it) } } catch(e: Exception) { null }
                if (decoded != null) { newFeatureState = currentFeatureState.copy(sessionNames = decoded.names) }
            }
            ActionNames.KNOWLEDGEGRAPH_PUBLISH_AVAILABLE_PERSONAS_UPDATED -> {
                val decoded = try { action.payload?.let { json.decodeFromJsonElement<GraphNamesPayload>(it) } } catch(e: Exception) { null }
                if (decoded != null) { newFeatureState = currentFeatureState.copy(knowledgeGraphNames = decoded.names) }
            }
            ActionNames.CORE_PUBLISH_IDENTITIES_UPDATED -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<IdentitiesUpdatedPayload>(it) }
                if (payload != null) {
                    newFeatureState = currentFeatureState.copy(userIdentities = payload.identities)
                }
            }
        }

        return newFeatureState?.let {
            val finalState = if (action.name != ActionNames.SESSION_PUBLISH_SESSION_DELETED) it.copy(agentsToPersist = null) else it
            platformDependencies.log(LogLevel.DEBUG, name, "REDUCER >> State changed: ${finalState != currentFeatureState}")
            if (finalState != currentFeatureState) stateWithFeature.copy(featureStates = stateWithFeature.featureStates + (name to finalState)) else stateWithFeature
        } ?: stateWithFeature
    }

    private fun handleStageTurnContext(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState? {
        val payload = action.payload?.let { json.decodeFromJsonElement<StageTurnContextPayload>(it) } ?: return null
        val agent = currentFeatureState.agents[payload.agentId] ?: return null
        val updatedAgent = agent.copy(stagedTurnContext = payload.messages)
        return currentFeatureState.copy(agents = currentFeatureState.agents + (agent.id to updatedAgent))
    }


    private fun handleInitiateTurn(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState? {
        val payload = action.payload?.let { json.decodeFromJsonElement<InitiateTurnPayload>(it) } ?: return null
        val agent = currentFeatureState.agents[payload.agentId] ?: return null
        if (agent.status == AgentStatus.PROCESSING) return null
        val updatedAgent = agent.copy(
            processingFrontierMessageId = agent.lastSeenMessageId,
            turnMode = if (payload.preview) TurnMode.PREVIEW else TurnMode.DIRECT,
            stagedTurnContext = null // Clear any stale context
        )
        return currentFeatureState.copy(agents = currentFeatureState.agents + (agent.id to updatedAgent))
    }

    private fun handleDiscardPreview(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState? {
        val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return null
        val agent = currentFeatureState.agents[agentId] ?: return null
        val updatedAgent = agent.copy(stagedPreviewData = null, processingStep = null)
        return currentFeatureState.copy(
            agents = currentFeatureState.agents + (agentId to updatedAgent),
            viewingContextForAgentId = null
        )
    }

    private fun handleSetPreviewData(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState? {
        val payload = action.payload?.let { json.decodeFromJsonElement<SetPreviewDataPayload>(it) } ?: return null
        val agent = currentFeatureState.agents[payload.agentId] ?: return null
        val previewData = StagedPreviewData(payload.agnosticRequest, payload.rawRequestJson)
        val updatedAgent = agent.copy(stagedPreviewData = previewData)
        return currentFeatureState.copy(
            agents = currentFeatureState.agents + (agent.id to updatedAgent),
            viewingContextForAgentId = agent.id
        )
    }


    private fun handleCreateAgent(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState? {
        val payload = action.payload ?: return null
        val newAgent = AgentInstance(
            id = platformDependencies.generateUUID(),
            name = payload["name"]?.jsonPrimitive?.contentOrNull ?: "New Agent",
            knowledgeGraphId = payload["knowledgeGraphId"]?.jsonPrimitive?.contentOrNull,
            modelProvider = payload["modelProvider"]?.jsonPrimitive?.contentOrNull ?: "gemini",
            modelName = payload["modelName"]?.jsonPrimitive?.contentOrNull ?: "gemini-pro",
            primarySessionId = payload["primarySessionId"]?.jsonPrimitive?.contentOrNull,
            automaticMode = payload["automaticMode"]?.jsonPrimitive?.booleanOrNull ?: false,
            autoWaitTimeSeconds = payload["autoWaitTimeSeconds"]?.jsonPrimitive?.intOrNull ?: 5,
            autoMaxWaitTimeSeconds = payload["autoMaxWaitTimeSeconds"]?.jsonPrimitive?.intOrNull ?: 30
        )
        return currentFeatureState.copy(agents = currentFeatureState.agents + (newAgent.id to newAgent), editingAgentId = newAgent.id)
    }

    private fun handleDeleteAgent(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState? {
        val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return null
        return currentFeatureState.copy(
            agents = currentFeatureState.agents - agentId,
            agentAvatarCardIds = currentFeatureState.agentAvatarCardIds - agentId
        )
    }

    private fun handleUpdateConfig(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState? {
        val payload = action.payload ?: return null
        val agentId = payload["agentId"]?.jsonPrimitive?.contentOrNull ?: return null
        val agentToUpdate = currentFeatureState.agents[agentId] ?: return null
        val updatedAgent = agentToUpdate.copy(
            name = payload["name"]?.jsonPrimitive?.contentOrNull ?: agentToUpdate.name,
            knowledgeGraphId = if ("knowledgeGraphId" in payload) payload["knowledgeGraphId"]?.jsonPrimitive?.contentOrNull else agentToUpdate.knowledgeGraphId,
            modelProvider = payload["modelProvider"]?.jsonPrimitive?.contentOrNull ?: agentToUpdate.modelProvider,
            modelName = payload["modelName"]?.jsonPrimitive?.contentOrNull ?: agentToUpdate.modelName,
            primarySessionId = if ("primarySessionId" in payload) payload["primarySessionId"]?.jsonPrimitive?.contentOrNull else agentToUpdate.primarySessionId,
            automaticMode = payload["automaticMode"]?.jsonPrimitive?.booleanOrNull ?: agentToUpdate.automaticMode,
            autoWaitTimeSeconds = payload["autoWaitTimeSeconds"]?.jsonPrimitive?.intOrNull ?: agentToUpdate.autoWaitTimeSeconds,
            autoMaxWaitTimeSeconds = payload["autoMaxWaitTimeSeconds"]?.jsonPrimitive?.intOrNull ?: agentToUpdate.autoMaxWaitTimeSeconds
        )
        return currentFeatureState.copy(agents = currentFeatureState.agents + (agentId to updatedAgent))
    }

    private fun handleToggleAutomaticMode(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState? {
        val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return null
        val agentToUpdate = currentFeatureState.agents[agentId] ?: return null
        val updatedAgent = agentToUpdate.copy(automaticMode = !agentToUpdate.automaticMode)
        return currentFeatureState.copy(agents = currentFeatureState.agents + (agentId to updatedAgent))
    }

    private fun handleToggleActive(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState? {
        val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return null
        val agentToUpdate = currentFeatureState.agents[agentId] ?: return null
        val updatedAgent = agentToUpdate.copy(isAgentActive = !agentToUpdate.isAgentActive)
        return currentFeatureState.copy(agents = currentFeatureState.agents + (agentId to updatedAgent))
    }

    private fun handleSessionDeleted(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState? {
        val deletedSessionId = action.payload?.get("sessionId")?.jsonPrimitive?.contentOrNull ?: return null
        val affectedAgentIds = currentFeatureState.agents.values.filter { it.primarySessionId == deletedSessionId }.map { it.id }.toSet()
        if (affectedAgentIds.isEmpty()) return null
        val newAgents = currentFeatureState.agents.mapValues { (_, agent) -> if (agent.id in affectedAgentIds) agent.copy(primarySessionId = null) else agent }
        return currentFeatureState.copy(agents = newAgents, agentsToPersist = affectedAgentIds)
    }

    private fun handleSetStatus(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState? {
        val payload = action.payload ?: return null
        val agentId = payload["agentId"]?.jsonPrimitive?.contentOrNull ?: return null
        val agentToUpdate = currentFeatureState.agents[agentId] ?: return null
        val newStatusString = payload["status"]?.jsonPrimitive?.contentOrNull ?: return null
        val newStatus = try {
            AgentStatus.valueOf(newStatusString)
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.ERROR, name, "Received invalid agent status string '$newStatusString' for agent '$agentId'. Action ignored.")
            return null
        }
        val newErrorMessage = if (newStatus == AgentStatus.ERROR) payload["error"]?.jsonPrimitive?.contentOrNull else null
        val clearTimers = agentToUpdate.status == AgentStatus.WAITING && newStatus != AgentStatus.WAITING
        val isStartingProcessing = newStatus == AgentStatus.PROCESSING && agentToUpdate.status != AgentStatus.PROCESSING
        val isStoppingProcessing = newStatus != AgentStatus.PROCESSING && agentToUpdate.status == AgentStatus.PROCESSING

        // Clear staged context when processing ends
        val shouldClearContext = isStoppingProcessing || newStatus == AgentStatus.IDLE || newStatus == AgentStatus.ERROR

        val updatedAgent = agentToUpdate.copy(
            status = newStatus,
            errorMessage = newErrorMessage,
            waitingSinceTimestamp = if (clearTimers) null else agentToUpdate.waitingSinceTimestamp,
            lastMessageReceivedTimestamp = if (clearTimers) null else agentToUpdate.lastMessageReceivedTimestamp,
            processingSinceTimestamp = if (isStartingProcessing) platformDependencies.getSystemTimeMillis() else if (isStoppingProcessing) null else agentToUpdate.processingSinceTimestamp,
            processingFrontierMessageId = if (isStoppingProcessing) null else agentToUpdate.processingFrontierMessageId,
            processingStep = if (isStoppingProcessing) null else agentToUpdate.processingStep,
            stagedTurnContext = if(shouldClearContext) null else agentToUpdate.stagedTurnContext
        )
        return currentFeatureState.copy(agents = currentFeatureState.agents + (agentId to updatedAgent))
    }

    private fun handleSetProcessingStep(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState? {
        val payload = action.payload ?: return null
        val agentId = payload["agentId"]?.jsonPrimitive?.contentOrNull ?: return null
        val agentToUpdate = currentFeatureState.agents[agentId] ?: return null
        val step = payload["step"]?.jsonPrimitive?.contentOrNull
        val updatedAgent = agentToUpdate.copy(processingStep = step)
        return currentFeatureState.copy(agents = currentFeatureState.agents + (agentId to updatedAgent))
    }

    private fun handleAgentLoaded(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState? {
        val agent = action.payload?.let { json.decodeFromJsonElement<AgentInstance>(it) } ?: return null
        return if (!currentFeatureState.agents.containsKey(agent.id)) currentFeatureState.copy(agents = currentFeatureState.agents + (agent.id to agent)) else null
    }

    private fun handleSetEditing(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState? {
        val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull
        return currentFeatureState.copy(editingAgentId = if (agentId == currentFeatureState.editingAgentId) null else agentId)
    }

    private fun handleMessagePosted(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState? {
        val payload = action.payload?.let { json.decodeFromJsonElement<MessagePostedPayload>(it) } ?: return null
        val entry = payload.entry; val messageId = entry["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val sessionId = payload.sessionId; val senderId = entry["senderId"]?.jsonPrimitive?.contentOrNull
        val currentTime = platformDependencies.getSystemTimeMillis()
        val affectedAgents = currentFeatureState.agents.mapValues { (_, agent) ->
            if (agent.primarySessionId == sessionId && agent.id != senderId) {
                agent.copy(
                    lastSeenMessageId = messageId, lastMessageReceivedTimestamp = currentTime,
                    waitingSinceTimestamp = agent.waitingSinceTimestamp ?: currentTime
                )
            } else if (agent.id == senderId && !isAvatarCard(payload)) {
                // An agent's own text response should advance its own lastSeenMessageId frontier
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

    private fun handleMessageDeleted(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState? {
        val payload = action.payload?.let { json.decodeFromJsonElement<MessageDeletedPayload>(it) } ?: return null
        val agentId = currentFeatureState.agentAvatarCardIds.entries.find { it.value.messageId == payload.messageId }?.key ?: return null
        return currentFeatureState.copy(agentAvatarCardIds = currentFeatureState.agentAvatarCardIds - agentId)
    }

    override fun onAction(action: Action, store: Store, previousState: AppState) {
        platformDependencies.log(LogLevel.DEBUG, name, "ON_ACTION << $action")
        val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
        when (action.name) {
            ActionNames.SYSTEM_PUBLISH_STARTING -> {
                store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_LIST))
                store.dispatch(this.name, Action(ActionNames.GATEWAY_REQUEST_AVAILABLE_MODELS))
            }
            ActionNames.AGENT_INTERNAL_AGENT_LOADED -> {
                val agent = action.payload?.let { json.decodeFromJsonElement<AgentInstance>(it) } ?: return
                if (agent.primarySessionId != null) {
                    updateAgentAvatarCard(agent.id, AgentStatus.IDLE, null, store)
                }
                broadcastAgentNames(store)
            }
            ActionNames.AGENT_CREATE -> {
                val agentToSave = agentState.agents.values.lastOrNull() ?: return
                saveAgentConfig(agentToSave, store)
                broadcastAgentNames(store)
            }
            ActionNames.AGENT_CLONE -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                val agentToClone = agentState.agents[agentId] ?: return
                val createPayload = buildJsonObject {
                    put("name", "${agentToClone.name} (Copy)")
                    agentToClone.knowledgeGraphId?.let { put("knowledgeGraphId", it) }
                    put("modelProvider", agentToClone.modelProvider)
                    put("modelName", agentToClone.modelName)
                    agentToClone.primarySessionId?.let { put("primarySessionId", it) }
                    put("automaticMode", agentToClone.automaticMode)
                    put("autoWaitTimeSeconds", agentToClone.autoWaitTimeSeconds)
                    put("autoMaxWaitTimeSeconds", agentToClone.autoMaxWaitTimeSeconds)
                }
                store.dispatch(this.name, Action(ActionNames.AGENT_CREATE, createPayload))
            }
            ActionNames.AGENT_TOGGLE_AUTOMATIC_MODE, ActionNames.AGENT_TOGGLE_ACTIVE -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                val agent = agentState.agents[agentId] ?: return
                saveAgentConfig(agent, store)
                touchAgentAvatarCard(agent, store)
            }
            ActionNames.AGENT_UPDATE_CONFIG -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                val oldAgent = (previousState.featureStates[name] as? AgentRuntimeState)?.agents?.get(agentId)
                val newAgent = agentState.agents[agentId] ?: return
                saveAgentConfig(newAgent, store)
                broadcastAgentNames(store)
                if (oldAgent != null && oldAgent.primarySessionId != newAgent.primarySessionId) {
                    updateAgentAvatarCard(agentId, newAgent.status, null, store)
                }
            }
            ActionNames.AGENT_DELETE -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                agentState.agentAvatarCardIds[agentId]?.let { cardInfo ->
                    store.dispatch(this.name, Action(ActionNames.SESSION_DELETE_MESSAGE, buildJsonObject {
                        put("session", cardInfo.sessionId)
                        put("messageId", cardInfo.messageId)
                    }))
                }
                store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_DELETE_DIRECTORY, buildJsonObject { put("subpath", agentId) }))
                store.dispatch(this.name, Action(ActionNames.AGENT_INTERNAL_CONFIRM_DELETE, buildJsonObject { put("agentId", agentId) }))
                store.dispatch(this.name, Action(ActionNames.AGENT_PUBLISH_AGENT_DELETED, buildJsonObject { put("agentId", agentId) }))
                broadcastAgentNames(store)
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
                val sessionId = agent.primarySessionId ?: run {
                    updateAgentAvatarCard(agentId, AgentStatus.ERROR, "Cannot start turn: Agent not subscribed to a session.", store)
                    return
                }
                if (agent.turnMode == TurnMode.DIRECT) {
                    updateAgentAvatarCard(agentId, AgentStatus.PROCESSING, null, store)
                }
                store.dispatch(this.name, Action(ActionNames.AGENT_INTERNAL_SET_PROCESSING_STEP, buildJsonObject {
                    put("agentId", agentId); put("step", "Requesting Ledger")
                }))
                store.dispatch(this.name, Action(ActionNames.SESSION_REQUEST_LEDGER_CONTENT, buildJsonObject {
                    put("sessionId", sessionId); put("correlationId", agentId)
                }))
            }
            ActionNames.AGENT_EXECUTE_PREVIEWED_TURN -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                val agent = agentState.agents[agentId] ?: return
                val previewData = agent.stagedPreviewData ?: return

                updateAgentAvatarCard(agentId, AgentStatus.PROCESSING, null, store)

                store.dispatch(this.name, Action(ActionNames.GATEWAY_GENERATE_CONTENT, buildJsonObject {
                    put("providerId", agent.modelProvider)
                    put("modelName", previewData.agnosticRequest.modelName)
                    put("correlationId", previewData.agnosticRequest.correlationId)
                    put("contents", json.encodeToJsonElement(previewData.agnosticRequest.contents))
                    previewData.agnosticRequest.systemPrompt?.let { put("systemPrompt", it) } // Pass the system prompt
                }))
                store.dispatch(this.name, Action(ActionNames.AGENT_DISCARD_PREVIEW, buildJsonObject { put("agentId", agentId) }))
                store.dispatch("ui.agent", Action(ActionNames.CORE_SHOW_DEFAULT_VIEW))
            }
            ActionNames.AGENT_DISCARD_PREVIEW -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                val agent = agentState.agents[agentId]
                if (agent?.status != AgentStatus.PROCESSING) {
                    store.dispatch(this.name, Action(ActionNames.AGENT_INTERNAL_SET_PROCESSING_STEP, buildJsonObject {
                        put("agentId", agentId); put("step", JsonNull)
                    }))
                }
                store.dispatch("ui.agent", Action(ActionNames.CORE_SHOW_DEFAULT_VIEW))
            }
            ActionNames.AGENT_CANCEL_TURN -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                store.dispatch(this.name, Action(ActionNames.GATEWAY_CANCEL_REQUEST, buildJsonObject {
                    put("correlationId", agentId)
                }))
                activeTurnJobs[agentId]?.cancel()
                activeTurnJobs.remove(agentId)
                updateAgentAvatarCard(agentId, AgentStatus.IDLE, "Turn cancelled by user.", store)
            }
            ActionNames.SESSION_PUBLISH_MESSAGE_POSTED -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<MessagePostedPayload>(it) } ?: return
                if (isAvatarCard(payload)) return
                (store.state.value.featureStates[name] as? AgentRuntimeState)?.agents?.values?.forEach { agent ->
                    if (agent.primarySessionId == payload.sessionId && agent.id != payload.entry["senderId"]?.jsonPrimitive?.content) {
                        if (agent.status == AgentStatus.IDLE) {
                            updateAgentAvatarCard(agent.id, AgentStatus.WAITING, null, store)
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
                            store.dispatch(this.name, Action(ActionNames.AGENT_INITIATE_TURN, buildJsonObject {
                                put("agentId", agent.id)
                                put("preview", false)
                            }))
                        }
                    }
                }
            }
        }
    }

    private fun saveAgentConfig(agent: AgentInstance, store: Store) {
        store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
            put("subpath", "${agent.id}/$agentConfigFILENAME")
            put("content", json.encodeToString(agent))
        }))
    }

    private fun touchAgentAvatarCard(agent: AgentInstance, store: Store) {
        val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
        val cardInfo = agentState.agentAvatarCardIds[agent.id] ?: return
        store.dispatch(this.name, Action(
            name = ActionNames.SESSION_UPDATE_MESSAGE,
            payload = buildJsonObject {
                put("session", cardInfo.sessionId)
                put("messageId", cardInfo.messageId)
            }
        ))
    }

    private fun broadcastAgentNames(store: Store) {
        val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
        val nameMap = agentState.agents.mapValues { it.value.name }
        store.dispatch(this.name, Action(ActionNames.AGENT_PUBLISH_AGENT_NAMES_UPDATED, buildJsonObject {
            put("names", Json.encodeToJsonElement(nameMap))
        }))
    }

    override fun onPrivateData(envelope: PrivateDataEnvelope, store: Store) {
        platformDependencies.log(LogLevel.DEBUG, name, "ON_PRIVATE_DATA << ${envelope.type} from ${envelope.payload["correlationId"]?.jsonPrimitive?.contentOrNull}")
        when (envelope.type) {
            ActionNames.Envelopes.GATEWAY_RESPONSE -> handleGatewayResponse(envelope.payload, store)
            ActionNames.Envelopes.GATEWAY_RESPONSE_PREVIEW -> handleGatewayPreviewResponse(envelope.payload, store)
            ActionNames.Envelopes.SESSION_RESPONSE_LEDGER -> handleSessionLedgerResponse(envelope.payload, store)
            ActionNames.Envelopes.KNOWLEDGEGRAPH_RESPONSE_CONTEXT -> handleKnowledgeGraphContextResponse(envelope.payload, store)
            ActionNames.Envelopes.FILESYSTEM_RESPONSE_LIST -> handleFileSystemListResponse(envelope.payload, store)
            ActionNames.Envelopes.FILESYSTEM_RESPONSE_READ -> handleFileSystemReadResponse(envelope.payload, store)
        }
    }

    private fun assemblePromptAndRequestGeneration(
        agent: AgentInstance,
        ledgerContext: List<GatewayMessage>,
        hkgContext: JsonObject?,
        store: Store
    ) {
        val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
        val hkgContextContent = hkgContext?.entries?.joinToString("\n\n---\n\n") { (holonId, content) ->
            "--- START OF FILE $holonId.json ---\n${content.jsonPrimitive.content}\n--- END OF FILE $holonId.json ---"
        } ?: ""

        val sessionName = agentState.sessionNames[agent.primarySessionId] ?: "Unknown Session"
        var systemPrompt = """
            --- SYSTEM BOOTSTRAP DIRECTIVES ---
            // You are an autonomous agent operating within the multi user and multi agent AUF App.
            // The following directives and context are provided for this turn.

            **OPERATIONAL DIRECTIVES:**
            *   **IDENTITY:** You are agent '${abbreviate(agent.name, 64)}' (ID: ${agent.id}).
            *   **FORMATTING:** Your response MUST be your direct reply only. DO NOT include prefixes (names, IDs, timestamps). The application handles all formatting.
            *   **DISCIPLINE:** You MUST NOT speak for or impersonate any other participant. Generate content only from your own perspective as "${abbreviate(agent.name, 64)}".

            **SITUATIONAL AWARENESS:**
            *   Platform: 'AUF App ${Version.APP_VERSION}'
            *   Host LLM: '${agent.modelProvider}'
            *   Host Model: '${agent.modelName}'
            *   Session: '${abbreviate(sessionName, 64)}'
            *   Request Time: ${platformDependencies.formatIsoTimestamp(platformDependencies.getSystemTimeMillis())}


        """.trimIndent()

        if (hkgContextContent != "") {
            systemPrompt += """
            --- HOLON KNOWLEDGE GRAPH CONTEXT ---
            $hkgContextContent
            
            """.trimIndent()
        }

        val requestActionName = if (agent.turnMode == TurnMode.PREVIEW) ActionNames.GATEWAY_PREPARE_PREVIEW else ActionNames.GATEWAY_GENERATE_CONTENT
        val step = if (agent.turnMode == TurnMode.PREVIEW) "Preparing Preview" else "Generating Content"

        store.dispatch(this.name, Action(ActionNames.AGENT_INTERNAL_SET_PROCESSING_STEP, buildJsonObject {
            put("agentId", agent.id); put("step", step)
        }))

        store.dispatch(this.name, Action(requestActionName, buildJsonObject {
            put("providerId", agent.modelProvider)
            put("modelName", agent.modelName)
            put("correlationId", agent.id)
            put("contents", json.encodeToJsonElement(ledgerContext))
            put("systemPrompt", systemPrompt)
        }))
    }


    private fun handleKnowledgeGraphContextResponse(payload: JsonObject, store: Store) {
        val agentId = payload["correlationId"]?.jsonPrimitive?.contentOrNull ?: return
        val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
        val agent = agentState.agents[agentId] ?: return
        val ledgerContext = agent.stagedTurnContext ?: run {
            platformDependencies.log(LogLevel.ERROR, name, "HKG context received for agent '$agentId', but staged ledger context was missing. Aborting turn.")
            updateAgentAvatarCard(agentId, AgentStatus.ERROR, "Internal Error: Staged context lost.", store)
            return
        }

        val hkgContext = payload["context"]?.jsonObject
        assemblePromptAndRequestGeneration(agent, ledgerContext, hkgContext, store)
    }


    private fun handleGatewayPreviewResponse(payload: JsonObject, store: Store) {
        val decoded = try { json.decodeFromJsonElement<GatewayPreviewResponsePayload>(payload) } catch (e: Exception) { return }
        val agent = (store.state.value.featureStates[name] as? AgentRuntimeState)?.agents?.get(decoded.correlationId) ?: return

        store.dispatch(this.name, Action(ActionNames.AGENT_INTERNAL_SET_PREVIEW_DATA, buildJsonObject {
            put("agentId", agent.id)
            put("agnosticRequest", json.encodeToJsonElement(decoded.agnosticRequest))
            put("rawRequestJson", decoded.rawRequestJson)
        }))
        store.dispatch("ui.agent", Action(ActionNames.CORE_SET_ACTIVE_VIEW, buildJsonObject {
            put("key", "feature.agent.context_viewer")
        }))
    }

    private fun handleSessionLedgerResponse(payload: JsonObject, store: Store) {
        val decoded = try {
            json.decodeFromJsonElement<LedgerResponsePayload>(payload)
        } catch (e: Exception) {
            val agentId = payload["correlationId"]?.jsonPrimitive?.contentOrNull
            val errorMessage = "FATAL: Failed to parse session ledger."
            platformDependencies.log(LogLevel.ERROR, name, "$errorMessage for agent '$agentId'. Error: ${e.message}")
            if (agentId != null) {
                updateAgentAvatarCard(agentId, AgentStatus.ERROR, errorMessage, store)
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
                    senderId == actingAgentId -> agent.name to "model" // This is me, the acting agent.
                    agentState.agents.containsKey(senderId) -> agentState.agents[senderId]!!.name to "user" // This is another agent, treated as a user.
                    user != null -> user.name to "user" // This is a known human user.
                    else -> "Unknown" to "user" // This is an unknown participant, default to user.
                }

                GatewayMessage(role, rawContent, senderId, senderName, timestamp)
            } catch (e: Exception) {
                platformDependencies.log(LogLevel.WARN, name, "Skipping corrupted ledger entry during context assembly: ${e.message}")
                null
            }
        }

        val kgId = agent.knowledgeGraphId
        val kgFeatureExists = store.features.any { it.name == "knowledgegraph" }

        if (kgId != null && kgFeatureExists) {
            // Path 1: HKG is available and requested. Stage the ledger and request the HKG context.
            store.dispatch(this.name, Action(ActionNames.AGENT_INTERNAL_STAGE_TURN_CONTEXT, buildJsonObject {
                put("agentId", agent.id)
                put("messages", json.encodeToJsonElement(enrichedMessages))
            }))
            store.dispatch(this.name, Action(ActionNames.AGENT_INTERNAL_SET_PROCESSING_STEP, buildJsonObject {
                put("agentId", agent.id); put("step", "Requesting HKG")
            }))

            val requestEnvelope = PrivateDataEnvelope(
                type = ActionNames.Envelopes.AGENT_REQUEST_CONTEXT,
                payload = buildJsonObject {
                    put("correlationId", agent.id)
                    put("knowledgeGraphId", kgId)
                }
            )
            store.deliverPrivateData(this.name, "knowledgegraph", requestEnvelope)
        } else {
            // Path 2: HKG is not needed or the feature is absent. Proceed directly.
            if (kgId != null && !kgFeatureExists) {
                platformDependencies.log(LogLevel.WARN, name, "Agent '${agent.id}' has an HKG configured, but KnowledgeGraphFeature not found. Proceeding without HKG context.")
            }
            assemblePromptAndRequestGeneration(agent, enrichedMessages, null, store)
        }
    }

    private fun handleFileSystemListResponse(payload: JsonObject, store: Store) {
        val fileList = payload["listing"]?.jsonArray?.map { json.decodeFromJsonElement<FileEntry>(it) } ?: return
        fileList.forEach { entry ->
            if (entry.isDirectory) {
                store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_READ, buildJsonObject {
                    put("subpath", "${platformDependencies.getFileName(entry.path)}/$agentConfigFILENAME")
                }))
            }
        }
    }

    private fun handleFileSystemReadResponse(payload: JsonObject, store: Store) {
        val content = payload["content"]?.jsonPrimitive?.contentOrNull ?: return
        try {
            val agent = json.decodeFromString<AgentInstance>(content)
            store.dispatch(this.name, Action(ActionNames.AGENT_INTERNAL_AGENT_LOADED, json.encodeToJsonElement(agent) as JsonObject))
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.ERROR, name, "Failed to parse agent config from file: ${payload["subpath"]}. Error: ${e.message}")
        }
    }

    private fun handleGatewayResponse(payload: JsonObject, store: Store) {
        val agentId = payload["correlationId"]?.jsonPrimitive?.contentOrNull
        if (agentId == null || ("rawContent" !in payload && "errorMessage" !in payload)) {
            platformDependencies.log(LogLevel.ERROR, name, "FATAL: Received corrupted gateway response payload for agent '$agentId'. Missing 'rawContent' and 'errorMessage' keys. Payload: $payload")
            if (agentId != null) {
                updateAgentAvatarCard(agentId, AgentStatus.ERROR, "FATAL: Corrupted response from gateway.", store)
            }
            return
        }

        val decoded = try {
            json.decodeFromJsonElement<GatewayResponsePayload>(payload)
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.ERROR, name, "FATAL: Failed to parse gateway response for agent '$agentId'. Error: ${e.message}")
            updateAgentAvatarCard(agentId, AgentStatus.ERROR, "FATAL: Could not parse gateway response.", store)
            return
        }

        val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
        val agent = agentState.agents[decoded.correlationId] ?: return
        val sessionId = agent.primarySessionId ?: return
        if (decoded.errorMessage != null) {
            updateAgentAvatarCard(agent.id, AgentStatus.ERROR, "[AGENT ERROR] Generation failed: ${decoded.errorMessage}", store)
        } else {
            // ADDITION: Sanitize the raw content before posting.
            var contentToPost = decoded.rawContent ?: ""
            val match = redundantHeaderRegex.find(contentToPost)
            if (match != null) {
                // If the header is found, strip it and post a sentinel warning.
                contentToPost = contentToPost.substring(match.range.last + 1).trimStart()
                val warningMessage = """SYSTEM SENTINEL (llm-output-sanitizer): Warning for [${agent.name}]: Please do not include the standard system "name (id) @timestamp:" part in your output. This is added automatically by the application."""
                store.dispatch(this.name, Action(ActionNames.SESSION_POST, buildJsonObject {
                    put("session", sessionId)
                    put("senderId", "system")
                    put("message", warningMessage)
                }))
            }

            store.dispatch(this.name, Action(ActionNames.SESSION_POST, buildJsonObject {
                put("session", sessionId); put("senderId", agent.id); put("message", contentToPost)
            }))
            updateAgentAvatarCard(agent.id, AgentStatus.IDLE, null, store)
        }
    }

    private fun isAvatarCard(payload : MessagePostedPayload) : Boolean {
        val metadata = payload.entry["metadata"]?.jsonObject
        return metadata?.get("render_as_partial")?.jsonPrimitive?.booleanOrNull ?: false
    }

    private fun updateAgentAvatarCard(agentId: String, status: AgentStatus, error: String? = null, store: Store) {
        val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
        val agent = agentState.agents[agentId] ?: return
        val newSessionId = agent.primarySessionId
        agentState.agentAvatarCardIds[agentId]?.let { oldCardInfo ->
            store.dispatch(this.name, Action(ActionNames.SESSION_DELETE_MESSAGE, buildJsonObject {
                put("session", oldCardInfo.sessionId); put("messageId", oldCardInfo.messageId)
            }))
        }
        store.dispatch(this.name, Action(ActionNames.AGENT_INTERNAL_SET_STATUS, buildJsonObject {
            put("agentId", agentId); put("status", status.name); error?.let { put("error", it) }
        }))
        if (newSessionId == null) return
        val latestAgentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
        val latestAgent = latestAgentState.agents[agentId] ?: return
        val afterMessageId = when (status) {
            AgentStatus.PROCESSING -> latestAgent.processingFrontierMessageId
            else -> latestAgent.lastSeenMessageId
        }
        val messageId = platformDependencies.generateUUID()
        val metadata = buildJsonObject {
            put("render_as_partial", true); put("is_transient", true); put("agentStatus", status.name)
            error?.let { put("errorMessage", it) }
        }
        store.dispatch(this.name, Action(ActionNames.SESSION_POST, buildJsonObject {
            put("session", newSessionId); put("senderId", agentId); put("messageId", messageId)
            put("metadata", metadata); afterMessageId?.let { put("afterMessageId", it) }
        }))
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
