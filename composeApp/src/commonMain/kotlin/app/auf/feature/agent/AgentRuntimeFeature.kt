package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.Feature
import app.auf.core.Store
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

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
        // --- FIX: Renamed local variable to avoid shadowing the feature's 'name' property ---
        val agentName = payload["name"]?.jsonPrimitive?.contentOrNull ?: return state
        val personaId = payload["personaId"]?.jsonPrimitive?.contentOrNull ?: return state
        val modelProvider = payload["modelProvider"]?.jsonPrimitive?.contentOrNull ?: return state
        val modelName = payload["modelName"]?.jsonPrimitive?.contentOrNull ?: return state
        val primarySessionId = payload["primarySessionId"]?.jsonPrimitive?.contentOrNull

        val newAgentId = platformDependencies.generateUUID()
        val newAgent = AgentInstance(
            id = newAgentId,
            name = agentName, // Use the correctly named variable
            personaId = personaId,
            modelProvider = modelProvider,
            modelName = modelName,
            primarySessionId = primarySessionId,
            status = AgentStatus.IDLE
        )

        val newAgents = currentFeatureState.agents + (newAgentId to newAgent)
        val newFeatureState = currentFeatureState.copy(agents = newAgents)
        // --- FIX: This now correctly uses the feature's 'name' property ("agent") as the key ---
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

    /**
     * Handles side effects. To be implemented in the next slice.
     */
    override fun onAction(action: Action, store: Store) {
        // Orchestration logic will be implemented here.
    }

    /**
     * Handles private data responses. To be implemented in the next slice.
     */
    override fun onPrivateData(data: Any, store: Store) {
        // Response handling logic will be implemented here.
    }
}
