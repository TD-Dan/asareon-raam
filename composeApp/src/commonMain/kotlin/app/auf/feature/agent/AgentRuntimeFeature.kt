package app.auf.feature.agent

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import app.auf.core.*
import app.auf.core.Feature.ComposableProvider
import app.auf.feature.session.SessionState
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
    @Serializable private data class GatewayModelsPayload(val models: Map<String, List<String>>)
    @Serializable private data class SessionNamesPayload(val names: Map<String, String>)
    @Serializable private data class GatewayResponsePayload(val correlationId: String, val rawContent: String? = null, val errorMessage: String? = null)
    @Serializable private data class GatewayMessage(val role: String, val content: String)
    @Serializable private data class AgentIdPayload(val agentId: String)
    @Serializable private data class SessionPostPayload(val senderId: String, val metadata: JsonObject? = null)
    @Serializable private data class SessionDeleteMessagePayload(val messageId: String)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val AGENT_CONFIG_FILENAME = "agent.json"
    private val activeTurnJobs = mutableMapOf<String, Job>()

    // --- REDUCER (Pure State Logic) ---

    override fun reducer(state: AppState, action: Action): AppState {
        val currentFeatureState = state.featureStates[name] as? AgentRuntimeState ?: AgentRuntimeState()
        var newFeatureState: AgentRuntimeState? = null
        val payload = action.payload

        when (action.name) {
            "agent.CREATE" -> handleCreateAgent(action, currentFeatureState)?.let { newFeatureState = it }
            "agent.DELETE" -> handleDeleteAgent(action, currentFeatureState)?.let { newFeatureState = it }
            "agent.UPDATE_CONFIG" -> handleUpdateConfig(action, currentFeatureState)?.let { newFeatureState = it }
            "agent.TOGGLE_AUTOMATIC_MODE" -> handleToggleAutomaticMode(action, currentFeatureState)?.let { newFeatureState = it }
            "session.DELETE" -> handleSessionDeleted(action, currentFeatureState)?.let { newFeatureState = it }
            "agent.internal.SET_STATUS" -> handleSetStatus(action, currentFeatureState)?.let { newFeatureState = it }
            "agent.internal.AGENT_LOADED" -> handleAgentLoaded(action, currentFeatureState)?.let { newFeatureState = it }
            "agent.SET_EDITING" -> handleSetEditing(action, currentFeatureState)?.let { newFeatureState = it }
            "session.POST", "session.DELETE_MESSAGE" -> handleSessionEvents(action, currentFeatureState)?.let { newFeatureState = it }

            "gateway.publish.AVAILABLE_MODELS_UPDATED" -> {
                val decoded = try { payload?.let { json.decodeFromJsonElement(GatewayModelsPayload.serializer(), it) } } catch(e: Exception) { null } ?: return state
                newFeatureState = currentFeatureState.copy(availableModels = decoded.models)
            }
            "session.publish.SESSION_NAMES_UPDATED" -> {
                val decoded = try { payload?.let { json.decodeFromJsonElement(SessionNamesPayload.serializer(), it) } } catch(e: Exception) { null } ?: return state
                newFeatureState = currentFeatureState.copy(sessionNames = decoded.names)
            }
        }

        return newFeatureState?.let {
            state.copy(featureStates = state.featureStates + (name to it))
        } ?: state
    }

    private fun handleCreateAgent(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState? {
        val payload = action.payload ?: return null
        val agentName = payload["name"]?.jsonPrimitive?.contentOrNull ?: return null
        // Use defaults for non-required fields
        val personaId = payload["personaId"]?.jsonPrimitive?.contentOrNull ?: "keel-20250914T142800Z"
        val modelProvider = payload["modelProvider"]?.jsonPrimitive?.contentOrNull ?: "gemini"
        val modelName = payload["modelName"]?.jsonPrimitive?.contentOrNull ?: "gemini-2.5-pro"
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
        val newAgents = currentFeatureState.agents.mapValues { (_, agent) -> if (agent.primarySessionId == deletedSessionId) agent.copy(primarySessionId = null) else agent }
        return if (newAgents != currentFeatureState.agents) currentFeatureState.copy(agents = newAgents) else null
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

    private fun handleSessionEvents(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState? {
        // This reducer now listens to session events to track its own avatar cards.
        val agentId = action.payload?.get("senderId")?.jsonPrimitive?.contentOrNull ?: return null
        if (!currentFeatureState.agents.containsKey(agentId)) return null // Not our agent, ignore.

        val isAvatarCard = action.payload["metadata"]?.jsonObject?.get("render_as_partial")?.jsonPrimitive?.booleanOrNull ?: false
        if (!isAvatarCard) return null // Not an avatar card, ignore.

        val newAvatarMap = currentFeatureState.agentAvatarCardIds.toMutableMap()

        when (action.name) {
            "session.POST" -> {
                // TODO: This logic will need to be expanded to handle multiple frontiers (PROCESSING, WAITING)
                // For now, it just tracks the latest card.
                val messageId = action.payload["messageId"]?.jsonPrimitive?.contentOrNull ?: return null
                val agentCards = newAvatarMap.getOrPut(agentId) { mutableMapOf() }.toMutableMap()
                // This is a simplified logic, will need refinement.
                agentCards[AgentStatus.IDLE] = messageId
                newAvatarMap[agentId] = agentCards
            }
            "session.DELETE_MESSAGE" -> {
                val messageId = action.payload["messageId"]?.jsonPrimitive?.contentOrNull ?: return null
                val agentCards = newAvatarMap[agentId]?.toMutableMap() ?: return null
                val statusToRemove = agentCards.entries.find { it.value == messageId }?.key
                if (statusToRemove != null) {
                    agentCards.remove(statusToRemove)
                    newAvatarMap[agentId] = agentCards
                }
            }
        }
        return currentFeatureState.copy(agentAvatarCardIds = newAvatarMap)
    }


    // --- ON_ACTION (Side Effect Orchestration) ---

    override fun onAction(action: Action, store: Store) {
        when (action.name) {
            "system.STARTING" -> {
                store.dispatch(this.name, Action("filesystem.SYSTEM_LIST"))
                store.dispatch(this.name, Action("gateway.REQUEST_AVAILABLE_MODELS"))
            }
            "agent.CREATE", "agent.UPDATE_CONFIG", "agent.TOGGLE_AUTOMATIC_MODE" -> {
                val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: agentState.agents.keys.lastOrNull() ?: return
                val agentToSave = agentState.agents[agentId] ?: return
                store.dispatch(this.name, Action("filesystem.SYSTEM_WRITE", buildJsonObject {
                    put("subpath", "${agentToSave.id}/$AGENT_CONFIG_FILENAME")
                    put("content", json.encodeToString(agentToSave))
                }))
                broadcastAgentNames(store)
            }
            "agent.DELETE" -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
                val agent = agentState.agents[agentId] ?: return

                // Agent cleans up its own avatar cards before being deleted.
                val sessionToClean = agent.primarySessionId
                val cardsToDelete = agentState.agentAvatarCardIds[agentId]
                if (sessionToClean != null && cardsToDelete != null) {
                    cardsToDelete.values.forEach { messageId ->
                        store.dispatch(this.name, Action("session.DELETE_MESSAGE", buildJsonObject {
                            put("session", sessionToClean)
                            put("messageId", messageId)
                        }))
                    }
                }
                store.dispatch(this.name, Action("filesystem.SYSTEM_DELETE_DIRECTORY", buildJsonObject { put("subpath", agentId) }))
                broadcastAgentNames(store)
            }
            "session.DELETE" -> {
                val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
                val deletedSessionId = action.payload?.get("sessionId")?.jsonPrimitive?.contentOrNull ?: return
                agentState.agents.values.filter { it.primarySessionId == deletedSessionId }.forEach { agentToUpdate ->
                    store.dispatch(this.name, Action("filesystem.SYSTEM_WRITE", buildJsonObject {
                        put("subpath", "${agentToUpdate.id}/$AGENT_CONFIG_FILENAME")
                        put("content", json.encodeToString(agentToUpdate))
                    }))
                }
            }
            "agent.CANCEL_TURN" -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                activeTurnJobs[agentId]?.cancel()
                activeTurnJobs.remove(agentId)
                setAgentStatus(agentId, AgentStatus.IDLE, store, "Turn cancelled by user.")
            }
            "agent.TRIGGER_MANUAL_TURN" -> beginCognitiveCycle(action, store)
            "gateway.publish.CONTENT_GENERATED" -> handleGatewayResponse(action, store)
        }
    }

    private fun broadcastAgentNames(store: Store) {
        val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
        val nameMap = agentState.agents.mapValues { it.value.name }
        store.dispatch(this.name, Action("agent.publish.AGENT_NAMES_UPDATED", buildJsonObject {
            put("names", Json.encodeToJsonElement(nameMap))
        }))
    }

    private fun beginCognitiveCycle(action: Action, store: Store) {
        val agentId = action.payload?.let { json.decodeFromJsonElement(AgentIdPayload.serializer(), it) }?.agentId ?: return
        val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
        val agent = agentState.agents[agentId] ?: return

        if (agent.status == AgentStatus.PROCESSING || agent.status == AgentStatus.WAITING) return
        val sessionId = agent.primarySessionId ?: run {
            setAgentStatus(agentId, AgentStatus.ERROR, store, "Cannot start turn: Agent not subscribed to a session."); return
        }

        // TODO (ARCH-VIOLATION): The following block directly accesses SessionState. This is a temporary
        // stub and a violation of Absolute Decoupling (P-ARCH-002). This entire function must be
        // refactored to implement the full "frontier" logic, where the agent determines its
        // context by observing the public ledger and maintaining its own state, without reaching
        // into the internal state of the SessionFeature.
        val sessionState = store.state.value.featureStates["session"] as? SessionState ?: return
        val session = sessionState.sessions[sessionId] ?: return
        val contextMessages = session.ledger.mapNotNull { it.rawContent?.let { content -> GatewayMessage("user", content) } } // Simplified for now

        setAgentStatus(agentId, AgentStatus.PROCESSING, store)
        val turnJob = coroutineScope.launch {
            store.dispatch(this@AgentRuntimeFeature.name, Action("gateway.GENERATE_CONTENT", buildJsonObject {
                put("providerId", agent.modelProvider)
                put("modelName", agent.modelName)
                put("correlationId", agent.id)
                put("contents", Json.encodeToJsonElement(contextMessages))
            }))
        }
        activeTurnJobs[agentId] = turnJob
        turnJob.invokeOnCompletion { activeTurnJobs.remove(agentId) }
    }

    private fun handleGatewayResponse(action: Action, store: Store) {
        val decoded = try { action.payload?.let { json.decodeFromJsonElement(GatewayResponsePayload.serializer(), it) } } catch (e: Exception) { null } ?: return
        val agentId = decoded.correlationId
        val agent = (store.state.value.featureStates[name] as? AgentRuntimeState)?.agents?.get(agentId) ?: return

        if (decoded.errorMessage != null) {
            setAgentStatus(agentId, AgentStatus.ERROR, store, "[AGENT ERROR] Generation failed: ${decoded.errorMessage}")
        } else {
            val responseContent = decoded.rawContent ?: "[AGENT ERROR] Received empty response from gateway."
            store.dispatch(this.name, Action("session.POST", buildJsonObject {
                put("session", agent.primarySessionId); put("senderId", agent.id); put("message", responseContent)
            }))
            setAgentStatus(agentId, AgentStatus.IDLE, store)
        }
    }

    override fun onPrivateData(data: Any, store: Store) {
        when (data) {
            is List<*> -> data.filterIsInstance<FileEntry>().filter { it.isDirectory }.forEach { dirEntry ->
                val configPath = "${platformDependencies.getFileName(dirEntry.path)}/$AGENT_CONFIG_FILENAME"
                store.dispatch(this.name, Action("filesystem.SYSTEM_READ", buildJsonObject { put("subpath", configPath) }))
            }
            is JsonObject -> try {
                val agent = json.decodeFromString<AgentInstance>(data["content"]?.jsonPrimitive?.content ?: "")
                store.dispatch(this.name, Action("agent.internal.LOADED", Json.encodeToJsonElement(agent) as JsonObject))
            } catch (e: Exception) {
                platformDependencies.log(LogLevel.ERROR, name, "Failed to parse agent config: ${data["subpath"]}. Error: ${e.message}")
            }
        }
    }

    private fun setAgentStatus(agentId: String, status: AgentStatus, store: Store, error: String? = null) {
        // TODO: This should now post/update a card on the ledger instead of just an internal status.
        // 1. Find existing card ID from `agentAvatarCardIds`.
        // 2. If exists, dispatch `session.UPDATE_MESSAGE` with new metadata.
        // 3. If not, dispatch `session.POST` with new card.
        store.dispatch(this.name, Action("agent.internal.SET_STATUS", buildJsonObject {
            put("agentId", agentId); put("status", status.name); error?.let { put("error", it) }
        }))
    }

    override val composableProvider: ComposableProvider = object : ComposableProvider {
        override val stageViews: Map<String, @Composable (Store, List<Feature>) -> Unit> =
            mapOf("feature.agent.manager" to { store, _ -> AgentManagerView(store) })

        @Composable
        override fun RibbonContent(store: Store, activeViewKey: String?) {
            val viewKey = "feature.agent.manager"
            val isActive = activeViewKey == viewKey
            IconButton(onClick = { store.dispatch("ui.ribbon", Action("core.SET_ACTIVE_VIEW", buildJsonObject { put("key", viewKey) })) }) {
                Icon(Icons.Default.Bolt, "Agent Manager", tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}