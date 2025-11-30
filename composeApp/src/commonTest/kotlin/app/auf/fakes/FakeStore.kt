package app.auf.fakes

import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.Feature
import app.auf.core.Store
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A lightweight, controllable fake of the Store for testing side effects and UI interactions.
 * It allows manual state setting and captures all dispatched actions for verification.
 */
class FakeStore(
    initialState: AppState,
    platformDependencies: PlatformDependencies,
    validActionNames: Set<String> = emptySet(),
    // [FIX] Allow injecting features to support logic that checks store.features
    features: List<Feature> = emptyList()
) : Store(initialState, features, platformDependencies, validActionNames) {
    val dispatchedActions = mutableListOf<Action>()
    private val _fakeState = MutableStateFlow(initialState)
    override val state = _fakeState.asStateFlow()

    /**
     * Captures the dispatched action.
     * Note: We do NOT call super.dispatch() because FakeStore is a logic sink.
     */
    override fun dispatch(originator: String, action: Action) {
        val stampedAction = action.copy(originator = originator)
        dispatchedActions.add(stampedAction)
    }

    /**
     * Captures deferred dispatches.
     * Note: We do NOT call super.deferredDispatch().
     * This ensures that T1 tests verify the *intent* to dispatch, regardless of
     * whether the production code uses dispatch() or deferredDispatch().
     */
    override fun deferredDispatch(originator: String, action: Action) {
        val stampedAction = action.copy(originator = originator)
        dispatchedActions.add(stampedAction)
    }

    fun setState(newState: AppState) {
        _fakeState.value = newState
    }
}