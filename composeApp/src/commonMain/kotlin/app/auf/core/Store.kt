package app.auf.core

import app.auf.service.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The central state container for the Unidirectional Data Flow (UDF) architecture.
 *
 * ---
 * ## Mandate
 * This class holds the single source of truth: the application's state (`AppState`). Its
 * responsibilities are:
 * 1. To provide a `StateFlow` of the current `AppState` for the UI to observe.
 * 2. To expose a single `dispatch` method that accepts `AppAction`s.
 * 3. To use the `appReducer` and registered `Feature` reducers to calculate the new state.
 * 4. To manage the lifecycle of features, calling their `start` method once.
 *
 * Is part of CORE: Does not know anything of specific features!
 */
open class Store(
    initialState: AppState,
    private val rootReducer: (AppState, AppAction) -> AppState,
    private val features: List<Feature>,
    private val sessionManager: SessionManager,
    private val coroutineScope: CoroutineScope
) {

    private val _state = MutableStateFlow(initialState)
    val state = _state.asStateFlow()

    private var lifecycleStarted = false

    /**
     * Kicks off the `start` lifecycle method for all registered features.
     * This should only be called once, after the store has been fully initialized.
     */
    fun startFeatureLifecycles() {
        if (!lifecycleStarted) {
            features.forEach { it.start(this) }
            lifecycleStarted = true
        }
    }


    open fun dispatch(action: AppAction) {
        val previousState = _state.value
        // 1. The root reducer handles core state changes first.
        val stateAfterRoot = rootReducer(previousState, action)

        // 2. The state is then passed through each feature's reducer in sequence.
        val newState = features.fold(stateAfterRoot) { currentState, feature ->
            feature.reducer(currentState, action)
        }
        _state.value = newState


        // --- SIDE EFFECT: AUTO-SAVE SESSION ---
        // If the chat history has changed, save the new history to disk.
        if (newState.chatHistory != previousState.chatHistory) {
            coroutineScope.launch {
                sessionManager.saveSession(newState.chatHistory)
            }
        }
    }
}