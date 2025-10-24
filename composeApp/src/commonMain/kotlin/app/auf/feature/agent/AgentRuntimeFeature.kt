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
        val newAgentId = platformDependencies.generateUUID()
        val newAgent = AgentInstance(id = newAgentId, name = agentName, personaId = personaId, modelProvider = modelProvider, modelName = modelName, primarySessionId = primarySessionId, automaticMode = automaticMode)
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
            automaticMode = payload["automaticMode"]?.jsonPrimitive?.booleanOrNull ?: agentToUpdate.automaticMode
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
        val updatedAgent = agentToUpdate.copy(status = newStatus, errorMessage = newErrorMessage)
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

        // Unconditionally update the awareness frontier for all subscribed agents.
        val affectedAgents = currentFeatureState.agents.mapValues { (_, agent) ->
            if (agent.primarySessionId == payload.sessionId) agent.copy(lastSeenMessageId = messageId) else agent
        }

        // Check if the message was an avatar card being posted, and if so, record its ID.
        val metadata = entry["metadata"]?.jsonObject
        val isAvatarCard = metadata?.get("render_as_partial")?.jsonPrimitive?.booleanOrNull ?: false
        if (isAvatarCard) {
            val agentId = entry["senderId"]?.jsonPrimitive?.contentOrNull
            if (agentId != null && currentFeatureState.agents.containsKey(agentId)) {
                return currentFeatureState.copy(
                    agents = affectedAgents,
                    agentAvatarCardIds = currentFeatureState.agentAvatarCardIds + (agentId to messageId)
                )
            }
        }
        return currentFeatureState.copy(agents = affectedAgents)
    }

    private fun handleMessageDeleted(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState? {
        val payload = action.payload?.let { json.decodeFromJsonElement<MessageDeletedPayload>(it) } ?: return null
        val agentId = currentFeatureState.agentAvatarCardIds.entries.find { it.value == payload.messageId }?.key ?: return null
        return currentFeatureState.copy(agentAvatarCardIds = currentFeatureState.agentAvatarCardIds - agentId)
    }

    private fun handleTriggerTurn(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState? {
        val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return null
        val agent = currentFeatureState.agents[agentId] ?: return null
        // THE FIX: Allow triggering from WAITING state. Only block if already PROCESSING.
        if (agent.status == AgentStatus.PROCESSING) return null // Guard against re-triggering

        // Atomically copy the awareness frontier to the commitment frontier.
        val updatedAgent = agent.copy(processingFrontierMessageId = agent.lastSeenMessageId)
        return currentFeatureState.copy(agents = currentFeatureState.agents + (agentId to updatedAgent))
    }

    // --- ON_ACTION (Side Effect Orchestration) ---

    override fun onAction(action: Action, store: Store) {
        val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
        when (action.name) {
            ActionNames.SYSTEM_PUBLISH_STARTING -> {
                store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_LIST))
                store.dispatch(this.name, Action(ActionNames.GATEWAY_REQUEST_AVAILABLE_MODELS))
            }
            ActionNames.AGENT_INTERNAL_AGENT_LOADED -> {
                val agent = action.payload?.let { json.decodeFromJsonElement<AgentInstance>(it) } ?: return
                if (agent.primarySessionId != null) {
                    setAgentStatus(agent.id, AgentStatus.IDLE, store)
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
            ActionNames.AGENT_UPDATE_CONFIG, ActionNames.AGENT_TOGGLE_AUTOMATIC_MODE -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: agentState.agents.keys.lastOrNull() ?: return
                val oldAgent = agentState.agents[agentId]
                val latestState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
                val newAgent = latestState.agents[agentId] ?: return

                store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
                    put("subpath", "${newAgent.id}/$agentConfigFILENAME")
                    put("content", json.encodeToString(newAgent))
                }))
                broadcastAgentNames(store)

                if (oldAgent != null && oldAgent.primarySessionId != newAgent.primarySessionId) {
                    oldAgent.primarySessionId?.let {
                        agentState.agentAvatarCardIds[agentId]?.let { messageId ->
                            store.dispatch(this.name, Action(ActionNames.SESSION_DELETE_MESSAGE, buildJsonObject {
                                put("session", oldAgent.primarySessionId)
                                put("messageId", messageId)
                            }))
                        }
                    }
                    newAgent.primarySessionId?.let {
                        setAgentStatus(agentId, AgentStatus.IDLE, store)
                    }
                }
            }
            ActionNames.AGENT_DELETE -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                val agentToDelete = agentState.agents[agentId] ?: return

                agentState.agentAvatarCardIds[agentId]?.let { messageId ->
                    agentToDelete.primarySessionId?.let { sessionId ->
                        store.dispatch(this.name, Action(ActionNames.SESSION_DELETE_MESSAGE, buildJsonObject {
                            put("session", sessionId)
                            put("messageId", messageId)
                        }))
                    }
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
                val job = activeTurnJobs[agentId] ?: return
                job.cancel()
                activeTurnJobs.remove(agentId)
                setAgentStatus(agentId, AgentStatus.IDLE, store, "Turn cancelled by user.")
            }
            ActionNames.AGENT_TRIGGER_MANUAL_TURN -> beginCognitiveCycle(action, store)
            ActionNames.SESSION_PUBLISH_MESSAGE_POSTED -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<MessagePostedPayload>(it) } ?: return
                val latestState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
                latestState.agents.values.forEach { agent ->
                    if (agent.primarySessionId == payload.sessionId && agent.id != payload.entry["senderId"]?.jsonPrimitive?.contentOrNull) {
                        if (agent.status == AgentStatus.IDLE || agent.status == AgentStatus.WAITING) {
                            setAgentStatus(agent.id, AgentStatus.WAITING, store)
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

        val sessionId = agent.primarySessionId ?: run {
            setAgentStatus(agentId, AgentStatus.ERROR, store, "Cannot start turn: Agent not subscribed to a session."); return
        }

        setAgentStatus(agentId, AgentStatus.PROCESSING, store)
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
            else -> {
                platformDependencies.log(
                    LogLevel.WARN,
                    name,
                    "Received private data envelope with unknown type: '${envelope.type}'. Ignoring."
                )
            }
        }
    }

    private fun handleSessionLedgerResponse(payload: JsonObject, store: Store) {
        val decoded = try {
            json.decodeFromJsonElement<LedgerResponsePayload>(payload)
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.ERROR, name, "FATAL: Failed to parse session ledger. Error: ${e.message}")
            val agentId = payload["correlationId"]?.jsonPrimitive?.contentOrNull
            if (agentId != null) {
                setAgentStatus(agentId, AgentStatus.ERROR, store, "FATAL: Could not parse session ledger.")
            }
            return
        }

        val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
        val agent = agentState.agents[decoded.correlationId] ?: return

        store.dispatch(this.name, Action(ActionNames.GATEWAY_GENERATE_CONTENT, buildJsonObject {
            put("providerId", agent.modelProvider)
            put("modelName", agent.modelName)
            put("correlationId", agent.id)
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
        if (agentId == null) {
            platformDependencies.log(LogLevel.ERROR, name, "Received gateway response with no correlationId.")
            return
        }

        if ("rawContent" !in payload && "errorMessage" !in payload) {
            platformDependencies.log(
                LogLevel.ERROR,
                name,
                "FATAL: Received corrupted gateway response payload for agent '$agentId'. Missing 'rawContent' and 'errorMessage' keys. Payload: $payload"
            )
            setAgentStatus(agentId, AgentStatus.ERROR, store, "FATAL: Corrupted response from gateway.")
            return
        }

        val decoded = try {
            json.decodeFromJsonElement<GatewayResponsePayload>(payload)
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.ERROR, name, "FATAL: Failed to parse gateway response for agent '$agentId'. Error: ${e.message}")
            setAgentStatus(agentId, AgentStatus.ERROR, store, "FATAL: Could not parse gateway response.")
            return
        }

        val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
        val agent = agentState.agents[agentId] ?: return
        val sessionId = agent.primarySessionId ?: return

        if (decoded.errorMessage != null) {
            platformDependencies.log(LogLevel.ERROR, name, "Gateway reported an error for agent '$agentId': ${decoded.errorMessage}")
            setAgentStatus(agentId, AgentStatus.ERROR, store, "[AGENT ERROR] Generation failed: ${decoded.errorMessage}")
        } else {
            if (!decoded.rawContent.isNullOrBlank()) {
                store.dispatch(this.name, Action(ActionNames.SESSION_POST, buildJsonObject {
                    put("session", sessionId)
                    put("senderId", agent.id)
                    put("message", decoded.rawContent)
                }))
            }
            setAgentStatus(agentId, AgentStatus.IDLE, store)
        }
    }


    private fun setAgentStatus(agentId: String, status: AgentStatus, store: Store, error: String? = null) {
        val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
        val agent = agentState.agents[agentId] ?: return
        val sessionId = agent.primarySessionId ?: return

        // 1. Atomically delete the old card, if one exists.
        agentState.agentAvatarCardIds[agentId]?.let { oldMessageId ->
            store.dispatch(this.name, Action(ActionNames.SESSION_DELETE_MESSAGE, buildJsonObject {
                put("session", sessionId)
                put("messageId", oldMessageId)
            }))
        }

        // 2. Dispatch the internal action to update the agent's state.
        store.dispatch(this.name, Action(ActionNames.AGENT_INTERNAL_SET_STATUS, buildJsonObject {
            put("agentId", agentId); put("status", status.name); error?.let { put("error", it) }
        }))

        // 3. Determine the correct positional frontier.
        val latestAgentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
        val latestAgent = latestAgentState.agents[agentId] ?: return
        val afterMessageId = when (status) {
            AgentStatus.PROCESSING -> latestAgent.processingFrontierMessageId
            else -> latestAgent.lastSeenMessageId
        }

        // 4. Post the new card with the correct metadata and position.
        val messageId = platformDependencies.generateUUID()
        val metadata = buildJsonObject {
            put("render_as_partial", true)
            put("is_transient", true)
            put("agentStatus", status.name)
            error?.let { put("errorMessage", it) }
        }

        store.dispatch(this.name, Action(ActionNames.SESSION_POST, buildJsonObject {
            put("session", sessionId)
            put("senderId", agentId)
            put("messageId", messageId)
            put("metadata", metadata)
            afterMessageId?.let { put("afterMessageId", it) }
        }))
    }

    override val composableProvider: ComposableProvider = object : ComposableProvider {
        override val stageViews: Map<String, @Composable (Store, List<Feature>) -> Unit> =
            mapOf("feature.agent.manager" to { store, _ -> AgentManagerView(store) })

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
                agentName = agent.name,
                agentStatus = agent.status,
                errorMessage = agent.errorMessage,
                onTrigger = {
                    store.dispatch(
                        "ui.avatar.$agentId",
                        Action(ActionNames.AGENT_TRIGGER_MANUAL_TURN, buildJsonObject { put("agentId", agent.id) })
                    )
                },
                onCancel = {
                    store.dispatch(
                        "ui.avatar.$agentId",
                        Action(ActionNames.AGENT_CANCEL_TURN, buildJsonObject { put("agentId", agent.id) })
                    )
                },
                // THE FIX: Update the canTrigger logic to include the WAITING state.
                canTrigger = (agent.status == AgentStatus.IDLE || agent.status == AgentStatus.WAITING || agent.status == AgentStatus.ERROR) && agent.primarySessionId != null
            )
        }
    }
}