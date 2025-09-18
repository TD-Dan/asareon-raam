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
import app.auf.feature.session.LedgerEntry
import app.auf.feature.session.SessionFeatureState
import app.auf.model.SettingDefinition
import app.auf.model.SettingType
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// --- 1. INTERNAL ACTIONS ---
private sealed interface AgentRuntimeInternalAction : AppAction {
    data class _UpdateStatus(val status: AgentStatus) : AgentRuntimeInternalAction
    data class _SetProcessingJob(val job: Job?, val turnId: String?) : AgentRuntimeInternalAction
}

// --- 2. THE FEATURE ---

class AgentRuntimeFeature(
    private val agentGateway: AgentGateway,
    private val platform: PlatformDependencies,
    private val coroutineScope: CoroutineScope
) : Feature {

    override val name: String = "AgentRuntimeFeature"
    private var store: Store? = null
    override val composableProvider: Feature.ComposableProvider = AgentRuntimeComposableProvider()

    // NOTE: getInitialState was removed as it does not exist in the Feature contract.
    // The initial state will be created by the Store from the default AppState.

    override fun reducer(state: AppState, action: AppAction): AppState {
        val featureState = state.featureStates[name] as? AgentRuntimeFeatureState ?: return state
        var agent = featureState.agent ?: return state

        // React to the global cancellation event
        if (action is AgentAction.TurnCancelled) {
            // CORRECTED: Compare the action's turnId (String) with the agent's activeTurnId (String)
            if (agent.status == AgentStatus.PROCESSING && agent.hkgPersonaId == action.turnId) { // HACK: Using hkgPersonaId to store turnId
                (agent.lastEntryAt as? Job)?.cancel() // The Job is stored in `lastEntryAt`
                agent = agent.copy(status = AgentStatus.WAITING, hkgPersonaId = null, lastEntryAt = null)
            }
        }

        // Handle internal state changes
        if (action is AgentRuntimeInternalAction) {
            agent = when (action) {
                is AgentRuntimeInternalAction._UpdateStatus -> agent.copy(status = action.status)
                is AgentRuntimeInternalAction._SetProcessingJob -> agent.copy(
                    hkgPersonaId = action.turnId, // HACK: Re-using hkgPersonaId to store the active turnId as a String
                    lastEntryAt = action.job as? Long // HACK: Re-using lastEntryAt to store the Job.
                )
            }
        }

        return state.copy(featureStates = state.featureStates + (name to featureState.copy(agent = agent)))
    }

    override fun start(store: Store) {
        this.store = store

        // MASTER STIMULUS & EXECUTOR
        coroutineScope.launch {
            var lastProcessedEntryId: String? = null
            store.state.map {
                (it.featureStates["SessionFeature"] as? SessionFeatureState)
                    ?.sessions?.get("default-session")
                    ?.transcript
                    ?.lastOrNull { entry -> entry is LedgerEntry.Message && entry.agentId == "USER" }
            }.distinctUntilChanged().collect { latestUserEntry ->
                val agent = (store.state.value.featureStates[name] as? AgentRuntimeFeatureState)?.agent

                if (agent != null && agent.status == AgentStatus.WAITING && latestUserEntry != null && latestUserEntry.entryId != lastProcessedEntryId) {
                    lastProcessedEntryId = latestUserEntry.entryId

                    val newTurnId = platform.generateUUID()
                    val job = launch { _runConversationalLogic(agent, newTurnId) }

                    store.dispatch(AgentRuntimeInternalAction._UpdateStatus(AgentStatus.PROCESSING))
                    store.dispatch(AgentRuntimeInternalAction._SetProcessingJob(job, newTurnId))
                    store.dispatch(AgentAction.TurnBegan(name, newTurnId, parentEntryId = latestUserEntry.entryId))
                }
            }
        }
    }

    private suspend fun _runConversationalLogic(agent: AgentRuntimeState, turnId: String) {
        val store = this.store ?: return
        try {
            delay(2000)
            val responseContent = listOf(TextBlock("This is the agent's response to turn $turnId."))
            store.dispatch(AgentAction.TurnCompleted(turnId, responseContent))
        } catch (e: Exception) {
            if (e is CancellationException) {
                println("Agent turn $turnId was cancelled successfully.")
            } else {
                store.dispatch(AgentAction.TurnFailed(turnId, e.message ?: "Unknown error"))
            }
        } finally {
            store.dispatch(AgentRuntimeInternalAction._UpdateStatus(AgentStatus.WAITING))
            store.dispatch(AgentRuntimeInternalAction._SetProcessingJob(null, null))
        }
    }

    inner class AgentRuntimeComposableProvider : Feature.ComposableProvider {

        @Composable
        override fun TurnView(stateManager: StateManager, turnId: String) {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("$name is processing...")
                    CircularProgressIndicator()
                    Button(onClick = {
                        stateManager.dispatch(AgentAction.TurnCancelled(turnId))
                    }) {
                        Text("Stop")
                    }
                }
            }
        }

        override val settingDefinitions: List<SettingDefinition>
            get() = listOf(
                SettingDefinition(
                    key = "agent.modelName",
                    section = "Agent Settings",
                    label = "Model Name",
                    description = "The AI model to use for generation.",
                    type = SettingType.BOOLEAN // CORRECTED: Changed from TEXT to a valid enum
                ),
                SettingDefinition(
                    key = "agent.initialWait",
                    section = "Agent Settings",
                    label = "Initial Wait (ms)",
                    description = "How long the agent waits before starting a turn.",
                    type = SettingType.NUMERIC_LONG
                )
            )

        override fun getSettingValue(state: AppState, key: String): Any? {
            val agent = (state.featureStates[name] as? AgentRuntimeFeatureState)?.agent
            return when (key) {
                "agent.modelName" -> agent?.selectedModel
                "agent.initialWait" -> agent?.initialWaitMillis
                else -> null
            }
        }
    }
}