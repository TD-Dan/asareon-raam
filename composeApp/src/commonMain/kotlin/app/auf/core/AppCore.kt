// --- file: commonMain/kotlin/app/auf/core/AppCore.kt ---
package app.auf.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- VERSION ---
object Version {
    const val APP_VERSION = "1.6.0" // Bump for the UI refactor
}

// --- CORE STATE & ACTIONS ---

interface FeatureState

data class AppState(
    val isSystemVisible: Boolean = false,
    val toastMessage: String? = null,
    val activeViewKey: String = "feature.session.main",
    val defaultViewKey: String = "feature.session.main",
    val featureStates: Map<String, FeatureState> = emptyMap()
)

/**
 * Defines all possible actions that can be dispatched to the Store to trigger a state change.
 * This is now a pure, core-only contract.
 */
interface AppAction {
    data class ShowToast(val message: String) : AppAction
    data object ClearToast : AppAction
    data object ToggleSystemVisibility : AppAction
    data class SetActiveView(val key: String) : AppAction
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