package app.auf.core

import androidx.compose.runtime.Composable

/**
 * ---
 * ## Mandate
 * Defines the universal contract for a self-contained, modular feature plugin within the AUF app.
 * This is the cornerstone of the "Core Ignorance" and "Contextual Granularity" principles.
 */
interface Feature {
    val name: String

    /**
     * A pure function that calculates a new AppState based on the current state and a given action.
     * If the feature does not handle the action, it MUST return the state unmodified.
     */
    fun reducer(state: AppState, action: Action): AppState = state

    /**
     * Called exactly once by the Store when the application starts.
     * Use this for one-time setup, like dispatching an initial action to load data.
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
         * The unique key for this feature's main view, used for navigation.
         * MUST be provided if the feature has a main StageContent.
         * E.g., "feature.session.main", "feature.knowledgegraph.import"
         */
        val viewKey: String?
            get() = null

        /**
         * The button to render in the GlobalActionRibbon.
         * Typically, dispatches Action(name = "core.SET_ACTIVE_VIEW", payload = ...).
         */
        @Composable
        fun RibbonButton(store: Store, isActive: Boolean) {}

        /**
         * The main content to render on the ActionStage when this feature's viewKey is active.
         */
        @Composable
        fun StageContent(store: Store) {}

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