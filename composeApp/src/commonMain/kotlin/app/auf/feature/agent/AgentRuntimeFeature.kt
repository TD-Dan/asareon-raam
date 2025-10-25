package app.auf.feature.agent

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
    @Serializable private data class GatewayResponsePayload(val correlationId: String, val rawContent: String? = null, val errorMessage: String? = null)
    @Serializable private data class LedgerResponsePayload(val correlationId: String, val messages: List<GatewayMessage>)
    @Serializable private data class GatewayMessage(val role: String, val content: String)
    @Serializable private data class MessagePostedPayload(val sessionId: String, val entry: JsonObject)
    @Serializable private data class MessageDeletedPayload(val sessionId: String, val messageId: String)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val agentConfigFILENAME = "agent.json"
    private val activeTurnJobs = mutableMapOf<String, Job>()

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
            ActionNames.AGENT_INTERNAL_AGENT_LOADED -> handleAgentLoaded(action, currentFeatureState)?.let { newFeatureState = it }
            ActionNames.SESSION_PUBLISH_MESSAGE_POSTED -> handleMessagePosted(action, currentFeatureState)?.let { newFeatureState = it }
            ActionNames.SESSION_PUBLISH_MESSAGE_DELETED -> handleMessageDeleted(action, currentFeatureState)?.let { newFeatureState = it }
            ActionNames.SESSION_PUBLISH_SESSION_DELETED -> handleSessionDeleted(action, currentFeatureState)?.let { newFeatureState = it }
            ActionNames.AGENT_TRIGGER_MANUAL_TURN -> handleTriggerTurn(action, currentFeatureState)?.let { newFeatureState = it }
            ActionNames.GATEWAY_PUBLISH_AVAILABLE_MODELS_UPDATED -> {
                val decodedModels: Map<String, List<String>>? = try { action.payload?.let { json.decodeFromJsonElement(it) } } catch (e: Exception) { null }
                newFeatureState = currentFeatureState.copy(availableModels = decodedModels ?: emptyMap())
            }
            ActionNames.SESSION_PUBLISH_SESSION_NAMES_UPDATED -> {
                val decoded = try { action.payload?.let { json.decodeFromJsonElement<SessionNamesPayload>(it) } } catch(e: Exception) { null }
                if (decoded != null) { newFeatureState = currentFeatureState.copy(sessionNames = decoded.names) }
            }
        }

        return newFeatureState?.let {
            val finalState = if (action.name != ActionNames.SESSION_PUBLISH_SESSION_DELETED) it.copy(agentsToPersist = null) else it
            if (finalState != currentFeatureState) stateWithFeature.copy(featureStates = stateWithFeature.featureStates + (name to finalState)) else stateWithFeature
        } ?: stateWithFeature
    }

    private fun handleCreateAgent(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState? {
        val payload = action.payload ?: return null
        val newAgent = AgentInstance(
            id = platformDependencies.generateUUID(),
            name = payload["name"]?.jsonPrimitive?.contentOrNull ?: "New Agent",
            personaId = payload["personaId"]?.jsonPrimitive?.contentOrNull ?: "keel-20250914T142800Z",
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
            personaId = payload["personaId"]?.jsonPrimitive?.contentOrNull ?: agentToUpdate.personaId,
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
        val newStatus = try { AgentStatus.valueOf(newStatusString) } catch (e: Exception) { return null }
        val newErrorMessage = if (newStatus == AgentStatus.ERROR) payload["error"]?.jsonPrimitive?.contentOrNull else null
        val clearTimers = agentToUpdate.status == AgentStatus.WAITING && newStatus != AgentStatus.WAITING
        val isStartingProcessing = newStatus == AgentStatus.PROCESSING && agentToUpdate.status != AgentStatus.PROCESSING
        val isStoppingProcessing = newStatus != AgentStatus.PROCESSING && agentToUpdate.status == AgentStatus.PROCESSING
        val updatedAgent = agentToUpdate.copy(
            status = newStatus,
            errorMessage = newErrorMessage,
            waitingSinceTimestamp = if (clearTimers) null else agentToUpdate.waitingSinceTimestamp,
            lastMessageReceivedTimestamp = if (clearTimers) null else agentToUpdate.lastMessageReceivedTimestamp,
            processingSinceTimestamp = if (isStartingProcessing) platformDependencies.getSystemTimeMillis() else if (isStoppingProcessing) null else agentToUpdate.processingSinceTimestamp,
            processingStep = if (isStoppingProcessing) null else agentToUpdate.processingStep
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
            } else if (agent.id == senderId) {
                if (isAvatarCard(payload)) agent else agent.copy(processingFrontierMessageId = messageId)
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

    private fun handleTriggerTurn(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState? {
        val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return null
        val agent = currentFeatureState.agents[agentId] ?: return null
        if (agent.status == AgentStatus.PROCESSING) return null
        val updatedAgent = agent.copy(processingFrontierMessageId = agent.lastSeenMessageId)
        return currentFeatureState.copy(agents = currentFeatureState.agents + (agentId to updatedAgent))
    }

    override fun onAction(action: Action, store: Store, previousState: AppState) {
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
            ActionNames.AGENT_CREATE, ActionNames.AGENT_TOGGLE_AUTOMATIC_MODE, ActionNames.AGENT_TOGGLE_ACTIVE -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull
                val agentToSave = if (agentId != null) agentState.agents[agentId] else agentState.agents.values.lastOrNull()
                agentToSave?.let { saveAgentConfig(it, store) }
                if (action.name == ActionNames.AGENT_CREATE) broadcastAgentNames(store)
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
            ActionNames.AGENT_CANCEL_TURN -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                store.dispatch(this.name, Action(ActionNames.GATEWAY_CANCEL_REQUEST, buildJsonObject {
                    put("correlationId", agentId)
                }))
                activeTurnJobs[agentId]?.cancel()
                activeTurnJobs.remove(agentId)
                updateAgentAvatarCard(agentId, AgentStatus.IDLE, "Turn cancelled by user.", store)
            }
            ActionNames.AGENT_TRIGGER_MANUAL_TURN -> beginCognitiveCycle(action, store)
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
                            store.dispatch(this.name, Action(ActionNames.AGENT_TRIGGER_MANUAL_TURN, buildJsonObject { put("agentId", agent.id) }))
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

    private fun broadcastAgentNames(store: Store) {
        val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
        val nameMap = agentState.agents.mapValues { it.value.name }
        store.dispatch(this.name, Action(ActionNames.AGENT_PUBLISH_AGENT_NAMES_UPDATED, buildJsonObject {
            put("names", Json.encodeToJsonElement(nameMap))
        }))
    }

    private fun beginCognitiveCycle(action: Action, store: Store) {
        val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
        val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
        val agent = agentState.agents[agentId] ?: return
        if (agent.status == AgentStatus.PROCESSING || !agent.isAgentActive) return
        val sessionId = agent.primarySessionId ?: run {
            updateAgentAvatarCard(agentId, AgentStatus.ERROR, "Cannot start turn: Agent not subscribed to a session.", store)
            return
        }
        updateAgentAvatarCard(agentId, AgentStatus.PROCESSING, null, store)
        store.dispatch(this.name, Action(ActionNames.AGENT_INTERNAL_SET_PROCESSING_STEP, buildJsonObject {
            put("agentId", agentId); put("step", "Requesting Ledger")
        }))
        store.dispatch(this.name, Action(ActionNames.SESSION_REQUEST_LEDGER_CONTENT, buildJsonObject {
            put("sessionId", sessionId); put("correlationId", agentId)
        }))
    }

    override fun onPrivateData(envelope: PrivateDataEnvelope, store: Store) {
        when (envelope.type) {
            ActionNames.Envelopes.GATEWAY_RESPONSE -> handleGatewayResponse(envelope.payload, store)
            ActionNames.Envelopes.SESSION_RESPONSE_LEDGER -> handleSessionLedgerResponse(envelope.payload, store)
            "filesystem.response.list" -> handleFileSystemListResponse(envelope.payload, store)
            "filesystem.response.read" -> handleFileSystemReadResponse(envelope.payload, store)
        }
    }

    private fun handleSessionLedgerResponse(payload: JsonObject, store: Store) {
        val decoded = try { json.decodeFromJsonElement<LedgerResponsePayload>(payload) } catch (e: Exception) { return }
        val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
        val agent = agentState.agents[decoded.correlationId] ?: return
        store.dispatch(this.name, Action(ActionNames.AGENT_INTERNAL_SET_PROCESSING_STEP, buildJsonObject {
            put("agentId", agent.id); put("step", "Generating Content")
        }))
        store.dispatch(this.name, Action(ActionNames.GATEWAY_GENERATE_CONTENT, buildJsonObject {
            put("providerId", agent.modelProvider); put("modelName", agent.modelName); put("correlationId", agent.id)
            put("contents", json.encodeToJsonElement(decoded.messages))
        }))
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
        val decoded = try { json.decodeFromJsonElement<GatewayResponsePayload>(payload) } catch (e: Exception) { return }
        val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
        val agent = agentState.agents[decoded.correlationId] ?: return
        val sessionId = agent.primarySessionId ?: return
        if (decoded.errorMessage != null) {
            updateAgentAvatarCard(agent.id, AgentStatus.ERROR, "[AGENT ERROR] Generation failed: ${decoded.errorMessage}", store)
        } else {
            store.dispatch(this.name, Action(ActionNames.SESSION_POST, buildJsonObject {
                put("session", sessionId); put("senderId", agent.id); put("message", decoded.rawContent ?: "")
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
            mapOf("feature.agent.manager" to { store, _, -> AgentManagerView(store, platformDependencies) })
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
            val state = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
            val agent = state.agents[agentId] ?: return
            AgentAvatarCard(agent = agent, store = store, platformDependencies = platformDependencies)
        }
    }
}