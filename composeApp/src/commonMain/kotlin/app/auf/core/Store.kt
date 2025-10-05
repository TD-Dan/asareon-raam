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
     * 1.  **Stamp:** The action is stamped with the verified `originator`.
     * 2.  **Guard: ** The stamped action is validated against the current `AppLifecycle` state.
     * 3.  **Reduce: ** The `reducer` from every feature is called sequentially to calculate the new state.
     * 4.  **Update: ** The central state is atomically updated.
     * 5.  **`onAction`:** The `onAction` side effect handler from every feature is called sequentially.
     *
     * This synchronous and sequential execution is the foundation of the application's
     * deterministic startup process. The function will not return until all phases are complete.
     *
     * @param originator A non-repudiable string identifying the caller (e.g., a feature's name).
     * @param action The action to be dispatched.
     */
    open fun dispatch(originator: String, action: Action) {
        // --- PHASE 0: STAMP ---
        // Create a new, trusted action instance with the Store-verified originator.
        // This makes the originator non-repudiable.
        val stampedAction = action.copy(originator = originator)

        val coreState = _state.value.featureStates["CoreFeature"] as? CoreState
        val currentLifecycle = coreState?.lifecycle ?: AppLifecycle.BOOTING
        platformDependencies.log(
            level = LogLevel.INFO,
            tag = "Store",
            message = "Dispatching: $stampedAction"
        )

        // --- PHASE 1: GUARD ---
        val isActionAllowed = when (currentLifecycle) {
            AppLifecycle.BOOTING -> stampedAction.name == "app.INITIALIZING"
            AppLifecycle.INITIALIZING -> true // Allow all actions during the init/load phase
            AppLifecycle.RUNNING -> stampedAction.name != "app.INITIALIZING" && stampedAction.name != "app.STARTING"
            AppLifecycle.CLOSING -> stampedAction.name == "app.CLOSING" // Only allow closing action
        }

        if (!isActionAllowed) {
            platformDependencies.log(
                level = LogLevel.ERROR,
                tag = "Store",
                message = "Action '$stampedAction' dispatched in invalid lifecycle state '$currentLifecycle'. Action ignored."
            )
            return
        }

        // --- PHASE 2: REDUCE ---
        val previousState = _state.value
        val newState = features.fold(previousState) { currentState, feature ->
            feature.reducer(currentState, stampedAction)
        }

        // --- PHASE 3: UPDATE STATE ---
        if (newState != previousState) {
            _state.value = newState
        }

        // --- PHASE 4: SIDE-EFFECTS ---
        features.forEach { feature ->
            feature.onAction(stampedAction, this)
        }
    }
}