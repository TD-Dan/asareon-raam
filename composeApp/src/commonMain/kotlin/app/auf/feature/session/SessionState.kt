package app.auf.feature.session

import app.auf.core.FeatureState
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

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
 * This has been updated to support both traditional messages and UI-only entries via metadata.
 */
@Serializable
data class LedgerEntry(
    val id: String,
    val timestamp: Long,
    /** RENAMED: The identifier for the originator of this entry (e.g., "user", or an agent's ID). */
    val senderId: String,
    /** The original, unmodified string content of the message. Can be null for UI-only entries. */
    val rawContent: String? = null,
    /** The structured, parsed representation of the rawContent. */
    val content: List<ContentBlock> = emptyList(),
    /** NEW: A generic metadata object for UI hints or embedding components from other features. */
    val metadata: JsonObject? = null,
    /** A locked message cannot be edited, deleted, or cleared. Acts as a durable preservation flag. */
    val isLocked: Boolean = false
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
    val messageUiState: Map<String, MessageUiState> = emptyMap(),
    /** When true, this session is hidden from the default view in both the tab bar and manager. */
    val isHidden: Boolean = false,
    /** When true, this session is an agent's private cognition session (shown with a lightning bolt icon). */
    val isAgentPrivate: Boolean = false
)

/**
 * The complete state container for the SessionFeature.
 * It manages all concurrent sessions and the UI state for displaying them.
 */
@Serializable
data class SessionState(
    /** A map of all active sessions, keyed by their unique ID. */
    val sessions: Map<String, Session> = emptyMap(),

    /** A unified local cache of all identity IDs (user and agent) to their display names. */
    // ADDITION: Initialize with a default "system" identity for sentinel messages.
    val identityNames: Map<String, String> = mapOf("system" to "SYSTEM SENTINEL"),

    /** The ID of the session currently visible in the main view. */
    val activeSessionId: String? = null,

    /** The canonical display ordering of sessions. Both views derive their order from this list. */
    val sessionOrder: List<String> = emptyList(),

    /** TRANSIENT UI STATE: When true, the SessionView tab bar hides sessions with isHidden=true. */
    @Transient
    val hideHiddenInViewer: Boolean = true,

    /** TRANSIENT UI STATE: When true, the SessionsManagerView hides sessions with isHidden=true. */
    @Transient
    val hideHiddenInManager: Boolean = true,

    /** TRANSIENT UI STATE: The ID of the session whose name is being edited. */
    @Transient
    val editingSessionId: String? = null,

    /** TRANSIENT UI STATE: The ID of the message being edited. */
    @Transient
    val editingMessageId: String? = null,

    /** TRANSIENT UI STATE: The raw content of the message being edited. */
    @Transient
    val editingMessageContent: String? = null,

    /** A transient error message for display in the UI. */
    @Transient
    val error: String? = null,

    /** THE FIX: A transient field to reliably pass the ID of a deleted session from the reducer to the onAction handler. */
    @Transient
    val lastDeletedSessionId: String? = null
) : FeatureState