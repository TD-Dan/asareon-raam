package app.auf.fakes

import app.auf.core.AppAction
import app.auf.core.AppState
import app.auf.core.Store
import app.auf.core.appReducer
import app.auf.service.SessionManager
import kotlinx.coroutines.CoroutineScope

/**
 * A fake implementation of the Store for unit tests. It allows us to track dispatched
 * actions and control the state.
 */
class FakeStore(
    initialState: AppState,
    coroutineScope: CoroutineScope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
    sessionManager: SessionManager = FakeSessionManager() // MODIFIED: Added sessionManager with a default fake
) : Store(initialState, ::appReducer, sessionManager, coroutineScope) { // MODIFIED: Corrected super() call

    val dispatchedActions = mutableListOf<AppAction>()

    override fun dispatch(action: AppAction) {
        dispatchedActions.add(action)
        // Also, apply the reducer to keep the internal state consistent for assertions
        super.dispatch(action)
    }
}