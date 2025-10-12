package app.auf.core

import androidx.compose.runtime.Composable

/**
 * Defines the universal contract for a self-contained, modular feature plugin within the AUF app.
 * This is the cornerstone of the "Core Ignorance" and "Contextual Granularity" principles.
 *
 * @version 2.2 - Evolved to support context passing for Partial Views and DI for Stage Views.
 */
interface Feature {
    val name: String
    fun reducer(state: AppState, action: Action): AppState = state
    fun onAction(action: Action, store: Store) {}
    fun onPrivateData(data: Any, store: Store) {}
    fun init(store: Store) {}

    val composableProvider: ComposableProvider?
        get() = null

    interface ComposableProvider {
        /**
         * A map of unique view keys to the composable functions that render them.
         * The list of all features is passed in to allow for dependency injection of composable providers.
         */
        val stageViews: Map<String, @Composable (Store, List<Feature>) -> Unit>
            get() = emptyMap()

        @Composable
        fun RibbonContent(store: Store, activeViewKey: String?) {}

        /**
         * Renders a part of the feature's UI to be embedded inside another view.
         * The generic context parameter allows the host view to pass necessary data without
         * creating a compile-time dependency.
         */
        @Composable
        fun PartialView(store: Store, partId: String, context: Any? = null) {}

        @Composable
        fun MenuContent(store: Store, onDismiss: () -> Unit) {}
    }
}