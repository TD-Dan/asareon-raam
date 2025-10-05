package app.auf.feature.session

import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.Feature
import app.auf.core.Store
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope

class SessionFeature(
    private val platformDependencies: PlatformDependencies,
    private val coroutineScope: CoroutineScope
) : Feature {
    override val name: String = "SessionFeature"

    override fun reducer(state: AppState, action: Action): AppState {
        // To be implemented via TDD
        return state
    }

    override fun onAction(action: Action, store: Store) {
        // To be implemented via TDD
    }

    override val composableProvider: Feature.ComposableProvider?
        get() = null // To be implemented
}