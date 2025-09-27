package app.auf.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The central state container for the Unidirectional Data Flow (UDF) architecture.
 *
 * ---
 * ## Mandate
 * This class holds the single source of truth: the application's state (`AppState`). Its
 * responsibilities are:
 * 1. To provide a `StateFlow` of the current `AppState` for the UI to observe.
 * 2. To expose a single `dispatch` method that accepts the universal `Action`.
 * 3. To pass the state through each registered feature's reducer to calculate the new state.
 * 4. To manage the lifecycle of features, calling their `init` method once.
 *
 * This class is part of the core scaffolding; it is completely ignorant of any specific feature's logic.
 */
open class Store(
    initialState: AppState,
    private val features: List<Feature>
) {

    private val _state = MutableStateFlow(initialState)
    val state = _state.asStateFlow()

    private var lifecycleStarted = false

    /**
     * Kicks off the `init` lifecycle method for all registered features.
     * This should only be called once, after the store has been fully initialized.
     */
    fun startFeatureLifecycles() {
        if (!lifecycleStarted) {
            features.forEach { it.init(this) }
            lifecycleStarted = true
        }
    }

    /**
     * The single, generic entry point for all state changes.
     * This method orchestrates the state transition by sequentially applying the reducer
     * from every registered feature to the current application state.
     */
    open fun dispatch(action: Action) {
        val previousState = _state.value

        // The state is passed through each feature's reducer in sequence.
        val newState = features.fold(previousState) { currentState, feature ->
            feature.reducer(currentState, action)
        }
        _state.value = newState
    }
}