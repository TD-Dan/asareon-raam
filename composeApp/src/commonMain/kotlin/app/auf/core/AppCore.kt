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
 * The root of all state changes. A sealed hierarchy with two exclusive branches.
 */
sealed interface AppAction

/**
 * A Command is a public intent for the system to perform an action.
 */
sealed interface Command : AppAction

/**
 * An Event is an internal result, reporting that something has happened.
 */
sealed interface Event : AppAction

// --- Core Commands ---
data class ShowToast(val message: String) : Command
data object ClearToast : Command
data class SetActiveView(val key: String) : Command


// --- AGENT-RELATED CONTRACTS ---

/** A sealed interface for all agent-related public Commands. */
sealed interface AgentCommand : Command {
    /** Dispatched by the UI as a public intent to cancel a turn. */
    data class TurnCancelled(val turnId: String) : AgentCommand
}

/** A sealed interface for all agent-related internal Events. */
sealed interface AgentEvent : Event {
    /** Dispatched by an agent's side-effect logic when a turn begins. */
    data class TurnBegan(val agentId: String, val turnId: String, val parentEntryId: String?) : AgentEvent

    /** Dispatched by an agent's side-effect logic when a turn completes successfully. */
    data class TurnCompleted(val turnId: String, val content: List<ContentBlock>) : AgentEvent

    /** Dispatched by an agent's side-effect logic when a turn fails. */
    data class TurnFailed(val turnId: String, val error: String) : AgentEvent
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