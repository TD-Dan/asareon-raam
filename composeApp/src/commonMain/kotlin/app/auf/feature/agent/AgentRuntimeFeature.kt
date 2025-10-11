package app.auf.feature.agent

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import app.auf.core.*
import app.auf.core.Feature.ComposableProvider
import app.auf.feature.gateway.GatewayResponse
import app.auf.feature.session.SessionState
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.*

class AgentRuntimeFeature(
    private val platformDependencies: PlatformDependencies,
    private val coroutineScope: CoroutineScope
) : Feature {
    override val name: String = "agent"

    // A private, lenient JSON instance for safe payload decoding.
    private val json = Json { ignoreUnknownKeys = true }

    override fun reducer(state: AppState, action: Action): AppState {
        val currentFeatureState = state.featureStates[name] as? AgentRuntimeState ?: AgentRuntimeState()

        return when (action.name) {
            "agent.CREATE" -> handleCreateAgent(action, currentFeatureState, state)
            "agent.DELETE" -> handleDeleteAgent(action, currentFeatureState, state)
            "agent.UPDATE_CONFIG" -> handleUpdateConfig(action, currentFeatureState, state)
            "session.DELETE" -> handleSessionDeleted(action, currentFeatureState, state)
            // Internal actions for managing the cognitive cycle state
            "agent.internal.SET_STATUS" -> handleSetStatus(action, currentFeatureState, state)
            else -> state
        }
    }

    private fun handleCreateAgent(action: Action, currentFeatureState: AgentRuntimeState, state: AppState): AppState {
        val payload = action.payload ?: return state
        val agentName = payload["name"]?.jsonPrimitive?.contentOrNull ?: return state
        val personaId = payload["personaId"]?.jsonPrimitive?.contentOrNull ?: return state
        val modelProvider = payload["modelProvider"]?.jsonPrimitive?.contentOrNull ?: return state
        val modelName = payload["modelName"]?.jsonPrimitive?.contentOrNull ?: return state
        val primarySessionId = payload["primarySessionId"]?.jsonPrimitive?.contentOrNull

        val newAgentId = platformDependencies.generateUUID()
        val newAgent = AgentInstance(
            id = newAgentId,
            name = agentName,
            personaId = personaId,
            modelProvider = modelProvider,
            modelName = modelName,
            primarySessionId = primarySessionId,
            status = AgentStatus.IDLE
        )

        val newAgents = currentFeatureState.agents + (newAgentId to newAgent)
        val newFeatureState = currentFeatureState.copy(agents = newAgents)
        return state.copy(featureStates = state.featureStates + (name to newFeatureState))
    }

    private fun handleDeleteAgent(action: Action, currentFeatureState: AgentRuntimeState, state: AppState): AppState {
        val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return state
        val newAgents = currentFeatureState.agents.filterKeys { it != agentId }
        val newFeatureState = currentFeatureState.copy(agents = newAgents)
        return state.copy(featureStates = state.featureStates + (name to newFeatureState))
    }

    private fun handleUpdateConfig(action: Action, currentFeatureState: AgentRuntimeState, state: AppState): AppState {
        val payload = action.payload ?: return state
        val agentId = payload["agentId"]?.jsonPrimitive?.contentOrNull ?: return state
        val agentToUpdate = currentFeatureState.agents[agentId] ?: return state

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
        val newFeatureState = currentFeatureState.copy(agents = newAgents)
        return state.copy(featureStates = state.featureStates + (name to newFeatureState))
    }

    private fun handleSessionDeleted(action: Action, currentFeatureState: AgentRuntimeState, state: AppState): AppState {
        val deletedSessionId = action.payload?.get("sessionId")?.jsonPrimitive?.contentOrNull ?: return state

        val newAgents = currentFeatureState.agents.mapValues { (_, agent) ->
            if (agent.primarySessionId == deletedSessionId) {
                agent.copy(primarySessionId = null)
            } else {
                agent
            }
        }

        if (newAgents == currentFeatureState.agents) return state

        val newFeatureState = currentFeatureState.copy(agents = newAgents)
        return state.copy(featureStates = state.featureStates + (name to newFeatureState))
    }

    private fun handleSetStatus(action: Action, currentFeatureState: AgentRuntimeState, state: AppState): AppState {
        val payload = action.payload ?: return state
        val agentId = payload["agentId"]?.jsonPrimitive?.contentOrNull ?: return state
        val agentToUpdate = currentFeatureState.agents[agentId] ?: return state
        val newStatus = try {
            json.decodeFromJsonElement<AgentStatus>(payload["status"] ?: return state)
        } catch (e: Exception) {
            return state // Ignore malformed status
        }

        val updatedAgent = agentToUpdate.copy(status = newStatus)
        val newAgents = currentFeatureState.agents + (agentId to updatedAgent)
        val newFeatureState = currentFeatureState.copy(agents = newAgents)
        return state.copy(featureStates = state.featureStates + (name to newFeatureState))
    }

    override fun onAction(action: Action, store: Store) {
        when (action.name) {
            "agent.TRIGGER_MANUAL_TURN" -> beginCognitiveCycle(action, store)
            "agent.CREATE", "agent.DELETE", "agent.UPDATE_CONFIG", "session.DELETE" -> {
                // After our state has been changed by the reducer, broadcast the update.
                val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
                val payload = json.encodeToJsonElement(agentState).jsonObject
                store.dispatch(this.name, Action("agent.UPDATED", payload))
            }
        }
    }

    private fun beginCognitiveCycle(action: Action, store: Store) {
        val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return

        val appState = store.state.value
        val agentState = appState.featureStates[name] as? AgentRuntimeState ?: return
        val sessionState = appState.featureStates["session"] as? SessionState ?: return
        val agent = agentState.agents[agentId] ?: return

        if (agent.status != AgentStatus.IDLE) {
            return
        }

        val targetSessionId = agent.primarySessionId
        if (targetSessionId == null) {
            postErrorMessage(agent, "Cannot start turn: Agent is not subscribed to a primary session.", store)
            setAgentStatus(agentId, AgentStatus.ERROR, store)
            return
        }

        val targetSession = sessionState.sessions[targetSessionId]
        if (targetSession == null || targetSession.ledger.isEmpty()) {
            postErrorMessage(agent, "Cannot start turn: Primary session is empty or not found.", store)
            setAgentStatus(agentId, AgentStatus.ERROR, store)
            return
        }

        setAgentStatus(agentId, AgentStatus.PROCESSING, store)

        val lastMessage = targetSession.ledger.last().rawContent
        val contentsPayload = buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("parts", buildJsonArray {
                    add(buildJsonObject {
                        put("text", lastMessage)
                    })
                })
            })
        }

        val gatewayPayload = buildJsonObject {
            put("providerId", agent.modelProvider)
            put("modelName", agent.modelName)
            put("correlationId", agent.id)
            put("contents", contentsPayload)
        }
        store.dispatch(this.name, Action("gateway.GENERATE_CONTENT", gatewayPayload))
    }

    override fun onPrivateData(data: Any, store: Store) {
        if (data !is GatewayResponse) return

        val agentId = data.correlationId
        val agent = (store.state.value.featureStates[name] as? AgentRuntimeState)?.agents?.get(agentId)

        if (agent == null) {
            platformDependencies.log(
                level = app.auf.util.LogLevel.WARN,
                tag = name,
                message = "Received GatewayResponse for deleted agent '$agentId'. Discarding."
            )
            return
        }

        if (data.errorMessage != null) {
            val errorMessage = "[AGENT ERROR] Generation failed: ${data.errorMessage}"
            postErrorMessage(agent, errorMessage, store)
            setAgentStatus(agentId, AgentStatus.ERROR, store)
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

    private fun setAgentStatus(agentId: String, status: AgentStatus, store: Store) {
        val payload = buildJsonObject {
            put("agentId", agentId)
            put("status", Json.encodeToJsonElement(status))
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
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = "Agent Manager",
                    tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}