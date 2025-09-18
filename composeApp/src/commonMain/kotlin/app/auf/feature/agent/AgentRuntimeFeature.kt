package app.auf.feature.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.auf.core.*
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.*

// --- 1. REVISED: INTERNAL ACTIONS USING MARKER INTERFACE PATTERN ---
interface AgentRuntimeAction

private data class SetGateways(val gateways: Map<String, GatewayInfo>) : AgentRuntimeAction, Event
private data class AddAgent(val agent: AgentRuntimeState) : AgentRuntimeAction, Event
private data class UpdateAgentConfig(val agentId: String, val gatewayId: String, val selectedModelId: String, val hkgPersonaId: String?) : AgentRuntimeAction, Event
private data class RemoveAgent(val agentId: String) : AgentRuntimeAction, Event
private data class StartProcessing(val agentId: String, val turnId: String, val parentEntryId: String, val job: Job) : AgentRuntimeAction, Event
private data class FinishProcessing(val agentId: String) : AgentRuntimeAction, Event
private data class SetActiveAgentForManager(val agentId: String?) : AgentRuntimeAction, Event


// --- 2. THE FEATURE (MANAGER) ---

class AgentRuntimeFeature(
    private val agentGateway: AgentGateway, // Will become a list/map later
    private val platform: PlatformDependencies,
    private val coroutineScope: CoroutineScope
) : Feature {

    override val name: String = "AgentRuntimeFeature"
    private var store: Store? = null
    override val composableProvider: Feature.ComposableProvider = AgentRuntimeComposableProvider()

    // --- NEW: MULTI-AGENT REDUCER ---
    override fun reducer(state: AppState, action: AppAction): AppState {
        val featureState = state.featureStates[name] as? AgentRuntimeFeatureState ?: return state

        val newFeatureState = when (action) {
            // --- GLOBAL COMMANDS ---
            is AgentCommand.TurnCancelled -> handleTurnCancellation(action, featureState)
            // --- INTERNAL EVENTS ---
            is AgentRuntimeAction -> handleInternalActions(action, featureState)
            else -> featureState
        }

        return if (newFeatureState != featureState) {
            state.copy(featureStates = state.featureStates + (name to newFeatureState))
        } else {
            state
        }
    }

    private fun handleInternalActions(action: AgentRuntimeAction, featureState: AgentRuntimeFeatureState): AgentRuntimeFeatureState {
        return when (action) {
            is SetGateways -> featureState.copy(gateways = action.gateways)
            is AddAgent -> featureState.copy(agents = featureState.agents + (action.agent.id to action.agent))
            is RemoveAgent -> featureState.copy(agents = featureState.agents - action.agentId)
            is SetActiveAgentForManager -> featureState.copy(activeAgentIdForManager = action.agentId)
            is UpdateAgentConfig -> {
                val agent = featureState.agents[action.agentId] ?: return featureState
                val updatedAgent = agent.copy(
                    gatewayId = action.gatewayId,
                    selectedModelId = action.selectedModelId,
                    hkgPersonaId = action.hkgPersonaId
                )
                featureState.copy(agents = featureState.agents + (action.agentId to updatedAgent))
            }
            is StartProcessing -> {
                val agent = featureState.agents[action.agentId] ?: return featureState
                if (agent.turn !is AgentTurn.Idle) return featureState // Guard
                val updatedAgent = agent.copy(turn = AgentTurn.Processing(action.turnId, action.parentEntryId, action.job))
                featureState.copy(agents = featureState.agents + (action.agentId to updatedAgent))
            }
            is FinishProcessing -> {
                val agent = featureState.agents[action.agentId] ?: return featureState
                if (agent.turn !is AgentTurn.Processing) return featureState // Guard
                val updatedAgent = agent.copy(turn = AgentTurn.Idle)
                featureState.copy(agents = featureState.agents + (action.agentId to updatedAgent))
            }
            else -> featureState
        }
    }

    private fun handleTurnCancellation(action: AgentCommand.TurnCancelled, featureState: AgentRuntimeFeatureState): AgentRuntimeFeatureState {
        val agentToCancel = featureState.agents.values.find {
            (it.turn as? AgentTurn.Processing)?.turnId == action.turnId
        }

        agentToCancel?.let { agent ->
            (agent.turn as? AgentTurn.Processing)?.job?.cancel()
            val updatedAgent = agent.copy(turn = AgentTurn.Idle)
            return featureState.copy(agents = featureState.agents + (agent.id to updatedAgent))
        }

        return featureState // No agent found for that turn
    }


    // --- STUBBED: SIDE EFFECT ORCHESTRATION ---
    override fun start(store: Store) {
        this.store = store
        println("AgentRuntimeFeature.start() called. Side effect logic is not yet implemented.")
    }

    // --- STUBBED: UI ---
    @Composable
    fun AgentManagerView(stateManager: StateManager) {
        Text("Agent Manager View - To be implemented")
    }

    // --- COMPOSABLE PROVIDER ---
    inner class AgentRuntimeComposableProvider : Feature.ComposableProvider {
        @Composable
        override fun TurnView(stateManager: StateManager, turnId: String) {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("$name is processing turn $turnId...")
                    CircularProgressIndicator()
                    Button(onClick = {
                        stateManager.dispatch(AgentCommand.TurnCancelled(turnId))
                    }) {
                        Text("Stop")
                    }
                }
            }
        }
    }
}