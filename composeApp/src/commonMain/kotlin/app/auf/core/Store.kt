package app.auf.core

import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreState
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The central state container for the Unidirectional Data Flow (UDF) architecture.
 */
open class Store(
    initialState: AppState,
    private val features: List<Feature>,
    private val platformDependencies: PlatformDependencies
) {

    private val _state = MutableStateFlow(initialState)
    open val state = _state.asStateFlow()

    private var lifecycleStarted = false

    fun initFeatureLifecycles() {
        if (!lifecycleStarted) {
            features.forEach { it.init(this) }
            lifecycleStarted = true
        }
    }

    /**
     * The single, generic entry point for all state changes and side effects.
     * The process is a strictly ordered, synchronous, blocking call:
     *
     * 1.  **Guard: ** The action is validated against the current `AppLifecycle` state.
     * 2.  **Reduce: ** The `reducer` from every feature is called sequentially to calculate the new state.
     * 3.  **Update: ** The central state is atomically updated.
     * 4.  **`onAction`:** The `onAction` side effect handler from every feature is called sequentially.
     *
     * This synchronous and sequential execution is the foundation of the application's
     * deterministic startup process. The function will not return until all phases are complete.
     */
    open fun dispatch(action: Action) {
        val coreState = _state.value.featureStates["CoreFeature"] as? CoreState
        val currentLifecycle = coreState?.lifecycle ?: AppLifecycle.BOOTING
        platformDependencies.log(
            level = LogLevel.INFO,
            tag = "Store",
            message = "Dispatching: $action"
        )

        val isActionAllowed = when (currentLifecycle) {
            AppLifecycle.BOOTING -> action.name == "app.INITIALIZING"
            AppLifecycle.INITIALIZING -> true // Allow all actions during the init/load phase
            AppLifecycle.RUNNING -> action.name != "app.INITIALIZING" && action.name != "app.STARTING"
            AppLifecycle.CLOSING -> action.name == "app.CLOSING" // Only allow closing action
        }

        if (!isActionAllowed) {
            platformDependencies.log(
                level = LogLevel.ERROR,
                tag = "Store",
                message = "Action '$action' dispatched in invalid lifecycle state '$currentLifecycle'. Action ignored."
            )
            return
        }

        // --- PHASE 1: REDUCE ---
        val previousState = _state.value
        val newState = features.fold(previousState) { currentState, feature ->
            feature.reducer(currentState, action)
        }

        // --- PHASE 2: UPDATE STATE ---
        if (newState != previousState) {
            _state.value = newState
        }

        // --- PHASE 3: SIDE-EFFECTS ---
        features.forEach { feature ->
            feature.onAction(action, this)
        }
    }
}