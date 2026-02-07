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
    val isLocked: Boolean = false,
    /** When true, this entry survives a SESSION_CLEAR even if not locked. Used for transient UI entries like agent avatar cards. */
    val doNotClear: Boolean = false
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
    val isAgentPrivate: Boolean = false,
    /**
     * Persistent ordering index for this session.
     * Lower values sort first. Ties are broken by createdAt descending (newest first).
     * Sessions created without an explicit drag-reorder keep orderIndex = 0
     * and rely on the createdAt tiebreaker for natural chronological ordering.
     * This field is persisted inside each session's JSON file.
     */
    val orderIndex: Int = 0
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

    /**
     * The canonical display ordering of sessions, derived from each Session's persisted orderIndex.
     * Recomputed whenever sessions are loaded, created, reordered, or deleted.
     * Both views derive their display list from this ordering.
     */
    @Transient
    val sessionOrder: List<String> = emptyList(),

    /**
     * PERSISTED VIA SETTINGS: When true, the SessionView tab bar hides sessions with isHidden=true.
     * Hydrated from setting key "session.hide_hidden_in_viewer" via SETTINGS_PUBLISH_LOADED / VALUE_CHANGED.
     */
    @Transient
    val hideHiddenInViewer: Boolean = true,

    /**
     * PERSISTED VIA SETTINGS: When true, the SessionsManagerView hides sessions with isHidden=true.
     * Hydrated from setting key "session.hide_hidden_in_manager" via SETTINGS_PUBLISH_LOADED / VALUE_CHANGED.
     */
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
) : FeatureState {

    companion object {
        /** Setting keys used by this feature, registered with the Settings feature at startup. */
        const val SETTING_HIDE_HIDDEN_VIEWER = "session.hide_hidden_in_viewer"
        const val SETTING_HIDE_HIDDEN_MANAGER = "session.hide_hidden_in_manager"

        /**
         * Derives a canonical session order list from the sessions map.
         * Sessions are sorted by orderIndex ascending, with ties broken by createdAt descending
         * (newest first). This ensures newly created sessions (all at orderIndex=0) appear in
         * reverse-chronological order, while drag-reordered sessions respect their assigned indices.
         */
        fun deriveSessionOrder(sessions: Map<String, Session>): List<String> {
            return sessions.values
                .sortedWith(
                    compareBy<Session> { it.orderIndex }
                        .thenByDescending { it.createdAt }
                )
                .map { it.id }
        }

        /**
         * Assigns contiguous orderIndex values (0, 1, 2, …) to all sessions based on the
         * canonical derived order. This heals gaps, duplicates, or negative indices that may
         * accumulate over time.
         *
         * Returns the sessions map with corrected orderIndex values, or the original map
         * unchanged if all indices were already contiguous.
         */
        fun normalizeOrderIndices(sessions: Map<String, Session>): Map<String, Session> {
            val ordered = deriveSessionOrder(sessions)
            var changed = false
            val normalized = sessions.toMutableMap()
            ordered.forEachIndexed { index, id ->
                val session = normalized[id] ?: return@forEachIndexed
                if (session.orderIndex != index) {
                    normalized[id] = session.copy(orderIndex = index)
                    changed = true
                }
            }
            return if (changed) normalized else sessions
        }
    }
}