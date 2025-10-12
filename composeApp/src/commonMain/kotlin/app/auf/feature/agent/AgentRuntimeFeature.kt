package app.auf.feature.agent

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import app.auf.core.*
import app.auf.core.Feature.ComposableProvider
import app.auf.feature.gateway.GatewayMessage
import app.auf.feature.gateway.GatewayResponse
import app.auf.feature.session.SessionState
import app.auf.util.FileEntry
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class AgentRuntimeFeature(
    private val platformDependencies: PlatformDependencies,
    private val coroutineScope: CoroutineScope
) : Feature {
    override val name: String = "agent"

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val AGENT_CONFIG_FILENAME = "agent.json"

    // --- REDUCER (Pure State Logic) ---

    override fun reducer(state: AppState, action: Action): AppState {
        val currentFeatureState = state.featureStates[name] as? AgentRuntimeState ?: AgentRuntimeState()

        val newFeatureState = when (action.name) {
            "agent.CREATE" -> handleCreateAgent(action, currentFeatureState)
            "agent.DELETE" -> handleDeleteAgent(action, currentFeatureState)
            "agent.UPDATE_CONFIG" -> handleUpdateConfig(action, currentFeatureState)
            "session.DELETE" -> handleSessionDeleted(action, currentFeatureState)
            "agent.internal.SET_STATUS" -> handleSetStatus(action, currentFeatureState)
            "agent.internal.AGENT_LOADED" -> handleAgentLoaded(action, currentFeatureState)
            "agent.SET_EDITING" -> handleSetEditing(action, currentFeatureState)
            else -> null
        }

        return newFeatureState?.let {
            state.copy(featureStates = state.featureStates + (name to it))
        } ?: state
    }

    private fun handleCreateAgent(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState {
        val payload = action.payload ?: return currentFeatureState
        val agentName = payload["name"]?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
        val personaId = payload["personaId"]?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
        val modelProvider = payload["modelProvider"]?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
        val modelName = payload["modelName"]?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
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
        return currentFeatureState.copy(agents = newAgents)
    }

    private fun handleDeleteAgent(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState {
        val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
        val newAgents = currentFeatureState.agents.filterKeys { it != agentId }
        return currentFeatureState.copy(agents = newAgents)
    }

    private fun handleUpdateConfig(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState {
        val payload = action.payload ?: return currentFeatureState
        val agentId = payload["agentId"]?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
        val agentToUpdate = currentFeatureState.agents[agentId] ?: return currentFeatureState

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

    private fun handleSessionDeleted(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState {
        val deletedSessionId = action.payload?.get("sessionId")?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
        val newAgents = currentFeatureState.agents.mapValues { (_, agent) ->
            if (agent.primarySessionId == deletedSessionId) agent.copy(primarySessionId = null) else agent
        }
        if (newAgents == currentFeatureState.agents) return currentFeatureState
        return currentFeatureState.copy(agents = newAgents)
    }

    private fun handleSetStatus(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState {
        val payload = action.payload ?: return currentFeatureState
        val agentId = payload["agentId"]?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
        val agentToUpdate = currentFeatureState.agents[agentId] ?: return currentFeatureState

        val newStatusString = payload["status"]?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
        val newStatus = try { AgentStatus.valueOf(newStatusString) } catch (e: Exception) { return currentFeatureState }

        val newErrorMessage = if (newStatus == AgentStatus.ERROR) {
            payload["error"]?.jsonPrimitive?.contentOrNull
        } else {
            null // Clear error message for any non-error status
        }

        val updatedAgent = agentToUpdate.copy(status = newStatus, errorMessage = newErrorMessage)
        val newAgents = currentFeatureState.agents + (agentId to updatedAgent)
        return currentFeatureState.copy(agents = newAgents)
    }

    private fun handleAgentLoaded(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState {
        val agent = action.payload?.let { json.decodeFromJsonElement<AgentInstance>(it) } ?: return currentFeatureState
        // Avoid overwriting an agent that might already be in memory.
        if (currentFeatureState.agents.containsKey(agent.id)) return currentFeatureState
        val newAgents = currentFeatureState.agents + (agent.id to agent)
        return currentFeatureState.copy(agents = newAgents)
    }

    private fun handleSetEditing(action: Action, currentFeatureState: AgentRuntimeState): AgentRuntimeState {
        val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull
        // If the ID is the same as the current editing ID, toggle it off.
        val nextId = if (agentId == currentFeatureState.editingAgentId) null else agentId
        return currentFeatureState.copy(editingAgentId = nextId)
    }


    // --- ON_ACTION (Side Effect Orchestration) ---

    override fun onAction(action: Action, store: Store) {
        when (action.name) {
            "system.STARTING" -> {
                // Request a listing of our root sandbox to discover persisted agent directories.
                store.dispatch(this.name, Action("filesystem.SYSTEM_LIST"))
            }
            "agent.CREATE", "agent.UPDATE_CONFIG" -> {
                // After the reducer has updated the state, persist the relevant agent.json.
                val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull
                // For CREATE, the agentId isn't in the payload, so we find the newest one.
                    ?: agentState.agents.keys.lastOrNull()
                    ?: return
                val agentToSave = agentState.agents[agentId] ?: return

                val fileContent = json.encodeToString(AgentInstance.serializer(), agentToSave)
                val subpath = "${agentToSave.id}/$AGENT_CONFIG_FILENAME"

                store.dispatch(this.name, Action("filesystem.SYSTEM_WRITE", buildJsonObject {
                    put("subpath", subpath)
                    put("content", fileContent)
                }))
                broadcastUpdate(store)
            }
            "agent.DELETE" -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                // Request to delete the entire agent's sandbox directory.
                store.dispatch(this.name, Action("filesystem.SYSTEM_DELETE_DIRECTORY", buildJsonObject {
                    put("subpath", agentId)
                }))
                broadcastUpdate(store)
            }
            "session.DELETE" -> {
                // If a session was deleted, we need to re-save any agents that were subscribed to it.
                val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
                val deletedSessionId = action.payload?.get("sessionId")?.jsonPrimitive?.contentOrNull ?: return
                agentState.agents.values
                    .filter { it.primarySessionId == deletedSessionId }
                    .forEach { agentToUpdate ->
                        val fileContent = json.encodeToString(AgentInstance.serializer(), agentToUpdate)
                        val subpath = "${agentToUpdate.id}/$AGENT_CONFIG_FILENAME"
                        store.dispatch(this.name, Action("filesystem.SYSTEM_WRITE", buildJsonObject {
                            put("subpath", subpath)
                            put("content", fileContent)
                        }))
                    }
                broadcastUpdate(store)
            }
            "agent.TRIGGER_MANUAL_TURN" -> beginCognitiveCycle(action, store)
        }
    }

    private fun broadcastUpdate(store: Store) {
        val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
        val payload = json.encodeToJsonElement(agentState).jsonObject
        store.dispatch(this.name, Action("agent.publish.UPDATED", payload))
    }

    private fun beginCognitiveCycle(action: Action, store: Store) {
        val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return

        val appState = store.state.value
        val agentState = appState.featureStates[name] as? AgentRuntimeState ?: return
        val sessionState = appState.featureStates["session"] as? SessionState ?: return
        val agent = agentState.agents[agentId] ?: return

        // Allow re-triggering if the agent is IDLE or in an ERROR state.
        if (agent.status == AgentStatus.PROCESSING || agent.status == AgentStatus.WAITING) return

        val targetSessionId = agent.primarySessionId
        if (targetSessionId == null) {
            val errorMsg = "Cannot start turn: Agent is not subscribed to a primary session."
            postErrorMessage(agent, errorMsg, store)
            setAgentStatus(agentId, AgentStatus.ERROR, store, errorMsg); return
        }

        val targetSession = sessionState.sessions[targetSessionId]
        if (targetSession == null || targetSession.ledger.isEmpty()) {
            val errorMsg = "Cannot start turn: Primary session is empty or not found."
            postErrorMessage(agent, errorMsg, store)
            setAgentStatus(agentId, AgentStatus.ERROR, store, errorMsg); return
        }

        setAgentStatus(agentId, AgentStatus.PROCESSING, store)

        // CORRECTED: Build a universal message list, not a provider-specific JSON object.
        val lastMessage = targetSession.ledger.last().rawContent
        val messages = listOf(GatewayMessage("user", lastMessage))

        val gatewayPayload = buildJsonObject {
            put("providerId", agent.modelProvider)
            put("modelName", agent.modelName)
            put("correlationId", agent.id)
            put("contents", Json.encodeToJsonElement(messages))
        }
        store.dispatch(this.name, Action("gateway.GENERATE_CONTENT", gatewayPayload))
    }

    // --- ON_PRIVATE_DATA (Async Data Handling) ---

    override fun onPrivateData(data: Any, store: Store) {
        when (data) {
            is List<*> -> { // Case 1: Received directory listing from FileSystemFeature
                val fileEntries = data.filterIsInstance<FileEntry>()
                fileEntries.filter { it.isDirectory }.forEach { dirEntry ->
                    val agentId = platformDependencies.getFileName(dirEntry.path)
                    val configPath = "$agentId/$AGENT_CONFIG_FILENAME"
                    store.dispatch(this.name, Action("filesystem.SYSTEM_READ", buildJsonObject {
                        put("subpath", configPath)
                    }))
                }
            }
            is JsonObject -> { // Case 2: Received agent.json file content from FileSystemFeature
                val content = data["content"]?.jsonPrimitive?.contentOrNull ?: return
                try {
                    val agent = json.decodeFromString<AgentInstance>(content)
                    // Dispatch an internal action to load this agent into the state via the reducer.
                    store.dispatch(this.name, Action("agent.internal.AGENT_LOADED", Json.encodeToJsonElement(agent) as JsonObject))
                } catch (e: Exception) {
                    platformDependencies.log(
                        level = LogLevel.ERROR,
                        tag = name,
                        message = "Failed to parse agent config file: ${data["subpath"]}. Error: ${e.message}"
                    )
                }
            }
            is GatewayResponse -> { // Case 3: Received response from GatewayFeature
                handleGatewayResponse(data, store)
            }
        }
    }

    private fun handleGatewayResponse(data: GatewayResponse, store: Store) {
        val agentId = data.correlationId
        val agent = (store.state.value.featureStates[name] as? AgentRuntimeState)?.agents?.get(agentId)

        if (agent == null) {
            platformDependencies.log(LogLevel.WARN, name, "Received GatewayResponse for deleted agent '$agentId'. Discarding.")
            return
        }

        if (data.errorMessage != null) {
            val errorMessage = "[AGENT ERROR] Generation failed: ${data.errorMessage}"
            postErrorMessage(agent, errorMessage, store)
            setAgentStatus(agentId, AgentStatus.ERROR, store, errorMessage)
        } else {
            val responseContent = data.rawContent ?: "[AGENT ERROR] Received empty response from gateway."
            val postPayload = buildJsonObject {
                put("sessionId", agent.primarySessionId)
                put("agentId", agent.id)
                put("message", responseContent)
            }
            store.dispatch(this.name, Action("session.POST", postPayload))
            setAgentStatus(agentId, AgentStatus.IDLE, store)
        }
    }

    private fun setAgentStatus(agentId: String, status: AgentStatus, store: Store, error: String? = null) {
        val payload = buildJsonObject {
            put("agentId", agentId)
            put("status", status.name)
            if (error != null) {
                put("error", error)
            }
        }
        store.dispatch(this.name, Action("agent.internal.SET_STATUS", payload))
    }

    private fun postErrorMessage(agent: AgentInstance, message: String, store: Store) {
        if (agent.primarySessionId == null) return
        val postPayload = buildJsonObject {
            put("sessionId", agent.primarySessionId)
            put("agentId", agent.id)
            put("message", message)
        }
        store.dispatch(this.name, Action("session.POST", postPayload))
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
                onClick = {
                    val payload = buildJsonObject { put("key", viewKey) }
                    store.dispatch("ui.ribbon", Action("core.SET_ACTIVE_VIEW", payload))
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = "Agent Manager",
                    tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}