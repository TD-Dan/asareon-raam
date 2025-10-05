package app.auf.fakes

import app.auf.core.Action
import app.auf.core.AppState
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
    platformDependencies: PlatformDependencies
) : Store(initialState, emptyList(), platformDependencies) {
    val dispatchedActions = mutableListOf<Action>()
    private val _fakeState = MutableStateFlow(initialState)
    override val state = _fakeState.asStateFlow()

    /**
     * Captures the dispatched action after stamping it with the originator,
     * mimicking the behavior of the real Store for accurate testing.
     */
    override fun dispatch(originator: String, action: Action) {
        // To make the fake more realistic, we stamp the action just like the real store.
        val stampedAction = action.copy(originator = originator)
        dispatchedActions.add(stampedAction)
    }

    fun setState(newState: AppState) {
        _fakeState.value = newState
    }
}