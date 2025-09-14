package app.auf.feature.agent

import androidx.compose.runtime.*
import app.auf.core.*
import app.auf.model.SettingDefinition
import app.auf.model.SettingType
import app.auf.model.SettingValue
import kotlinx.coroutines.*

// --- 1. ACTIONS ---
// (No changes from previous version)
internal sealed interface AgentRuntimeAction : AppAction {
    data object PrimeResponder : AgentRuntimeAction
    data class _UpdateStatus(val status: AgentStatus, val primedAt: Long? = null, val lastEntryAt: Long? = null) : AgentRuntimeAction
    data object _DebounceTimerExpired : AgentRuntimeAction
    // Actions for UI interaction, migrated from HkgAgentFeature
    data class CreateAgent(val sessionId: String, val agentId: String, val hkgPersonaId: String? = null) : AgentRuntimeAction
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
    private var debounceJob: Job? = null

    // MOVED: The ComposableProvider is now part of the new runtime feature.
    override val composableProvider: Feature.ComposableProvider = AgentRuntimeComposableProvider()

    override fun reducer(state: AppState, action: AppAction): AppState {
        if (action !is AgentRuntimeAction) return state

        val featureState = state.featureStates[name] as? AgentRuntimeFeatureState ?: AgentRuntimeFeatureState()
        val agent = featureState.agent

        val newFeatureState = when (action) {
            is AgentRuntimeAction.CreateAgent -> {
                val newAgent = AgentRuntimeState(id = action.agentId, sessionId = action.sessionId, hkgPersonaId = action.hkgPersonaId)
                featureState.copy(agent = newAgent)
            }
            // ... (All other reducer cases would be migrated and updated here to use `featureState.copy(agent = agent.copy(...))`)
            // For brevity, the core state machine logic is shown:
            is AgentRuntimeAction.PrimeResponder -> {
                if (agent == null) return state
                val currentTime = platform.getSystemTimeMillis()
                val newPrimedAt = if (agent.status == AgentStatus.WAITING) currentTime else (agent.primedAt ?: currentTime)
                featureState.copy(agent = agent.copy(status = AgentStatus.PRIMED, primedAt = newPrimedAt, lastEntryAt = currentTime))
            }
            is AgentRuntimeAction._UpdateStatus -> {
                if (agent == null) return state
                featureState.copy(agent = agent.copy(status = action.status, primedAt = action.primedAt, lastEntryAt = action.lastEntryAt))
            }
            is AgentRuntimeAction._DebounceTimerExpired -> {
                if (agent == null || agent.status != AgentStatus.PRIMED) return state
                featureState.copy(agent = agent.copy(status = AgentStatus.PROCESSING, primedAt = null, lastEntryAt = null))
            }
            else -> featureState
        }
        return state.copy(featureStates = state.featureStates + (name to newFeatureState))
    }

    override fun start(store: Store) {
        this.store = store
        // (The Master Stimulus and Executor collectors remain the same as the previous version)
        // ...
    }

    // (_handleStimulus, _runConversationalLogic, _manageDebounceTimer, _executeAgentResponse, _buildRequest)
    // ... (These private methods remain the same, but would now get the agent state from `(state.featureStates[name] as? AgentRuntimeFeatureState)?.agent`)

    // MOVED AND ADAPTED: The UI provider is now an inner class of the runtime.
    inner class AgentRuntimeComposableProvider : Feature.ComposableProvider {
        override val settingDefinitions: List<SettingDefinition> = listOf(
            SettingDefinition("compiler.removeWhitespace", "Prompt Compiler", "Remove extraneous whitespace", "...", SettingType.BOOLEAN),
            // ... (All other setting definitions from HkgAgentFeature)
        )

        override fun getSettingValue(state: AppState, key: String): Any? {
            val agent = (state.featureStates[name] as? AgentRuntimeFeatureState)?.agent ?: return null
            return when (key) {
                "compiler.removeWhitespace" -> agent.compilerSettings.removeWhitespace
                // ... (All other setting value getters)
                else -> null
            }
        }

        @Composable
        override fun SessionHeader(stateManager: StateManager) {
            // This UI code would be migrated here from HkgAgentFeature,
            // using the new AgentRuntimeFeatureState and dispatching AgentRuntimeActions.
        }
    }
}