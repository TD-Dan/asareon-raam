package app.auf.fakes

import app.auf.core.*
import app.auf.service.SessionManager
import kotlinx.coroutines.CoroutineScope

/**
 * A fake implementation of the Store for unit tests. It allows us to track dispatched
 * actions and control the state.
 */
class FakeStore(
    initialState: AppState,
    coroutineScope: CoroutineScope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
    sessionManager: SessionManager = FakeSessionManager(),
    // --- MODIFICATION START: Allow fakes to be passed into the fake store ---
    features: List<Feature> = emptyList()
    // --- MODIFICATION END ---
) : Store(initialState, ::appReducer, features, sessionManager, coroutineScope) { // --- MODIFICATION: Corrected super() call ---

    val dispatchedActions = mutableListOf<AppAction>()

    override fun dispatch(action: AppAction) {
        dispatchedActions.add(action)
        // Also, apply the reducer to keep the internal state consistent for assertions
        super.dispatch(action)
    }
}