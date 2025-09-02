package app.auf.core

import app.auf.model.CompilerSettings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Defines the core, immutable data models for the entire AUF application state.
 * This object is lean, holding only top-level session state.
 * Feature-specific state is managed within the `featureStates` map.
 */

data class AppState(
    val isSystemVisible: Boolean = false,
    val availableModels: List<String> = emptyList(),
    val selectedModel: String = "gemini-1.5-flash-latest",
    val toastMessage: String? = null,
    val compilerSettings: CompilerSettings = CompilerSettings(),
    val featureStates: Map<String, Any> = emptyMap()
)

// This class is no longer needed in core, but ContentBlock types are used by the UI
// and parser. We will keep them for now for stability. A future refactor could move
// them to a dedicated `ui.model` package.

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