package app.auf.core

import kotlinx.coroutines.CoroutineScope
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
 * @version 1.0
 * @since 2025-08-16
 */
open class Store(
    initialState: AppState,
    private val reducer: (AppState, AppAction) -> AppState,
    private val coroutineScope: CoroutineScope
) {

    private val _state = MutableStateFlow(initialState)
    val state = _state.asStateFlow()

    open fun dispatch(action: AppAction) {
        // The core of UDF: calculate the new state using the reducer.
        val newState = reducer(_state.value, action)
        _state.value = newState

        // Handle side effects (asynchronous operations)
        // This will be expanded significantly when we build the Services.
        when (action) {
            is AppAction.LoadGraph -> {
                // In the full architecture, this would be handled by a GraphService.
                // For now, this is a placeholder for the concept.
                println("STORE: Side-effect for LoadGraph would be triggered here.")
            }
            is AppAction.AddUserMessage -> {
                // In the full architecture, this would be handled by a ChatService.
                println("STORE: Side-effect for SendMessage would be triggered here.")
            }
            else -> {
                // No side effect for this action.
            }
        }
    }
}