package app.auf.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- VERSION ---
object Version {
    const val APP_VERSION = "1.5.4"
}

// --- CORE STATE & ACTIONS ---

interface FeatureState

data class AppState(
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
    data class SetActiveView(val key: String) : AppAction
}

/**
 * The formal, system-wide contract for agent turn-based processing.
 * These actions are dispatched by agent features and primarily handled by the SessionFeature.
 */
sealed interface AgentAction : AppAction {
    /** Dispatched when an agent begins a processing turn, creating a placeholder in the UI. */
    data class TurnBegan(val agentId: String, val turnId: String, val parentEntryId: String?) : AgentAction

    /** Dispatched when an agent successfully completes a turn, replacing the placeholder with content. */
    data class TurnCompleted(val turnId: String, val content: List<ContentBlock>) : AgentAction

    /** Dispatched when a user or the system cancels an in-progress turn. */
    data class TurnCancelled(val turnId: String) : AgentAction

    /** Dispatched when a turn fails due to an error. */
    data class TurnFailed(val turnId: String, val error: String) : AgentAction
}


// --- UI MODELS (Used by multiple features) ---

/**
 * A marker interface for LedgerEntry types that should NOT be persisted to disk.
 * This is used to filter out transient, runtime-only entries like agent processing indicators.
 */
interface TransientEntry

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