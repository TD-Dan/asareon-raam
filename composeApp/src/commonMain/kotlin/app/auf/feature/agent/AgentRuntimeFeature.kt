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
import app.auf.util.FileEntry
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

private data class AnyAction(
    val name: String,
    val jsonPayload: JsonObject?,
    val payload: Any?
) : Action(name, jsonPayload)

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
    @Serializable private data class TriggerTurnPayload(val agentId: String, val lastMessage: String)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val AGENT_CONFIG_FILENAME = "agent.json"

    // --- REDUCER (Pure State Logic) ---

    override fun reducer(state: AppState, action: Action): AppState {
        val currentFeatureState = state.featureStates[name] as? AgentRuntimeState ?: AgentRuntimeState()
        var newFeatureState: AgentRuntimeState? = null
        val payload = action.payload

        when (action.name) {
            "agent.CREATE" -> handleCreateAgent(action, currentFeatureState)?.let { newFeatureState = it }
            "agent.DELETE" -> handleDeleteAgent(action, currentFeatureState)?.let { newFeatureState = it }
            "agent.UPDATE_CONFIG" -> handleUpdateConfig(action, currentFeatureState)?.let { newFeatureState = it }
            "session.DELETE" -> handleSessionDeleted(action, currentFeatureState)?.let { newFeatureState = it }
            "agent.internal.SET_STATUS" -> handleSetStatus(action, currentFeatureState)?.let { newFeatureState = it }
            "agent.internal.AGENT_LOADED" -> handleAgentLoaded(action, currentFeatureState)?.let { newFeatureState = it }
            "agent.internal.SET_TURN_JOB" -> handleSetTurnJob(action, currentFeatureState)?.let { newFeatureState = it }
            "agent.SET_EDITING" -> handleSetEditing(action, currentFeatureState)?.let { newFeatureState = it }
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
        val personaId = payload["personaId"]?.jsonPrimitive?.contentOrNull ?: return null
        val modelProvider = payload["modelProvider"]?.jsonPrimitive?.contentOrNull ?: return null
        val modelName = payload["modelName"]?.jsonPrimitive?.contentOrNull ?: return null
        val primarySessionId = payload["primarySessionId"]?.jsonPrimitive?.contentOrNull

        val newAgentId = platformDependencies.generateUUID()
        val newAgent = AgentInstance(
            id = newAgentId,
            name = agentName,
            personaId = personaId,
            modelProvider = modelProvider,
            modelName = modelName,
            primarySessionId = primarySessionId
        )

        val newAgents = currentFeatureState.agents + (newAgentId to newAgent)
        return currentFeatureState.copy(agents = newAgents, editingAgentId = newAgentId) // Auto-edit new agent
    }

    private fun handleDeleteAgent(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState? {
        val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return null
        val newAgents = currentFeatureState.agents.filterKeys { it != agentId }
        return currentFeatureState.copy(agents = newAgents)
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
            primarySessionId = if ("primarySessionId" in payload) {
                payload["primarySessionId"]?.jsonPrimitive?.contentOrNull
            } else {
                agentToUpdate.primarySessionId
            }
        )

        val newAgents = currentFeatureState.agents + (agentId to updatedAgent)
        return currentFeatureState.copy(agents = newAgents)
    }

    private fun handleSessionDeleted(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState? {
        val deletedSessionId = action.payload?.get("sessionId")?.jsonPrimitive?.contentOrNull ?: return null
        val newAgents = currentFeatureState.agents.mapValues { (_, agent) ->
            if (agent.primarySessionId == deletedSessionId) agent.copy(primarySessionId = null) else agent
        }
        if (newAgents == currentFeatureState.agents) return null
        return currentFeatureState.copy(agents = newAgents)
    }

    private fun handleSetStatus(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState? {
        val payload = action.payload ?: return null
        val agentId = payload["agentId"]?.jsonPrimitive?.contentOrNull ?: return null
        val agentToUpdate = currentFeatureState.agents[agentId] ?: return null
        val newStatusString = payload["status"]?.jsonPrimitive?.contentOrNull ?: return null
        val newStatus = try { AgentStatus.valueOf(newStatusString) } catch (e: Exception) { return null }
        val newErrorMessage = if (newStatus == AgentStatus.ERROR) payload["error"]?.jsonPrimitive?.contentOrNull else null
        val updatedAgent = agentToUpdate.copy(status = newStatus, errorMessage = newErrorMessage, turnJob = if (newStatus != AgentStatus.PROCESSING) null else agentToUpdate.turnJob)
        val newAgents = currentFeatureState.agents + (agentId to updatedAgent)
        return currentFeatureState.copy(agents = newAgents)
    }

    private fun handleSetTurnJob(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState? {
        val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return null
        val agentToUpdate = currentFeatureState.agents[agentId] ?: return null
        val job = (action as? AnyAction)?.payload as? Job
        val updatedAgent = agentToUpdate.copy(turnJob = job)
        val newAgents = currentFeatureState.agents + (agentId to updatedAgent)
        return currentFeatureState.copy(agents = newAgents)
    }

    private fun handleAgentLoaded(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState? {
        val agent = action.payload?.let { json.decodeFromJsonElement<AgentInstance>(it) } ?: return null
        if (currentFeatureState.agents.containsKey(agent.id)) return null
        val newAgents = currentFeatureState.agents + (agent.id to agent)
        return currentFeatureState.copy(agents = newAgents)
    }

    private fun handleSetEditing(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState? {
        val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull
        val nextId = if (agentId == currentFeatureState.editingAgentId) null else agentId
        return currentFeatureState.copy(editingAgentId = nextId)
    }

    // --- ON_ACTION (Side Effect Orchestration) ---

    override fun onAction(action: Action, store: Store) {
        when (action.name) {
            "system.STARTING" -> {
                store.dispatch(this.name, Action("filesystem.SYSTEM_LIST"))
                store.dispatch(this.name, Action("gateway.REQUEST_AVAILABLE_MODELS"))
            }
            "agent.CREATE", "agent.UPDATE_CONFIG" -> {
                val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: agentState.agents.keys.lastOrNull() ?: return
                val agentToSave = agentState.agents[agentId] ?: return
                val fileContent = json.encodeToString(AgentInstance.serializer(), agentToSave)
                val subpath = "${agentToSave.id}/$AGENT_CONFIG_FILENAME"
                store.dispatch(this.name, Action("filesystem.SYSTEM_WRITE", buildJsonObject {
                    put("subpath", subpath); put("content", fileContent)
                }))
                broadcastAgentNames(store)
            }
            "agent.DELETE" -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                store.dispatch(this.name, Action("filesystem.SYSTEM_DELETE_DIRECTORY", buildJsonObject { put("subpath", agentId) }))
                broadcastAgentNames(store)
            }
            "session.DELETE" -> {
                val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
                val deletedSessionId = action.payload?.get("sessionId")?.jsonPrimitive?.contentOrNull ?: return
                agentState.agents.values.filter { it.primarySessionId == deletedSessionId }.forEach { agentToUpdate ->
                    val fileContent = json.encodeToString(AgentInstance.serializer(), agentToUpdate)
                    val subpath = "${agentToUpdate.id}/$AGENT_CONFIG_FILENAME"
                    store.dispatch(this.name, Action("filesystem.SYSTEM_WRITE", buildJsonObject {
                        put("subpath", subpath); put("content", fileContent)
                    }))
                }
                broadcastAgentNames(store)
            }
            "agent.CANCEL_TURN" -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
                agentState.agents[agentId]?.turnJob?.cancel()
                setAgentStatus(agentId, AgentStatus.IDLE, store, "Turn cancelled by user.")
            }
            "agent.TRIGGER_MANUAL_TURN" -> beginCognitiveCycle(action, store)
            "gateway.publish.CONTENT_GENERATED" -> handleGatewayResponse(action, store)
        }
    }

    private fun broadcastAgentNames(store: Store) {
        val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
        val nameMap = agentState.agents.mapValues { it.value.name }
        val payload = buildJsonObject { put("names", Json.encodeToJsonElement(nameMap)) }
        store.dispatch(this.name, Action("agent.publish.AGENT_NAMES_UPDATED", payload))
    }

    private fun beginCognitiveCycle(action: Action, store: Store) {
        val decoded = try { action.payload?.let { json.decodeFromJsonElement(TriggerTurnPayload.serializer(), it) } } catch(e: Exception) { null } ?: return
        val agentId = decoded.agentId
        val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
        val agent = agentState.agents[agentId] ?: return

        if (agent.status == AgentStatus.PROCESSING || agent.status == AgentStatus.WAITING) return
        if (agent.primarySessionId == null) {
            val msg = "Cannot start turn: Agent is not subscribed to a primary session."
            setAgentStatus(agentId, AgentStatus.ERROR, store, msg); return
        }

        setAgentStatus(agentId, AgentStatus.PROCESSING, store)
        val turnJob = coroutineScope.launch {
            val messages = listOf(GatewayMessage("user", decoded.lastMessage))
            val gatewayPayload = buildJsonObject {
                put("providerId", agent.modelProvider)
                put("modelName", agent.modelName)
                put("correlationId", agent.id)
                put("contents", Json.encodeToJsonElement(messages))
            }
            store.dispatch(this@AgentRuntimeFeature.name, Action("gateway.GENERATE_CONTENT", gatewayPayload))
        }

        store.dispatch(this.name, AnyAction("agent.internal.SET_TURN_JOB", buildJsonObject { put("agentId", agentId) }, turnJob))
    }

    private fun handleGatewayResponse(action: Action, store: Store) {
        val decoded = try { action.payload?.let { json.decodeFromJsonElement(GatewayResponsePayload.serializer(), it) } } catch (e: Exception) { null } ?: return
        val agentId = decoded.correlationId
        val agent = (store.state.value.featureStates[name] as? AgentRuntimeState)?.agents?.get(agentId) ?: run {
            platformDependencies.log(LogLevel.WARN, name, "Received GatewayResponse for deleted agent '$agentId'. Discarding."); return
        }
        if (decoded.errorMessage != null) {
            val error = "[AGENT ERROR] Generation failed: ${decoded.errorMessage}"
            setAgentStatus(agentId, AgentStatus.ERROR, store, error)
        } else {
            val responseContent = decoded.rawContent ?: "[AGENT ERROR] Received empty response from gateway."
            val postPayload = buildJsonObject {
                put("session", agent.primarySessionId); put("agentId", agent.id); put("message", responseContent)
            }
            store.dispatch(this.name, Action("session.POST", postPayload))
            setAgentStatus(agentId, AgentStatus.IDLE, store)
        }
    }

    // --- ON_PRIVATE_DATA (Async Data Handling) ---

    override fun onPrivateData(data: Any, store: Store) {
        when (data) {
            is List<*> -> {
                data.filterIsInstance<FileEntry>().filter { it.isDirectory }.forEach { dirEntry ->
                    val agentId = platformDependencies.getFileName(dirEntry.path)
                    val configPath = "$agentId/$AGENT_CONFIG_FILENAME"
                    store.dispatch(this.name, Action("filesystem.SYSTEM_READ", buildJsonObject { put("subpath", configPath) }))
                }
            }
            is JsonObject -> { // agent.json file content
                val content = data["content"]?.jsonPrimitive?.contentOrNull ?: return
                try {
                    val agent = json.decodeFromString<AgentInstance>(content)
                    store.dispatch(this.name, Action("agent.internal.AGENT_LOADED", Json.encodeToJsonElement(agent) as JsonObject))
                } catch (e: Exception) {
                    platformDependencies.log(LogLevel.ERROR, name, "Failed to parse agent config: ${data["subpath"]}. Error: ${e.message}")
                }
            }
        }
    }

    private fun setAgentStatus(agentId: String, status: AgentStatus, store: Store, error: String? = null) {
        store.dispatch(this.name, Action("agent.internal.SET_STATUS", buildJsonObject {
            put("agentId", agentId); put("status", status.name); error?.let { put("error", it) }
        }))
    }

    // --- UI PROVIDER ---

    override val composableProvider: ComposableProvider = object : ComposableProvider {
        override val stageViews: Map<String, @Composable (Store) -> Unit> =
            mapOf("feature.agent.manager" to { store -> AgentManagerView(store) })

        @Composable
        override fun RibbonContent(store: Store, activeViewKey: String?) {
            val viewKey = "feature.agent.manager"
            val isActive = activeViewKey == viewKey
            IconButton(
                onClick = { store.dispatch("ui.ribbon", Action("core.SET_ACTIVE_VIEW", buildJsonObject { put("key", viewKey) })) }
            ) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = "Agent Manager",
                    tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        @Composable
        override fun PartialView(store: Store, partId: String) {
            val appState by store.state.collectAsState()
            val agentState = appState.featureStates[name] as? AgentRuntimeState ?: return
            val sessionState = appState.featureStates["session"] as? SessionState ?: return
            val agent = agentState.agents[partId] ?: return
            val session = agent.primarySessionId?.let { sessionState.sessions[it] } ?: return
            AgentAvatarCard(agent = agent, session = session, store = store)
        }
    }
}