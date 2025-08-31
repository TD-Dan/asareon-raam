package app.auf.feature.systemclock

import app.auf.core.AppAction
import app.auf.core.AppState
import app.auf.core.Feature
import app.auf.core.GatewayStatus
import app.auf.core.Store
import app.auf.model.SettingDefinition
import app.auf.model.SettingType
import app.auf.model.SettingValue
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
)

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

    /**
     * The feature's dedicated reducer. It safely accesses its own state slice from the generic
     * feature map, modifies it based on ClockActions, and returns the new global AppState.
     */
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

    /**
     * The lifecycle start method, which launches the clock's asynchronous ticking loop.
     * This is the "middleware" portion of the feature.
     */
    override fun start(store: Store) {
        coroutineScope.launch {
            while (true) {
                val latestState = store.state.value
                val clockState = latestState.featureStates[name] as? SystemClockState ?: SystemClockState()

                if (clockState.isEnabled) {
                    if (latestState.gatewayStatus == GatewayStatus.OK && !latestState.isProcessing) {
                        // 1. Dispatch the system-wide event for other features to hear.
                        store.dispatch(ClockAction.Tick)
                        // 2. Dispatch a generic core action to make the event visible in the UI.
                        store.dispatch(
                            AppAction.AddSystemMessage(
                                title = "[SYSTEM: TICK]",
                                rawContent = "Autonomous processing cycle."
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