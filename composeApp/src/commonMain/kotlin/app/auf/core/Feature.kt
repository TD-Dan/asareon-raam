package app.auf.core

import androidx.compose.runtime.Composable

/**
 * ---
 * ## Mandate
 * Defines the universal contract for a self-contained, modular feature plugin within the AUF app.
 * This is the cornerstone of the "Core Ignorance" and "Contextual Granularity" principles.
 * A Feature can provide a reducer to manage its own state slice, a `start` lifecycle
 * method, and an optional `ComposableProvider` for rendering its UI.
 */

interface Feature {
    /**
     * A unique, machine-readable name for the feature, used for debugging and registration.
     */
    val name: String

    /**
     * The feature's dedicated reducer function. It receives the current global state and an action,
     * and must return a new, updated global state. It is the feature's responsibility to
     * handle its own state slice immutably. If an action is not relevant to this feature,
     * it must return the state object unmodified.
     *
     * @param state The current global [AppState].
     * @param action The dispatched [AppAction].
     * @return The new [AppState].
     */
    fun reducer(state: AppState, action: AppAction): AppState = state

    /**
     * A lifecycle method called once when the Store is initialized. This is the designated
     * entry point for a feature to start any long-running processes, coroutines, or listeners
     * (e.g., a system clock timer, a file system watcher). It provides access to the
     * store for dispatching new actions.
     *
     * @param store The central [Store] instance, providing `dispatch` and `state`.
     */
    fun start(store: Store) {
        // The default implementation is a no-op for features without async logic.
    }

    /**
     * An optional provider for this feature's UI components. If a feature needs to render
     * something in the main application shell, it should override this property and return
     * an implementation of the [ComposableProvider] interface.
     */
    val composableProvider: ComposableProvider?
        get() = null


    /**
     * Defines a contract for features to provide UI components ("slots") to be rendered
     * in the main application layout. This keeps the core UI decoupled from feature specifics.
     */
    interface ComposableProvider {
        /**
         * A slot for components that should appear in the header area of a session view,
         * typically for controls like agent or model selection.
         *
         * @param stateManager The central StateManager to dispatch actions.
         */
        @Composable
        fun SessionHeader(stateManager: StateManager) {
            // Default is an empty implementation
        }
    }
}