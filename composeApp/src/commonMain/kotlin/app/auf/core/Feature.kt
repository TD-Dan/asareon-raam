package app.auf.core

import androidx.compose.runtime.Composable

/**
 * Defines the universal contract for a self-contained, modular feature plugin within the AUF app.
 * This is the cornerstone of the "Core Ignorance" and "Contextual Granularity" principles.
 *
 * @version 2.1 - Evolved to support multiple views and ribbon content per feature.
 */
interface Feature {
    val name: String

    /**
     * A PURE function that calculates a new AppState based on the current state and a given action.
     * It MUST NOT perform any side effects (I/O, network, etc.).
     * If the feature does not handle the action, it MUST return the state unmodified.
     */
    fun reducer(state: AppState, action: Action): AppState = state

    /**
     * The designated place to handle SIDE EFFECTS (I/O, coroutines, etc.) in response to an action.
     * The Store calls this function for every feature on every action, AFTER the reducers
     * have calculated the new state. This allows features to react to events and dispatch
     * new actions.
     *
     * ### Synchronous Startup Mandate
     * The Store guarantees that this `onAction` method is invoked synchronously and sequentially
     * on all features within a single `dispatch` call. During the critical `system.INITIALIZING`
     * lifecycle phase, any work performed in this method MUST be synchronous.
     * Launching a "fire-and-forget" coroutine for an essential setup task is a critical
     * architectural violation, as it breaks the deterministic startup sequence.
     * The `system.STARTING`phase can be used to launch long taking asynchronous jobs whose outcome
     * is not deterministic.
     *
     * @see P-SYSTEM-002: Synchronous Startup Mandate
     */
    fun onAction(action: Action, store: Store) {}

    /**
     * A secure callback for receiving private data directly from the Store.
     * This is the designated channel for sensitive information (e.g., file contents, API keys)
     * that MUST NOT be broadcast on the public action bus. To integrate the received data
     * with the main state, the feature should dispatch a new, internal action to its own
     * reducer from within this method.
     */
    fun onPrivateData(data: Any, store: Store) {}


    /**
     * Called exactly once by the Store when the application is being assembled.
     * Use this for one-time, synchronous setup of a feature's internal dependencies.
     * DO NOT dispatch actions here, as the application lifecycle has not yet begun.
     */
    fun init(store: Store) {}

    /**
     * The single, optional provider for ALL of this feature's UI components.
     * A feature can choose to implement any combination of the functions within.
     */
    val composableProvider: ComposableProvider?
        get() = null

    interface ComposableProvider {
        /**
         * A map of unique view keys to the composable functions that render them.
         * This allows a single feature to provide multiple, distinct views for the main stage.
         *
         * Example:
         * "feature.session.main" to { store -> SessionView(store) },
         * "feature.session.manager" to { store -> SessionsManagerView(store) }
         */
        val stageViews: Map<String, @Composable (Store) -> Unit>
            get() = emptyMap()

        /**
         * A slot for rendering one or more buttons or other components in the GlobalActionRibbon.
         * This flexible slot replaces the previous, single-button contract.
         */
        @Composable
        fun RibbonContent(store: Store, activeViewKey: String?) {}

        /**
         * Renders a part of the feature's UI to be embedded inside another view.
         */
        @Composable
        fun PartialView(store: Store, partId: String) {}

        /**
         * A slot for adding DropdownMenuItems to the main application menu.
         */
        @Composable
        fun MenuContent(store: Store, onDismiss: () -> Unit) {}
    }
}