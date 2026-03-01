package app.auf.feature.session

import app.auf.core.FeatureState
import app.auf.core.Identity
import app.auf.core.IdentityHandle
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

/**
 * Persisted per-session input field state: the current draft text and the
 * sent-message history (newest-first, capped at MAX_HISTORY_SIZE).
 * Stored as {uuid}/input.json alongside the session's ledger file.
 */
@Serializable
data class SessionInputState(
    val draft: String = "",
    val history: List<String> = emptyList()
)

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
 * Transient record of a session creation that is awaiting identity approval from CoreFeature.
 * Keyed by UUID in SessionState.pendingCreations.
 */
@Serializable
data class PendingSessionCreation(
    val uuid: String,
    val requestedName: String,
    val isHidden: Boolean = false,
    /** [PHASE 3] The identity handle of the owner, or null if not private. Replaces `isPrivate: Boolean`. */
    val isPrivateTo: IdentityHandle? = null,
    val createdAt: Long,
    /** For clones: the localHandle of the source session to copy the ledger from. */
    val cloneSourceLocalHandle: String? = null
)

/**
 * Represents a single, continuous session transcript.
 * Phase 4: Session identity is now a full Identity object instead of separate id/name fields.
 */
@Serializable
data class Session(
    val identity: Identity,
    val ledger: List<LedgerEntry>,
    val createdAt: Long,
    /** A map of message IDs to their persistent UI state. */
    val messageUiState: Map<String, MessageUiState> = emptyMap(),
    /** When true, this session is hidden from the default view in both the tab bar and manager. */
    val isHidden: Boolean = false,
    /**
     * [PHASE 3] If non-null, this session is private to the specified identity
     * (shown with a lightning bolt icon, excluded from session name broadcasts).
     * Replaces the old `isPrivate: Boolean`. A session is private if `isPrivateTo != null`.
     *
     * Old persisted sessions may still have `"isPrivate": true` with no `isPrivateTo`.
     * These are migrated at load time by the agent infrastructure (Phase 4 hooks),
     * or handled by the compat flag below.
     */
    val isPrivateTo: IdentityHandle? = null,
    /**
     * @deprecated Backward-compat shim. Old session JSON files have `"isPrivate": true`.
     * New code should check `isPrivateTo != null` instead. This field is retained so that
     * kotlinx.serialization can deserialize old files without data loss. Will be removed
     * once all persisted sessions have been re-saved with `isPrivateTo`.
     */
    val isPrivate: Boolean = false,
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
    /** A map of all active sessions, keyed by their identity.localHandle. */
    val sessions: Map<String, Session> = emptyMap(),

    /** The localHandle of the session currently visible in the main view. */
    val activeSessionLocalHandle: String? = null,

    /**
     * The canonical display ordering of sessions, derived from each Session's persisted orderIndex.
     * Recomputed whenever sessions are loaded, created, reordered, or deleted.
     * Both views derive their display list from this ordering.
     */
    @Transient
    val sessionOrder: List<String> = emptyList(),

    /**
     * PERSISTED VIA SETTINGS: When true, the SessionView tab bar hides sessions with isHidden=true.
     * Hydrated from setting key "session.hide_hidden_in_viewer" via SETTINGS_LOADED / VALUE_CHANGED.
     */
    @Transient
    val hideHiddenInViewer: Boolean = true,

    /**
     * PERSISTED VIA SETTINGS: When true, the SessionsManagerView hides sessions with isHidden=true.
     * Hydrated from setting key "session.hide_hidden_in_manager" via SETTINGS_LOADED / VALUE_CHANGED.
     */
    @Transient
    val hideHiddenInManager: Boolean = true,

    /** TRANSIENT UI STATE: The localHandle of the session whose name is being edited. */
    @Transient
    val editingSessionLocalHandle: String? = null,

    /** TRANSIENT UI STATE: The ID of the message being edited. */
    @Transient
    val editingMessageId: String? = null,

    /** TRANSIENT UI STATE: The raw content of the message being edited. */
    @Transient
    val editingMessageContent: String? = null,

    /** Cached from core.IDENTITIES_UPDATED broadcast — the active user's handle for sender attribution. */
    @Transient
    val activeUserId: String? = null,

    /** A transient error message for display in the UI. */
    @Transient
    val error: String? = null,

    /** A transient field to reliably pass the localHandle of a deleted session from the reducer to the side effects handler. */
    @Transient
    val lastDeletedSessionLocalHandle: String? = null,

    /** Pending session creations awaiting identity approval, keyed by UUID. */
    @Transient
    val pendingCreations: Map<String, PendingSessionCreation> = emptyMap(),

    // ── Input draft & history ─────────────────────────────────────────────
    // All five fields are @Transient so they are never written into the session
    // JSON ledger file. They are persisted separately via {uuid}/input.json.

    /** Live draft text for each session, keyed by localHandle. */
    @Transient
    val draftInputs: Map<String, String> = emptyMap(),

    /** Sent-message history per session, keyed by localHandle, newest-first. */
    @Transient
    val inputHistories: Map<String, List<String>> = emptyMap(),

    /**
     * History navigation cursor per session, keyed by localHandle.
     * Absent (or -1) means the user is not currently navigating history.
     * 0 = most recent entry, history.lastIndex = oldest entry.
     */
    @Transient
    val historyNavIndex: Map<String, Int> = emptyMap(),

    /**
     * The draft text that was in the input field before the user started
     * navigating history (saved on the first UP press, restored on DOWN
     * past index 0). Keyed by localHandle.
     */
    @Transient
    val preNavDrafts: Map<String, String> = emptyMap(),

    /**
     * Buffer for input.json data that arrived before its session was loaded
     * from disk (startup race). Keyed by session UUID.
     * Drained into draftInputs/inputHistories when SESSION_LOADED fires.
     */
    @Transient
    val pendingInputLoads: Map<String, SessionInputState> = emptyMap()

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
                .map { it.identity.localHandle }
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
            ordered.forEachIndexed { index, localHandle ->
                val session = normalized[localHandle] ?: return@forEachIndexed
                if (session.orderIndex != index) {
                    normalized[localHandle] = session.copy(orderIndex = index)
                    changed = true
                }
            }
            return if (changed) normalized else sessions
        }
    }
}