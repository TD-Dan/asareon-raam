package app.auf.feature.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import app.auf.core.*
import app.auf.model.SettingDefinition
import app.auf.model.SettingType
import app.auf.model.SettingValue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// --- 1. ACTIONS ---
// Actions are now a mix of internal state updates and external configuration commands.
internal sealed interface AgentRuntimeAction : AppAction {
    // Internal state machine actions
    data class _UpdateStatus(val status: AgentStatus) : AgentRuntimeAction
    data class _SetActiveTurn(val turnId: String?) : AgentRuntimeAction

    // Configuration actions from UI
    data class SelectHkgPersona(val hkgPersonaId: String?) : AgentRuntimeAction
    data class SelectModel(val modelName: String) : AgentRuntimeAction
    data class UpdateCompilerSetting(val setting: SettingValue) : AgentRuntimeAction
    data class UpdateTimingSetting(val setting: SettingValue) : AgentRuntimeAction
    data class SetAvailableModels(val models: List<String>) : AgentRuntimeAction
}


// --- 2. THE FEATURE ---

class AgentRuntimeFeature(
    private val agentGateway: AgentGateway,
    private val platform: app.auf.util.PlatformDependencies,
    private val coroutineScope: CoroutineScope
) : Feature {

    override val name: String = "AgentRuntimeFeature"
    private var store: Store? = null
    private var processingJob: Job? = null

    override val composableProvider: Feature.ComposableProvider = AgentRuntimeComposableProvider()

    override fun reducer(state: AppState, action: AppAction): AppState {
        if (action !is AgentRuntimeAction && action !is AgentAction.TurnCancelled) return state

        val featureState = state.featureStates[name] as? AgentRuntimeFeatureState ?: return state
        val agent = featureState.agent

        val newAgentState = when (action) {
            // Internal State
            is AgentRuntimeAction._UpdateStatus -> agent.copy(status = action.status)
            is AgentRuntimeAction._SetActiveTurn -> agent.copy(activeTurnId = action.turnId)

            // Configuration
            is AgentRuntimeAction.SelectHkgPersona -> agent.copy(hkgPersonaId = action.hkgPersonaId)
            is AgentRuntimeAction.SelectModel -> agent.copy(modelName = action.modelName)
            is AgentRuntimeAction.SetAvailableModels -> agent.copy(availableModels = action.models)
            is AgentRuntimeAction.UpdateCompilerSetting -> {
                val newSettings = when (action.setting.key) {
                    "compiler.removeWhitespace" -> agent.compilerSettings.copy(removeWhitespace = action.setting.value as Boolean)
                    // ... other compiler settings
                    else -> agent.compilerSettings
                }
                agent.copy(compilerSettings = newSettings)
            }
            is AgentRuntimeAction.UpdateTimingSetting -> {
                val newTimings = when (action.setting.key) {
                    "timing.debounceMs" -> agent.timingSettings.copy(debounceMs = (action.setting.value as Float).toLong())
                    // ... other timing settings
                    else -> agent.timingSettings
                }
                agent.copy(timingSettings = newTimings)
            }
            // Handle cancellation
            is AgentAction.TurnCancelled -> {
                if (agent.activeTurnId == action.turnId) {
                    processingJob?.cancel()
                    agent.copy(activeTurnId = null, status = AgentStatus.WAITING)
                } else {
                    agent
                }
            }
            else -> agent
        }

        return state.copy(featureStates = state.featureStates + (name to featureState.copy(agent = newAgentState)))
    }

    override fun start(store: Store) {
        this.store = store

        // MASTER STIMULUS & EXECUTOR
        coroutineScope.launch {
            store.stateFlow.map { (it.featureStates[name] as? AgentRuntimeFeatureState)?.agent }
                .distinctUntilChanged()
                .collect { agent ->
                    if (agent == null) return@collect

                    // This logic would contain the stimulus detection (e.g., new message added, debounce timer, etc.)
                    // For now, we simulate a trigger when status is PRIMED.
                    if (agent.status == AgentStatus.PRIMED && agent.activeTurnId == null) {
                        store.dispatch(AgentRuntimeAction._UpdateStatus(AgentStatus.PROCESSING))
                        val newTurnId = platform.generateUUID()
                        store.dispatch(AgentRuntimeAction._SetActiveTurn(newTurnId))
                        store.dispatch(AgentAction.TurnBegan(name, newTurnId, parentEntryId = null))
                        processingJob = launch { _runConversationalLogic(agent, newTurnId) }
                    }
                }
        }
    }

    private suspend fun _runConversationalLogic(agent: AgentRuntimeState, turnId: String) {
        try {
            // Actual call to the gateway would be here.
            // Using a delay to simulate network latency.
            delay(2000)
            val responseContent = listOf(TextBlock("This is the agent's response."))
            store?.dispatch(AgentAction.TurnCompleted(turnId, responseContent))
        } catch (e: Exception) {
            if (e is CancellationException) {
                // On cancellation, the reducer has already cleared the state.
                // We dispatch a specific completion action so the Session can clean up the placeholder.
                store?.dispatch(AgentAction.TurnCancelled(turnId))
            } else {
                store?.dispatch(AgentAction.TurnFailed(turnId, e.message ?: "Unknown error"))
            }
        } finally {
            store?.dispatch(AgentRuntimeAction._SetActiveTurn(null))
            store?.dispatch(AgentRuntimeAction._UpdateStatus(AgentStatus.WAITING))
        }
    }

    inner class AgentRuntimeComposableProvider : Feature.ComposableProvider {
        override val settingDefinitions: List<SettingDefinition> = listOf(
            SettingDefinition("compiler.removeWhitespace", "Prompt Compiler", "Remove extraneous whitespace", "...", SettingType.BOOLEAN),
            SettingDefinition("timing.debounceMs", "Agent Timing", "Debounce time (ms)", "...", SettingType.SLIDER, min = 0f, max = 5000f),
            // ... other setting definitions
        )

        override fun getSettingValue(state: AppState, key: String): Any? {
            val agent = (state.featureStates[name] as? AgentRuntimeFeatureState)?.agent ?: return null
            return when (key) {
                "compiler.removeWhitespace" -> agent.compilerSettings.removeWhitespace
                "timing.debounceMs" -> agent.timingSettings.debounceMs.toFloat()
                // ... other setting value getters
                else -> null
            }
        }

        @Composable
        override fun TurnView(stateManager: StateManager, turnId: String) {
            val agent by stateManager.stateFlow.map {
                (it.featureStates[name] as? AgentRuntimeFeatureState)?.agent
            }.collectAsState(initial = null)

            // Only render if this turn is our active turn.
            if (agent?.activeTurnId == turnId) {
                Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Arrangement.CenterVertically
                    ) {
                        Text("${agent?.name ?: "Agent"} is processing...")
                        CircularProgressIndicator()
                        Button(onClick = {
                            stateManager.dispatch(AgentAction.TurnCancelled(turnId))
                        }) {
                            Text("Stop")
                        }
                    }
                }
            }
        }
    }
}