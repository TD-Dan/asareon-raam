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
    fun reducer(state: AppState, action: AppAction): AppState = state
    fun start(store: Store) {}

    /**
     * A new delegation method. The StateManager will call this on all features.
     * The first feature to recognize the setting's key will return the appropriate
     * feature-specific AppAction. Others will return null.
     */
    fun createActionForSetting(setting: SettingValue): AppAction? = null

    /**
     * The single, optional provider for ALL of this feature's UI components.
     * A feature can choose to implement any combination of the functions within.
     */
    val composableProvider: ComposableProvider?
        get() = null

    interface ComposableProvider {
        /**
         * --- Main Stage Contract (for features with a dedicated, top-level view) ---
         */

        /**
         * The unique key for this feature's main view, used for navigation.
         * MUST be provided if the feature has a main StageContent.
         * e.g., "feature.session.main", "feature.knowledgegraph.import"
         */
        val viewKey: String?
            get() = null

        /**
         * The button to render in the GlobalActionRibbon.
         * Typically dispatches AppAction.SetActiveView(viewKey).
         */
        @Composable
        fun RibbonButton(stateManager: StateManager, isActive: Boolean) {}

        /**
         * The main content to render on the ActionStage when this feature's viewKey is active.
         */
        @Composable
        fun StageContent(stateManager: StateManager) {}


        /**
         * --- Enrichment & Headless Contract (for features that plug into other views) ---
         */

        /**
         * A slot for adding controls to the header of the main SessionView.
         * Used by features like HkgAgentFeature to provide model/persona selectors.
         */
        @Composable
        fun SessionHeader(stateManager: StateManager) {}

        /**
         * A slot for adding a feature's settings UI to the main SettingsView.
         * The SettingsView will iterate through all features and render this for each one.
         */
        @Composable
        fun SettingsContent(stateManager: StateManager) {}

        /**
         * A slot for adding DropdownMenuItems to the main application menu.
         * Used for simple, one-off tool commands.
         */
        @Composable
        fun MenuContent(stateManager: StateManager, onDismiss: () -> Unit) {}

        /**
         * The list of settings this feature exposes to the UI.
         */
        val settingDefinitions: List<SettingDefinition>
            get() = emptyList()

        /**
         * A new, required function to decouple the view from the state.
         * The feature itself is responsible for finding the correct value within its
         * own state slice, given the global AppState.
         */
        fun getSettingValue(state: AppState, key: String): Any? = null
    }
}