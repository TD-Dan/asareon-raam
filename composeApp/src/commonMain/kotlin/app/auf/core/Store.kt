package app.auf.core

import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreState
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The central state container for the Unidirectional Data Flow (UDF) architecture.
 *
 * This class holds the single source of truth: the application's state (`AppState`). Its
 * responsibilities are:
 * 1. To provide a `StateFlow` of the current `AppState` for the UI to observe.
 * 2. To expose a single `dispatch` method that accepts the universal `Action`.
 * 3. To pass the state through each registered feature's reducer to calculate the new state.
 * 4. To trigger each feature's `onAction` side effect handler AFTER the state has been updated.
 * 5. To manage the lifecycle of features, calling their `init` method once.
 * 6. To act as a gatekeeper, enforcing the application lifecycle state.
 *
 * This class is part of the core scaffolding; it is completely ignorant of any specific feature's logic.
 *
 */
open class Store(
    initialState: AppState,
    private val features: List<Feature>,
    private val platformDependencies: PlatformDependencies
) {

    private val _state = MutableStateFlow(initialState)
    open val state = _state.asStateFlow()

    private var lifecycleStarted = false

    /**
     * Kicks off the `init` lifecycle method for all registered features.
     * This should only be called once, after the store has been fully initialized.
     */
    fun initFeatureLifecycles() {
        if (!lifecycleStarted) {
            features.forEach { it.init(this) }
            lifecycleStarted = true
        }
    }

    /**
     * The single, generic entry point for all state changes and side effects.
     * The process is strictly ordered:
     * 1. Check lifecycle guard.
     * 2. Sequentially apply the reducer from every feature to calculate the new state.
     * 3. Atomically update the state.
     * 4. Sequentially trigger the `onAction` side effect handler for every feature.
     */
    open fun dispatch(action: Action) {
        val coreState = _state.value.featureStates["CoreFeature"] as? CoreState
        val currentLifecycle = coreState?.lifecycle ?: AppLifecycle.INITIALIZING
        platformDependencies.log(
            level = LogLevel.INFO,
            tag = "Store",
            message = "Dispatching: $action"
        )
        // THE GUARD CLAUSE
        // An action was dispatched before the app finished starting. This is a critical
        // lifecycle violation. We must log it as an error and ignore the action.
        if (currentLifecycle == AppLifecycle.INITIALIZING && action.name != "app.STARTING") {
            platformDependencies.log(
                level = LogLevel.ERROR,
                tag = "Store",
                message = "Action $action dispatched before app started. Action ignored."
            )
            return
        }

        // --- PHASE 1: REDUCE ---
        val previousState = _state.value
        val newState = features.fold(previousState) { currentState, feature ->
            feature.reducer(currentState, action)
        }

        // --- PHASE 2: UPDATE STATE ---
        // Atomically update the state if it has changed.
        if (newState != previousState) {
            _state.value = newState
        }

        // --- PHASE 3: SIDE-EFFECTS ---
        // Trigger side effects for all features AFTER the state has been updated.
        // This ensures onAction handlers always work with the most current state.
        features.forEach { feature ->
            feature.onAction(action, this)
        }
    }
}