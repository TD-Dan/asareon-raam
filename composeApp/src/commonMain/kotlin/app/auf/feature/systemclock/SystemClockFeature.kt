package app.auf.feature.systemclock

import app.auf.core.*
import app.auf.feature.hkgagent.HkgAgentFeatureState
import app.auf.feature.session.SessionAction
import app.auf.model.SettingDefinition
import app.auf.model.SettingType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

// --- 1. MODEL ---
/**
 * The state slice for the SystemClock feature.
 */
@Serializable
data class SystemClockState(
    val isEnabled: Boolean = false,
    val intervalMillis: Long = 300_000L // 5 minutes
) : FeatureState

// --- 2. ACTIONS ---
/**
 * Defines all AppActions specific to the SystemClock feature.
 */
sealed interface ClockAction : AppAction {
    data object Start : ClockAction
    data object Stop : ClockAction
    data class SetInterval(val millis: Long) : ClockAction

    /** The autonomous heartbeat action dispatched by the feature's middleware. */
    data object Tick : ClockAction
}


// --- 3. FEATURE IMPLEMENTATION ---
/**
 * ## Mandate
 * Provides an autonomous, configurable "heartbeat" for the application by periodically
 * dispatching a `ClockAction.Tick`. It is a self-contained plugin that operates
 * on its own state slice within the generic `featureStates` map.
 */
class SystemClockFeature(
    private val coroutineScope: CoroutineScope
) : Feature {
    override val name: String = "SystemClockFeature"

    companion object {
        val SETTING_DEFINITIONS = listOf(
            SettingDefinition(
                key = "clock.isEnabled",
                section = "System Clock",
                label = "Enable Autonomous TICK",
                description = "When enabled, the application will periodically dispatch a TICK action, allowing the AI to perform background introspection.",
                type = SettingType.BOOLEAN
            ),
            SettingDefinition(
                key = "clock.intervalMillis",
                section = "System Clock",
                label = "TICK Interval (milliseconds)",
                description = "The time in milliseconds between each autonomous TICK dispatch.",
                type = SettingType.NUMERIC_LONG
            )
        )
    }

    override fun reducer(state: AppState, action: AppAction): AppState {
        val currentState = state.featureStates[name] as? SystemClockState ?: SystemClockState()

        val newFeatureState = when (action) {
            is ClockAction.Start -> currentState.copy(isEnabled = true)
            is ClockAction.Stop -> currentState.copy(isEnabled = false)
            is ClockAction.SetInterval -> currentState.copy(intervalMillis = action.millis)
            is AppAction.UpdateSetting -> {
                when (action.setting.key) {
                    "clock.isEnabled" -> {
                        val value = action.setting.value as? Boolean ?: currentState.isEnabled
                        currentState.copy(isEnabled = value)
                    }
                    "clock.intervalMillis" -> {
                        val value = (action.setting.value as? Long) ?: currentState.intervalMillis
                        currentState.copy(intervalMillis = value)
                    }
                    else -> currentState
                }
            }
            else -> return state
        }


        return state.copy(
            featureStates = state.featureStates + (name to newFeatureState)
        )
    }

    override fun start(store: Store) {
        coroutineScope.launch {
            while (true) {
                val latestState = store.state.value
                val clockState = latestState.featureStates[name] as? SystemClockState ?: SystemClockState()
                val agentFeatureState = latestState.featureStates["HkgAgentFeature"] as? HkgAgentFeatureState

                val isAnyAgentProcessing = agentFeatureState?.agents?.values?.any { it.isProcessing } ?: false

                if (clockState.isEnabled) {
                    if (!isAnyAgentProcessing) {
                        store.dispatch(ClockAction.Tick)
                        store.dispatch(
                            SessionAction.PostEntry(
                                sessionId = "default-session", // Assume default for now
                                agentId = "CORE",
                                content = "[SYSTEM: TICK]\nAutonomous processing cycle."
                            )
                        )
                    }
                    delay(clockState.intervalMillis)
                } else {
                    delay(1000L)
                }
            }
        }
    }
}