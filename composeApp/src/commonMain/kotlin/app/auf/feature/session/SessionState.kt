package app.auf.feature.session

import app.auf.core.FeatureState
import kotlinx.serialization.Serializable

/**
 * A sealed interface representing a single block of content within a message.
 * This allows for rich, structured messages instead of simple strings.
 */
@Serializable
sealed interface ContentBlock {
    @Serializable
    data class Text(val text: String) : ContentBlock
    @Serializable
    data class CodeBlock(val language: String, val code: String) : ContentBlock
}

/**
 * Represents a single, immutable entry in a session's ledger.
 * System messages are denoted by an `agentId` with a 'system.*' prefix.
 */
@Serializable
data class LedgerEntry(
    val id: String,
    val timestamp: Long,
    val agentId: String,
    /** The original, unmodified string content of the message. */
    val rawContent: String,
    /** The structured, parsed representation of the rawContent. */
    val content: List<ContentBlock>
)

/**
 * Represents the persistent UI state for a single message.
 * Storing this within the Session object ensures it is saved across restarts.
 */
@Serializable
data class MessageUiState(
    val isCollapsed: Boolean = false,
    val isRawView: Boolean = false
)

/**
 * Represents a single, continuous session transcript.
 */
@Serializable
data class Session(
    val id: String,
    val name: String,
    val ledger: List<LedgerEntry>,
    val createdAt: Long,
    /** A map of message IDs to their persistent UI state. */
    val messageUiState: Map<String, MessageUiState> = emptyMap()
)

/**
 * The complete state container for the SessionFeature.
 * It manages all concurrent sessions and the UI state for displaying them.
 */
@Serializable
data class SessionState(
    /** A map of all active sessions, keyed by their unique ID. */
    val sessions: Map<String, Session> = emptyMap(),

    /** A local cache of agent IDs to their human-readable names. */
    val agentNames: Map<String, String> = emptyMap(),

    /** The ID of the session currently visible in the main view. */
    val activeSessionId: String? = null,

    /** TRANSIENT UI STATE: The ID of the session whose name is being edited. */
    val editingSessionId: String? = null,

    /** TRANSIENT UI STATE: The ID of the message being edited. */
    val editingMessageId: String? = null,

    /** TRANSIENT UI STATE: The raw content of the message being edited. */
    val editingMessageContent: String? = null,

    /** A transient error message for display in the UI. */
    val error: String? = null
) : FeatureState