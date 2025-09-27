package app.auf.feature.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.auf.core.*
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// --- INTERNAL ACTIONS ---
internal interface AgentRuntimeAction

internal data class SetGateways(val gateways: Map<String, GatewayInfo>) : AgentRuntimeAction, Event
internal data class AddAgent(val agent: AgentRuntimeState) : AgentRuntimeAction, Event
internal data class UpdateAgentConfig(val agentId: String, val gatewayId: String, val selectedModelId: String, val hkgPersonaId: String?) : AgentRuntimeAction, Event
internal data class RemoveAgent(val agentId: String) : AgentRuntimeAction, Event
internal data class StartProcessing(val agentId: String, val turnId: String, val parentEntryId: String, val job: Job) : AgentRuntimeAction, Event
internal data class FinishProcessing(val agentId: String) : AgentRuntimeAction, Event
internal data class SetActiveAgentForManager(val agentId: String?) : AgentRuntimeAction, Event


// --- THE FEATURE (MANAGER) ---

class AgentRuntimeFeature(
    private val agentGateway: AgentGateway,
    private val platform: PlatformDependencies,
    private val coroutineScope: CoroutineScope
) : Feature {

    override val name: String = "AgentRuntimeFeature"
    private var store: Store? = null
    override val composableProvider: Feature.ComposableProvider = AgentRuntimeComposableProvider()

    // --- REDUCER (Unchanged) ---
    override fun reducer(state: AppState, action: AppAction): AppState {
        val featureState = state.featureStates[name] as? AgentRuntimeFeatureState ?: return state

        val newFeatureState = when (action) {
            is AgentCommand.TurnCancelled -> handleTurnCancellation(action, featureState)
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

        return featureState
    }


    // --- SIDE EFFECT ORCHESTRATION ---
    override fun start(store: Store) {
        this.store = store
        val agentId = "janitor-agent" // Hardcoded for now

        coroutineScope.launch {
            // This coroutine now bootstraps the agent if it doesn't exist
            // NOTE: This is a placeholder for proper agent creation and management
            val agentExists = (store.state.value.featureStates[name] as? AgentRuntimeFeatureState)
                ?.agents?.containsKey(agentId) == true
            if (!agentExists) {
                store.dispatch(AddAgent(AgentRuntimeState(
                    id = agentId,
                    archetypeId = "auf.janitor",
                    displayName = "Janitor",
                    gatewayId = "gemini",
                    selectedModelId = "gemini-pro"
                )))
            }
        }

        coroutineScope.launch {
            // --- FIX IMPLEMENTED ---
            // This collector now observes a more robust condition:
            // "Does an agent with our target ID exist, AND is its turn Idle?"
            store.state
                .map { state ->
                    val featureState = state.featureStates[name] as? AgentRuntimeFeatureState
                    featureState?.agents?.get(agentId)?.turn is AgentTurn.Idle
                }
                .distinctUntilChanged()
                .collect { isAgentReady ->
                    // This will now fire `true` as soon as the agent is added and idle.
                    if (isAgentReady == true) {
                        println("[AgentRuntimeFeature] STIMULUS DETECTED: Agent '$agentId' is ready. Triggering turn.")
                        triggerAgentTurn(store, agentId)
                    }
                }
        }
    }

    private fun triggerAgentTurn(store: Store, agentId: String) {
        val turnId = platform.generateUUID()
        val parentEntryId = ""

        val agentJob = coroutineScope.launch {
            // A small delay to ensure this doesn't execute in the same dispatcher cycle
            // as the state change that triggered it, preventing potential re-trigger loops.
            // NOTE: This is a horrible hack and unstable as hell
            delay(1)
            val resultBlocks = listOf(TextBlock("This is the result of the agent's work for turn $turnId."))
            store.dispatch(AgentEvent.TurnCompleted(turnId, resultBlocks))
            store.dispatch(FinishProcessing(agentId))
            println("[AgentRuntimeFeature] Turn $turnId completed and dispatched.")
        }

        store.dispatch(StartProcessing(agentId, turnId, parentEntryId, agentJob))
        store.dispatch(AgentEvent.TurnBegan(
            rendererFeatureName = this.name,
            turnId = turnId,
            parentEntryId = parentEntryId.ifEmpty { null }
        ))
        println("[AgentRuntimeFeature] Turn $turnId began and dispatched with renderer feature name '${this.name}'.")
    }

    // --- UI PROVIDER ---
    inner class AgentRuntimeComposableProvider : Feature.ComposableProvider {
        override val viewKey: String = "feature.agent.manager"

        @Composable
        override fun RibbonButton(stateManager: StateManager, isActive: Boolean) {
            IconButton(onClick = { stateManager.dispatch(SetActiveView(viewKey)) }) {
                Icon(
                    imageVector = Icons.Default.DataObject,
                    contentDescription = "Agent Manager",
                    tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        @Composable
        override fun StageContent(stateManager: StateManager) {
            AgentManagerView(stateManager)
        }

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