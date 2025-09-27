package app.auf.core

import androidx.compose.runtime.Composable
import app.auf.model.SettingDefinition
import app.auf.model.SettingValue

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
     * A delegation method. The system will call this on all features.
     * The first feature to recognize the setting's key will return the appropriate
     * feature-specific Action. Others will return null.
     */
    fun createActionForSetting(setting: SettingValue): Action? = null

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

        /**
         * The list of settings this feature exposes to the UI.
         */
        val settingDefinitions: List<SettingDefinition>
            get() = emptyList()

        /**
         * A required function to decouple the view from the state.
         * The feature itself is responsible for finding the correct value within its
         * own state slice, given the global AppState.
         */
        fun getSettingValue(state: AppState, key: String): Any? = null
    }
}