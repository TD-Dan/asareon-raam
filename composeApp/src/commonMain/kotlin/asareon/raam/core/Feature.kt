package asareon.raam.core

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

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

    /**
     * Pre-initialization lifecycle hook. Called by [Store.initFeatureLifecycles] BEFORE
     * [init] and before the Store's action bus is operational.
     *
     * **Contract:**
     * - Runs during bootstrap, before any state mutations or action dispatch.
     * - Must be synchronous and blocking (called from a synchronous `remember {}` block).
     * - Must NOT depend on the Store, action bus, or any other feature's state.
     * - Must NOT modify application data on disk — this phase is read-only + snapshot.
     * - Dependencies are available only via constructor injection (e.g., platformDependencies).
     * - Failures must be handled internally (log + continue). Never throw — a failed
     *   preInit must not prevent the application from starting.
     *
     * **Use cases:** Creating protective snapshots (backups), validating data integrity,
     * reading bootstrap configuration from non-encrypted sources.
     *
     * Default implementation is a no-op. Override only if your feature needs
     * pre-initialization work.
     */
    fun preInit() {}

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

        /**
         * Structured ribbon entries contributed by this feature. The ribbon
         * renders entries sorted by [RibbonEntry.priority] (higher first) and
         * spills low-priority ones into its overflow menu when vertical space
         * is constrained.
         *
         * Prefer this over [RibbonContent]. The legacy [RibbonContent] hook
         * remains for features that need a custom composable (e.g. a badge),
         * and is rendered below the structured entries.
         */
        fun ribbonEntries(store: Store, activeViewKey: String?): List<RibbonEntry> = emptyList()

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

/**
 * A single entry rendered in the [GlobalActionRibbon].
 *
 * @property priority Higher values render first (top of the ribbon) and are
 *   the last to spill into the overflow menu when space is tight. Ties break
 *   by insertion order across features.
 * @property isActive Whether this entry corresponds to the currently active
 *   view — used to render a selected/pressed visual state.
 */
data class RibbonEntry(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val priority: Int = 0,
    val isActive: Boolean = false,
    val onClick: () -> Unit,
)
