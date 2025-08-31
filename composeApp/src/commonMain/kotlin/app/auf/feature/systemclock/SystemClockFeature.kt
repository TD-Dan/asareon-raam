package app.auf.feature.systemclock

import app.auf.core.AppAction
import app.auf.core.AppState
import app.auf.core.Feature
import app.auf.core.GatewayStatus
import app.auf.core.Store
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

    /**
     * The feature's dedicated reducer. It safely accesses its own state slice from the generic
     * feature map, modifies it based on ClockActions, and returns the new global AppState.
     */
    override fun reducer(state: AppState, action: AppAction): AppState {
        if (action !is ClockAction) return state

        // Safely get the current state for this feature, or a default if not present.
        val currentState = state.featureStates[name] as? SystemClockState ?: SystemClockState()

        val newFeatureState = when (action) {
            is ClockAction.Start -> currentState.copy(isEnabled = true)
            is ClockAction.Stop -> currentState.copy(isEnabled = false)
            is ClockAction.SetInterval -> currentState.copy(intervalMillis = action.millis)
            is ClockAction.Tick -> currentState // A Tick action does not change the clock's state.
        }

        // Return the global state with this feature's state slice updated in the map.
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
                // Always read the latest state from the store inside the loop.
                val latestState = store.state.value
                val clockState = latestState.featureStates[name] as? SystemClockState ?: SystemClockState()

                if (clockState.isEnabled) {
                    // CRITICAL SAFETY CONSTRAINT: Only dispatch a Tick if the gateway is idle.
                    if (latestState.gatewayStatus == GatewayStatus.IDLE && !latestState.isProcessing) {
                        store.dispatch(ClockAction.Tick)
                    }
                    delay(clockState.intervalMillis)
                } else {
                    // If disabled, wait a short time before checking again to avoid a tight loop.
                    delay(1000L)
                }
            }
        }
    }
}