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
import app.auf.util.FileEntry
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    @Serializable private data class GatewayMessage(val role: String, val content: String)
    @Serializable private data class LedgerContentResponse(val correlationId: String, val messages: List<GatewayMessage>)
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

            ActionNames.GATEWAY_PUBLISH_AVAILABLE_MODELS_UPDATED -> {
                val decodedModels: Map<String, List<String>>? = try { payload?.let { json.decodeFromJsonElement(it) } } catch (e: Exception) { null }
                newFeatureState = currentFeatureState.copy(availableModels = decodedModels ?: emptyMap())
            }
            ActionNames.SESSION_PUBLISH_SESSION_NAMES_UPDATED -> {
                val decoded = try { payload?.let { json.decodeFromJsonElement<SessionNamesPayload>(it) } } catch(e: Exception) { null } ?: return stateWithFeature
                newFeatureState = currentFeatureState.copy(sessionNames = decoded.names)
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
        val newStatus = try { AgentStatus.valueOf(newStatusString) } catch (e: Exception) { return null }
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
        val metadata = entry["metadata"]?.jsonObject ?: return null

        val isAvatarCard = metadata["render_as_partial"]?.jsonPrimitive?.booleanOrNull ?: false
        if (!isAvatarCard) return null

        val agentId = entry["senderId"]?.jsonPrimitive?.contentOrNull ?: return null
        if (!currentFeatureState.agents.containsKey(agentId)) return null

        val statusString = metadata["agentStatus"]?.jsonPrimitive?.contentOrNull ?: return null
        val status = try { AgentStatus.valueOf(statusString) } catch (e: Exception) { return null }
        val messageId = entry["id"]?.jsonPrimitive?.contentOrNull ?: return null

        val newAvatarMap = currentFeatureState.agentAvatarCardIds.toMutableMap()
        val agentCards = newAvatarMap.getOrPut(agentId) { mutableMapOf() }.toMutableMap()
        agentCards[status] = messageId
        newAvatarMap[agentId] = agentCards
        return currentFeatureState.copy(agentAvatarCardIds = newAvatarMap)
    }

    private fun handleMessageDeleted(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState? {
        val payload = action.payload?.let { json.decodeFromJsonElement<MessageDeletedPayload>(it) } ?: return null
        val agentId = currentFeatureState.agentAvatarCardIds.entries.find { (_, statuses) -> statuses.containsValue(payload.messageId) }?.key ?: return null

        val newAvatarMap = currentFeatureState.agentAvatarCardIds.toMutableMap()
        val agentCards = newAvatarMap.getOrPut(agentId) { mutableMapOf() }.toMutableMap()
        val statusToRemove = agentCards.entries.find { it.value == payload.messageId }?.key
        if (statusToRemove != null) {
            agentCards.remove(statusToRemove)
        }
        newAvatarMap[agentId] = agentCards
        return currentFeatureState.copy(agentAvatarCardIds = newAvatarMap)
    }

    // --- ON_ACTION (Side Effect Orchestration) ---

    override fun onAction(action: Action, store: Store) {
        val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
        when (action.name) {
            ActionNames.SYSTEM_STARTING -> {
                store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_LIST))
                store.dispatch(this.name, Action(ActionNames.GATEWAY_REQUEST_AVAILABLE_MODELS))
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

                // THE FIX: Handle avatar card sync on session change.
                if (oldAgent != null && oldAgent.primarySessionId != newAgent.primarySessionId) {
                    // 1. Clean up card from the old session
                    oldAgent.primarySessionId?.let { oldSessionId ->
                        agentState.agentAvatarCardIds[agentId]?.values?.forEach { messageId ->
                            store.dispatch(this.name, Action(ActionNames.SESSION_DELETE_MESSAGE, buildJsonObject {
                                put("session", oldSessionId)
                                put("messageId", messageId)
                            }))
                        }
                    }
                    // 2. Create a new idle card in the new session
                    newAgent.primarySessionId?.let {
                        setAgentStatus(agentId, AgentStatus.IDLE, store)
                    }
                }
            }
            ActionNames.AGENT_DELETE -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                val agentToDelete = agentState.agents[agentId] ?: return

                val sessionToClean = agentToDelete.primarySessionId
                val cardsToDelete = agentState.agentAvatarCardIds[agentId]
                cardsToDelete?.values?.forEach { messageId ->
                    if (sessionToClean != null) {
                        store.dispatch(this.name, Action(ActionNames.SESSION_DELETE_MESSAGE, buildJsonObject {
                            put("session", sessionToClean)
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
                val agent = agentState.agents[agentId] ?: return
                val sessionId = agent.primarySessionId ?: return
                agentState.agentAvatarCardIds[agentId]?.get(AgentStatus.PROCESSING)?.let { messageId ->
                    store.dispatch(this.name, Action(ActionNames.SESSION_DELETE_MESSAGE, buildJsonObject {
                        put("session", sessionId)
                        put("messageId", messageId)
                    }))
                }
                setAgentStatus(agentId, AgentStatus.IDLE, store, "Turn cancelled by user.")
            }
            ActionNames.AGENT_TRIGGER_MANUAL_TURN -> beginCognitiveCycle(action, store)
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

        if (agent.status == AgentStatus.PROCESSING || agent.status == AgentStatus.WAITING) return
        val sessionId = agent.primarySessionId ?: run {
            setAgentStatus(agentId, AgentStatus.ERROR, store, "Cannot start turn: Agent not subscribed to a session."); return
        }

        setAgentStatus(agentId, AgentStatus.PROCESSING, store)
        store.dispatch(this.name, Action(ActionNames.SESSION_REQUEST_LEDGER_CONTENT, buildJsonObject {
            put("sessionId", sessionId)
            put("correlationId", agentId)
        }))
    }

    override fun onPrivateData(data: Any, store: Store) {
        when (data) {
            is List<*> -> data.filterIsInstance<FileEntry>().filter { it.isDirectory }.forEach { dirEntry ->
                val configPath = "${platformDependencies.getFileName(dirEntry.path)}/$agentConfigFILENAME"
                store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_READ, buildJsonObject { put("subpath", configPath) }))
            }
            is JsonObject -> {
                if (data.containsKey("correlationId") && data.containsKey("messages")) { // Response from SessionFeature
                    // THE FIX: Hardened parsing to prevent silent failures and deadlocks.
                    val agentId = data["correlationId"]?.jsonPrimitive?.contentOrNull
                    if (agentId == null) {
                        platformDependencies.log(LogLevel.ERROR, name, "Received session ledger with no correlationId.")
                        return
                    }
                    try {
                        val decoded = json.decodeFromJsonElement<LedgerContentResponse>(data)
                        val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
                        val agent = agentState.agents[agentId] ?: return

                        val turnJob = coroutineScope.launch {
                            store.dispatch(this@AgentRuntimeFeature.name, Action(ActionNames.GATEWAY_GENERATE_CONTENT, buildJsonObject {
                                put("providerId", agent.modelProvider)
                                put("modelName", agent.modelName)
                                put("correlationId", agent.id)
                                put("contents", Json.encodeToJsonElement(decoded.messages))
                            }))
                        }
                        activeTurnJobs[agentId] = turnJob
                        turnJob.invokeOnCompletion { activeTurnJobs.remove(agentId) }
                    } catch (e: Exception) {
                        platformDependencies.log(LogLevel.ERROR, name, "FATAL: Failed to parse session ledger for agent '$agentId'. Error: ${e.message}")
                        setAgentStatus(agentId, AgentStatus.ERROR, store, "FATAL: Could not parse session ledger.")
                    }

                } else if (data.containsKey("correlationId")) { // Response from GatewayFeature
                    val agentId = data["correlationId"]?.jsonPrimitive?.contentOrNull
                    if (agentId == null) {
                        platformDependencies.log(LogLevel.ERROR, name, "Received gateway response with no correlationId.")
                        return
                    }

                    val decoded = try {
                        json.decodeFromJsonElement<GatewayResponsePayload>(data)
                    } catch (e: Exception) {
                        platformDependencies.log(LogLevel.ERROR, name, "FATAL: Failed to parse gateway response for agent '$agentId'. Error: ${e.message}")
                        setAgentStatus(agentId, AgentStatus.ERROR, store, "FATAL: Could not parse gateway response.")
                        return
                    }

                    val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
                    val agent = agentState.agents[agentId] ?: return
                    val sessionId = agent.primarySessionId ?: return

                    agentState.agentAvatarCardIds[agentId]?.get(AgentStatus.PROCESSING)?.let { messageId ->
                        store.dispatch(this.name, Action(ActionNames.SESSION_DELETE_MESSAGE, buildJsonObject {
                            put("session", sessionId)
                            put("messageId", messageId)
                        }))
                    }

                    if (decoded.errorMessage != null) {
                        setAgentStatus(agentId, AgentStatus.ERROR, store, "[AGENT ERROR] Generation failed: ${decoded.errorMessage}")
                    } else {
                        val responseContent = decoded.rawContent ?: "[AGENT ERROR] Received empty response from gateway."
                        store.dispatch(this.name, Action(ActionNames.SESSION_POST, buildJsonObject {
                            put("session", sessionId); put("senderId", agent.id); put("message", responseContent)
                        }))
                        setAgentStatus(agentId, AgentStatus.IDLE, store)
                    }
                } else if (data.containsKey("content")) { // Response from FileSystemFeature
                    try {
                        val agent = json.decodeFromString<AgentInstance>(data["content"]?.jsonPrimitive?.content ?: "")
                        store.dispatch(this.name, Action(ActionNames.AGENT_INTERNAL_AGENT_LOADED, Json.encodeToJsonElement(agent) as JsonObject))
                        // THE FIX: Create the avatar card on successful load.
                        if (agent.primarySessionId != null) {
                            setAgentStatus(agent.id, AgentStatus.IDLE, store)
                        }
                    } catch (e: Exception) {
                        platformDependencies.log(LogLevel.ERROR, name, "Failed to parse agent config: ${data["subpath"]}. Error: ${e.message}")
                    }
                }
            }
        }
    }

    private fun setAgentStatus(agentId: String, status: AgentStatus, store: Store, error: String? = null) {
        val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
        val agent = agentState.agents[agentId] ?: return
        val sessionId = agent.primarySessionId ?: return

        store.dispatch(this.name, Action(ActionNames.AGENT_INTERNAL_SET_STATUS, buildJsonObject {
            put("agentId", agentId); put("status", status.name); error?.let { put("error", it) }
        }))

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
    }
}