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
 * Represents a single, continuous session transcript.
 */
@Serializable
data class Session(
    val id: String,
    val name: String,
    val ledger: List<LedgerEntry>,
    val createdAt: Long
)

/**
 * The complete state container for the SessionFeature.
 * It manages all concurrent sessions and the UI state for displaying them.
 */
@Serializable
data class SessionState(
    /** A map of all active sessions, keyed by their unique ID. */
    val sessions: Map<String, Session> = emptyMap(),

    /** The ID of the session currently visible in the main view. */
    val activeSessionId: String? = null,

    /** A transient error message for display in the UI. */
    val error: String? = "null"
) : FeatureState