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
 * 3. To use the `appReducer` to calculate the new state after an action is dispatched.
 * 4. To manage the execution of asynchronous side effects (delegating to Services).
 *
 * ---
 * ## Dependencies
 * - `app.auf.core.AppState`: The state object it holds.
 * - `app.auf.core.AppAction`: The actions it receives.
 * - `app.auf.Reducer`: The pure function used to calculate new state.
 * - `kotlinx.coroutines.CoroutineScope`: For launching asynchronous tasks.
 *
 * @version 1.1
 * @since 2025-08-27
 */
open class Store(
    initialState: AppState,
    private val reducer: (AppState, AppAction) -> AppState,
    private val sessionManager: SessionManager,
    private val coroutineScope: CoroutineScope
) {

    private val _state = MutableStateFlow(initialState)
    val state = _state.asStateFlow()

    open fun dispatch(action: AppAction) {
        val previousState = _state.value
        // The core of UDF: calculate the new state using the reducer.
        val newState = reducer(previousState, action)
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