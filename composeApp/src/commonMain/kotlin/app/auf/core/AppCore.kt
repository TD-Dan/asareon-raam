package app.auf.core

import app.auf.model.SettingValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- VERSION ---
object Version {
    const val APP_VERSION = "1.5.1"
}

// --- CORE STATE & ACTIONS ---

/**
 * ## Mandate
 * A marker interface for all serializable feature state data classes.
 * This enables the core persistence system to save and load a generic map of
 * feature states without needing to know the concrete type of any specific feature's state.
 */
interface FeatureState

/**
 * Defines the core, immutable data models for the entire AUF application state.
 * This object is lean, holding only top-level session state.
 * Feature-specific state is managed within the `featureStates` map.
 */
data class AppState(
    val isSystemVisible: Boolean = false,
    val toastMessage: String? = null,
    val featureStates: Map<String, FeatureState> = emptyMap()
)

/**
 * Defines all possible actions that can be dispatched to the Store to trigger a state change.
 * As of v2.0.0, this is a non-sealed interface to support an open, pluggable feature architecture.
 */
interface AppAction {
    data class ShowToast(val message: String) : AppAction
    data object ClearToast : AppAction
    data object ToggleSystemVisibility : AppAction

    // These actions are now handled by HkgAgentFeature, but remain here as part of the
    // universal action bus contract.
    data class SelectModel(val modelName: String) : AppAction
    data class SetAvailableModels(val models: List<String>) : AppAction
    data class UpdateSetting(val setting: SettingValue) : AppAction
}

// --- UI MODELS (Used by multiple features) ---

@Serializable
sealed interface ContentBlock {
    val summary: String
}

@Serializable
@SerialName("TextBlock")
data class TextBlock(
    val text: String,
    override val summary: String = "Text block: \"${text.take(50).replace("\n", " ")}...\""
) : ContentBlock

@Serializable
@SerialName("CodeBlock")
data class CodeBlock(
    val language: String,
    val content: String,
    override val summary: String = "Code Block: $language"
) : ContentBlock