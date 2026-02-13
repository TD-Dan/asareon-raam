package app.auf.core

import androidx.compose.runtime.Composable

/**
 * Defines the universal contract for a self-contained, modular feature plugin within the AUF app.
 * This is the cornerstone of the "Core Ignorance" and "Contextual Granularity" principles.
 *
 * Each feature registers itself with an [Identity] that provides its bus handle (used for
 * action routing and originator authorization) and a human-readable display name.
 */
interface Feature {
    /**
     * The feature's identity on the action bus. The [Identity.handle] is used for routing
     * and authorization (replaces the old `val name: String`). The [Identity.uuid] is null
     * for features since their handles are stable across restarts.
     *
     * Example:
     * ```
     * override val identity = Identity(uuid = null, handle = "session", name = "Session Manager")
     * ```
     */
    val identity: Identity

    fun reducer(state: FeatureState?, action: Action): FeatureState? = state

    /**
     * The impure phase where features perform I/O, dispatch follow-up actions, and interact
     * with external systems. Called after the reducer has produced a new state.
     *
     * Named `handleSideEffects` (not `onAction`) because it truthfully describes what the
     * method does — a developer unfamiliar with the system won't mistakenly call it directly,
     * bypassing the Store pipeline (validation, authorization, reducer, audit logging).
     */
    fun handleSideEffects(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {}

    fun onPrivateData(envelope: PrivateDataEnvelope, store: Store) {}
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