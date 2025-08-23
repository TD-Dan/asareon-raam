package app.auf.fakes

import app.auf.core.AppAction
import app.auf.core.AppState
import app.auf.core.Store
import app.auf.core.appReducer
import kotlinx.coroutines.CoroutineScope

/**
 * A fake implementation of the Store for unit tests. It allows us to track dispatched
 * actions and control the state.
 */
class FakeStore(
    initialState: AppState,
    coroutineScope: CoroutineScope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
) : Store(initialState, ::appReducer, coroutineScope) {

    val dispatchedActions = mutableListOf<AppAction>()

    override fun dispatch(action: AppAction) {
        dispatchedActions.add(action)
        // Also, apply the reducer to keep the internal state consistent for assertions
        super.dispatch(action)
    }
}