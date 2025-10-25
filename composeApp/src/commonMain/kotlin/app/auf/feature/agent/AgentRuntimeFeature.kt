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

    // --- Private, serializable data classes for decoding action payloads safely. ---
    @Serializable private data class SessionNamesPayload(val names: Map<String, String>)
    @Serializable private data class GatewayResponsePayload(val correlationId: String, val rawContent: String? = null, val errorMessage: String? = null)
    @Serializable private data class LedgerResponsePayload(val correlationId: String, val messages: List<GatewayMessage>)
    @Serializable private data class GatewayMessage(val role: String, val content: String)
    @Serializable private data class MessagePostedPayload(val sessionId: String, val entry: JsonObject)
    @Serializable private data class MessageDeletedPayload(val sessionId: String, val messageId: String)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val agentConfigFILENAME = "agent.json"
    private val activeTurnJobs = mutableMapOf<String, Job>()

    // --- LIFECYCLE ---

    override fun init(store: Store) {
        // Start the ticker coroutine to check for automatic triggers.
        coroutineScope.launch {
            while (true) {
                delay(1000) // Check every second
                // Only dispatch the check action if there's an automatic agent that could possibly be triggered.
                // This prevents constant log spam when all agents are idle.
                val currentState = store.state.value.featureStates[name] as? AgentRuntimeState
                if (currentState?.agents?.values?.any { it.status == AgentStatus.WAITING && it.automaticMode } == true) {
                    store.dispatch(name, Action(ActionNames.AGENT_INTERNAL_CHECK_AUTOMATIC_TRIGGERS))
                }
            }
        }
    }

    // --- REDUCER (Pure State Logic) ---

    override fun reducer(state: AppState, action: Action): AppState {
        val (stateWithFeature, currentFeatureState) = state.featureStates[name]
            ?.let { state to (it as AgentRuntimeState) }
            ?: (state.copy(featureStates = state.featureStates + (name to AgentRuntimeState())) to AgentRuntimeState())

        var newFeatureState: AgentRuntimeState? = null
        val payload = action.payload

        when (action.name) {
            ActionNames.AGENT_CREATE -> handleCreateAgent(action, currentFeatureState)?.let { newFeatureState = it }
            ActionNames.AGENT_UPDATE_CONFIG -> handleUpdateConfig(action, currentFeatureState)?.let { newFeatureState = it }
            ActionNames.AGENT_TOGGLE_AUTOMATIC_MODE -> handleToggleAutomaticMode(action, currentFeatureState)?.let { newFeatureState = it }
            ActionNames.AGENT_SET_EDITING -> handleSetEditing(action, currentFeatureState)?.let { newFeatureState = it }
            ActionNames.AGENT_INTERNAL_CONFIRM_DELETE -> handleDeleteAgent(action, currentFeatureState)?.let { newFeatureState = it }
            ActionNames.AGENT_INTERNAL_SET_STATUS -> handleSetStatus(action, currentFeatureState)?.let { newFeatureState = it }
            ActionNames.AGENT_INTERNAL_AGENT_LOADED -> handleAgentLoaded(action, currentFeatureState)?.let { newFeatureState = it }
            ActionNames.SESSION_PUBLISH_MESSAGE_POSTED -> handleMessagePosted(action, currentFeatureState)?.let { newFeatureState = it }
            ActionNames.SESSION_PUBLISH_MESSAGE_DELETED -> handleMessageDeleted(action, currentFeatureState)?.let { newFeatureState = it }
            ActionNames.SESSION_PUBLISH_SESSION_DELETED -> handleSessionDeleted(action, currentFeatureState)?.let { newFeatureState = it }
            ActionNames.AGENT_TRIGGER_MANUAL_TURN -> handleTriggerTurn(action, currentFeatureState)?.let { newFeatureState = it }

            ActionNames.GATEWAY_PUBLISH_AVAILABLE_MODELS_UPDATED -> {
                val decodedModels: Map<String, List<String>>? = try {
                    payload?.let { json.decodeFromJsonElement(it) }
                } catch (e: Exception) {
                    platformDependencies.log(LogLevel.WARN, name, "Failed to parse available models payload: ${e.message}")
                    null
                }
                newFeatureState = currentFeatureState.copy(availableModels = decodedModels ?: emptyMap())
            }
            ActionNames.SESSION_PUBLISH_SESSION_NAMES_UPDATED -> {
                val decoded = try {
                    payload?.let { json.decodeFromJsonElement<SessionNamesPayload>(it) }
                } catch(e: Exception) {
                    platformDependencies.log(LogLevel.WARN, name, "Failed to parse session names payload: ${e.message}")
                    null
                }
                if (decoded != null) {
                    newFeatureState = currentFeatureState.copy(sessionNames = decoded.names)
                }
            }
        }

        return newFeatureState?.let {
            val finalState = if (action.name != ActionNames.SESSION_PUBLISH_SESSION_DELETED) it.copy(agentsToPersist = null) else it
            if (finalState != currentFeatureState) stateWithFeature.copy(featureStates = stateWithFeature.featureStates + (name to finalState)) else stateWithFeature
        } ?: stateWithFeature
    }

    private fun handleCreateAgent(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState? {
        val payload = action.payload ?: return null
        val agentName = payload["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val personaId = payload["personaId"]?.jsonPrimitive?.contentOrNull ?: "keel-20250914T142800Z"
        val modelProvider = payload["modelProvider"]?.jsonPrimitive?.contentOrNull ?: "gemini"
        val modelName = payload["modelName"]?.jsonPrimitive?.contentOrNull ?: "gemini-pro"
        val primarySessionId = payload["primarySessionId"]?.jsonPrimitive?.contentOrNull
        val automaticMode = payload["automaticMode"]?.jsonPrimitive?.booleanOrNull ?: false
        val autoWaitTimeSeconds = payload["autoWaitTimeSeconds"]?.jsonPrimitive?.intOrNull ?: 5
        val autoMaxWaitTimeSeconds = payload["autoMaxWaitTimeSeconds"]?.jsonPrimitive?.intOrNull ?: 30
        val newAgentId = platformDependencies.generateUUID()
        val newAgent = AgentInstance(
            id = newAgentId, name = agentName, personaId = personaId,
            modelProvider = modelProvider, modelName = modelName, primarySessionId = primarySessionId,
            automaticMode = automaticMode,
            autoWaitTimeSeconds = autoWaitTimeSeconds,
            autoMaxWaitTimeSeconds = autoMaxWaitTimeSeconds
        )
        return currentFeatureState.copy(agents = currentFeatureState.agents + (newAgentId to newAgent), editingAgentId = newAgentId)
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

    private fun handleSessionDeleted(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState? {
        val deletedSessionId = action.payload?.get("sessionId")?.jsonPrimitive?.contentOrNull ?: return null
        val affectedAgentIds = currentFeatureState.agents.values
            .filter { it.primarySessionId == deletedSessionId }
            .map { it.id }
            .toSet()

        if (affectedAgentIds.isEmpty()) return null

        val newAgents = currentFeatureState.agents.mapValues { (_, agent) ->
            if (agent.id in affectedAgentIds) agent.copy(primarySessionId = null) else agent
        }
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

        // When transitioning out of WAITING, clear the timers.
        val clearTimers = agentToUpdate.status == AgentStatus.WAITING && newStatus != AgentStatus.WAITING
        val updatedAgent = agentToUpdate.copy(
            status = newStatus,
            errorMessage = newErrorMessage,
            waitingSinceTimestamp = if (clearTimers) null else agentToUpdate.waitingSinceTimestamp,
            lastMessageReceivedTimestamp = if (clearTimers) null else agentToUpdate.lastMessageReceivedTimestamp
        )

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
        val entry = payload.entry
        val messageId = entry["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val sessionId = payload.sessionId
        val senderId = entry["senderId"]?.jsonPrimitive?.contentOrNull
        val currentTime = platformDependencies.getSystemTimeMillis()

        // Unconditionally update the awareness frontier for all subscribed agents that are not the sender.
        val affectedAgents = currentFeatureState.agents.mapValues { (_, agent) ->
            if (agent.primarySessionId == sessionId && agent.id != senderId) {
                agent.copy(
                    lastSeenMessageId = messageId,
                    lastMessageReceivedTimestamp = currentTime,
                    // If the agent was IDLE, this is the moment it starts waiting.
                    waitingSinceTimestamp = agent.waitingSinceTimestamp ?: currentTime
                )
            } else if (agent.id == senderId) {
                // The agent's own message becomes its new commitment frontier.
                agent.copy(processingFrontierMessageId = messageId)
            } else {
                agent
            }
        }

        // Check if the message was an avatar card being posted, and if so, record its ID.
        if (isAvatarCard(payload)) {
            val agentId = entry["senderId"]?.jsonPrimitive?.contentOrNull
            if (agentId != null && currentFeatureState.agents.containsKey(agentId)) {
                val newCardInfo = AgentRuntimeState.AvatarCardInfo(messageId = messageId, sessionId = sessionId)
                return currentFeatureState.copy(
                    agents = affectedAgents,
                    agentAvatarCardIds = currentFeatureState.agentAvatarCardIds + (agentId to newCardInfo)
                )
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

        // Atomically copy the awareness frontier to the commitment frontier.
        val updatedAgent = agent.copy(processingFrontierMessageId = agent.lastSeenMessageId)
        return currentFeatureState.copy(agents = currentFeatureState.agents + (agentId to updatedAgent))
    }

    // --- ON_ACTION (Side Effect Orchestration) ---

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
            ActionNames.AGENT_CREATE -> {
                val latestState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
                val agentToSave = latestState.agents.values.lastOrNull() ?: return
                store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
                    put("subpath", "${agentToSave.id}/$agentConfigFILENAME")
                    put("content", json.encodeToString(agentToSave))
                }))
                broadcastAgentNames(store)
            }
            ActionNames.AGENT_UPDATE_CONFIG -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                // REFACTOR: Correctly use previousState to get the true old agent state.
                val oldAgentState = previousState.featureStates[name] as? AgentRuntimeState
                val oldAgent = oldAgentState?.agents?.get(agentId)
                val newAgent = agentState.agents[agentId] ?: return

                // Persist the change
                store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
                    put("subpath", "${newAgent.id}/$agentConfigFILENAME")
                    put("content", json.encodeToString(newAgent))
                }))
                broadcastAgentNames(store)

                // Orchestrate the "move" of the avatar card if the session changed.
                if (oldAgent != null && oldAgent.primarySessionId != newAgent.primarySessionId) {
                    platformDependencies.log(LogLevel.DEBUG, "agent.onAction.AGENT_UPDATE_CONFIG", "Moving card from session '${oldAgent.primarySessionId}' to '${newAgent.primarySessionId}'.")
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
                    val agentToPersist = agentState.agents[agentId]
                    if (agentToPersist != null) {
                        store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
                            put("subpath", "${agentToPersist.id}/$agentConfigFILENAME")
                            put("content", json.encodeToString(agentToPersist))
                        }))
                    }
                }
            }
            ActionNames.AGENT_CANCEL_TURN -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                // REFACTOR: Dispatch cancellation to the Gateway in addition to local cancellation.
                store.dispatch(this.name, Action(ActionNames.GATEWAY_CANCEL_REQUEST, buildJsonObject {
                    put("correlationId", agentId)
                }))
                // Local job management remains for UI responsiveness
                activeTurnJobs[agentId]?.cancel()
                activeTurnJobs.remove(agentId)
                updateAgentAvatarCard(agentId, AgentStatus.IDLE, "Turn cancelled by user.", store)
            }
            ActionNames.AGENT_TRIGGER_MANUAL_TURN -> beginCognitiveCycle(action, store)

            ActionNames.SESSION_PUBLISH_MESSAGE_POSTED -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<MessagePostedPayload>(it) } ?: return
                val latestState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return

                if (isAvatarCard(payload)) return

                latestState.agents.values.forEach { agent ->
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
                    if (agent.automaticMode && agent.status == AgentStatus.WAITING && agent.waitingSinceTimestamp != null && agent.lastMessageReceivedTimestamp != null) {
                        val waitedFor = (currentTime - agent.lastMessageReceivedTimestamp) / 1000
                        val totalWait = (currentTime - agent.waitingSinceTimestamp) / 1000

                        val debounceTrigger = waitedFor >= agent.autoWaitTimeSeconds
                        val timeoutTrigger = totalWait >= agent.autoMaxWaitTimeSeconds

                        if (debounceTrigger || timeoutTrigger) {
                            platformDependencies.log(LogLevel.DEBUG, name, "Agent '${agent.name}' automatically triggered. Reason: ${if (debounceTrigger) "debounce" else "timeout"}")
                            store.dispatch(this.name, Action(ActionNames.AGENT_TRIGGER_MANUAL_TURN, buildJsonObject {
                                put("agentId", agent.id)
                            }))
                        }
                    }
                }
            }
        }
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

        // REFACTOR: Add guard to prevent re-firing a processing agent.
        if (agent.status == AgentStatus.PROCESSING) {
            platformDependencies.log(LogLevel.WARN, name, "Agent '$agentId' is already PROCESSING. Ignoring duplicate TRIGGER_MANUAL_TURN request.")
            return
        }

        val sessionId = agent.primarySessionId ?: run {
            updateAgentAvatarCard(agentId, AgentStatus.ERROR, "Cannot start turn: Agent not subscribed to a session.", store)
            return
        }
        updateAgentAvatarCard(agentId, AgentStatus.PROCESSING, null, store)

        store.dispatch(this.name, Action(ActionNames.SESSION_REQUEST_LEDGER_CONTENT, buildJsonObject {
            put("sessionId", sessionId)
            put("correlationId", agentId)
        }))
    }

    override fun onPrivateData(envelope: PrivateDataEnvelope, store: Store) {
        when (envelope.type) {
            ActionNames.Envelopes.GATEWAY_RESPONSE -> handleGatewayResponse(envelope.payload, store)
            ActionNames.Envelopes.SESSION_RESPONSE_LEDGER -> handleSessionLedgerResponse(envelope.payload, store)
            "filesystem.response.list" -> handleFileSystemListResponse(envelope.payload, store)
            "filesystem.response.read" -> handleFileSystemReadResponse(envelope.payload, store)
            else -> platformDependencies.log(LogLevel.WARN, name, "Received private data envelope with unknown type: '${envelope.type}'. Ignoring.")
        }
    }

    private fun handleSessionLedgerResponse(payload: JsonObject, store: Store) {
        val decoded = try { json.decodeFromJsonElement<LedgerResponsePayload>(payload) } catch (e: Exception) {
            val agentId = payload["correlationId"]?.jsonPrimitive?.contentOrNull
            platformDependencies.log(LogLevel.ERROR, name, "FATAL: Failed to parse session ledger for agent '$agentId'. Error: ${e.message}")
            if (agentId != null) {
                updateAgentAvatarCard(agentId, AgentStatus.ERROR, "FATAL: Could not parse session ledger.", store)
            }
            return
        }
        val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
        val agent = agentState.agents[decoded.correlationId] ?: return
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
        val agentId = payload["correlationId"]?.jsonPrimitive?.contentOrNull
        if (agentId == null) { platformDependencies.log(LogLevel.ERROR, name, "Received gateway response with no correlationId."); return }
        if ("rawContent" !in payload && "errorMessage" !in payload) {
            platformDependencies.log(LogLevel.ERROR, name, "FATAL: Received corrupted gateway response payload for agent '$agentId'. Missing 'rawContent' and 'errorMessage' keys. Payload: $payload")
            updateAgentAvatarCard(agentId, AgentStatus.ERROR, "FATAL: Corrupted response from gateway.", store)
            return
        }
        val decoded = try { json.decodeFromJsonElement<GatewayResponsePayload>(payload) } catch (e: Exception) {
            platformDependencies.log(LogLevel.ERROR, name, "FATAL: Failed to parse gateway response for agent '$agentId'. Error: ${e.message}")
            updateAgentAvatarCard(agentId, AgentStatus.ERROR, "FATAL: Could not parse gateway response.", store)
            return
        }
        val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
        val agent = agentState.agents[agentId] ?: return
        val sessionId = agent.primarySessionId ?: return

        if (decoded.errorMessage != null) {
            platformDependencies.log(LogLevel.ERROR, name, "Gateway reported an error for agent '$agentId': ${decoded.errorMessage}")
            updateAgentAvatarCard(agentId, AgentStatus.ERROR, "[AGENT ERROR] Generation failed: ${decoded.errorMessage}", store)
        } else {
            // REFACTOR: Remove guard. Post message even if rawContent is null or blank (e.g., a "STOP" reason).
            store.dispatch(this.name, Action(ActionNames.SESSION_POST, buildJsonObject {
                put("session", sessionId); put("senderId", agent.id); put("message", decoded.rawContent ?: "")
            }))
            updateAgentAvatarCard(agentId, AgentStatus.IDLE, null, store)
        }
    }

    // Helper to check if a message is an Avatar Card
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
                put("session", oldCardInfo.sessionId)
                put("messageId", oldCardInfo.messageId)
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
            mapOf("feature.agent.manager" to { store, _, -> AgentManagerView(store) })
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
            AgentAvatarCard(
                agentName = agent.name, agentStatus = agent.status, errorMessage = agent.errorMessage,
                onTrigger = { store.dispatch("ui.avatar.$agentId", Action(ActionNames.AGENT_TRIGGER_MANUAL_TURN, buildJsonObject { put("agentId", agent.id) })) },
                onCancel = { store.dispatch("ui.avatar.$agentId", Action(ActionNames.AGENT_CANCEL_TURN, buildJsonObject { put("agentId", agent.id) })) },
                canTrigger = (agent.status == AgentStatus.IDLE || agent.status == AgentStatus.WAITING || agent.status == AgentStatus.ERROR) && agent.primarySessionId != null
            )
        }
    }
}