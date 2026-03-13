package app.auf.feature.agent

import app.auf.util.PlatformDependencies

/**
 * Formats multi-session conversation ledgers into a structured text partition
 * for injection into the agent's system prompt.
 *
 * ## Delimiter Convention
 *
 * ```
 * ---              → top-level section boundary
 *  ---             → partition-level boundary (individual session)
 *   ---            → entry-level boundary (individual message)
 * ```
 *
 * Content (message bodies) sits at zero indent between `  ---` delimiters,
 * making it unambiguous even if messages contain markdown `---` rules.
 *
 * ## Output Format
 *
 * ```
 * --- CONVERSATION LOG ---
 *  --- SESSION: Pet language studies | uuid: xxx | 3 messages ---
 *   --- Daniel (user.daniel) @ 2026-03-13T17:13:56Z ---
 * What is your favourite animal?
 *   ---
 *  --- END OF SESSION ---
 * --- END OF CONVERSATION LOG ---
 * ```
 *
 * ## Design Decisions
 *
 * - Session order follows the order of [sessions] input (caller controls priority).
 * - Messages within each session are in chronological order (as received from ledger).
 * - The formatter is a pure function — no state, no side effects.
 * - This is a pipeline-level utility, not strategy-owned (§2.3 absolute decoupling).
 * - Designed as a context partition that participates in the collapse/budget system.
 */
object ConversationLogFormatter {

    /**
     * A snapshot of one session's ledger for formatting.
     *
     * @param sessionName Human-readable session name.
     * @param sessionUUID Immutable session UUID.
     * @param sessionHandle Session handle (e.g., "session.pet-language-studies").
     * @param messages Chronologically ordered messages from this session's ledger.
     * @param isOutputSession True if this is the agent's primary output session.
     */
    data class SessionLedgerSnapshot(
        val sessionName: String,
        val sessionUUID: String,
        val sessionHandle: String,
        val messages: List<GatewayMessage>,
        val isOutputSession: Boolean = false
    )

    /**
     * Formats multiple session ledgers into a single structured conversation log.
     *
     * @param sessions The session ledgers to format. Order is preserved.
     * @param platformDependencies Used for timestamp formatting.
     * @return The formatted conversation log string, or an empty-session notice.
     */
    fun format(
        sessions: List<SessionLedgerSnapshot>,
        platformDependencies: PlatformDependencies
    ): String = buildString {
        appendLine("--- CONVERSATION LOG ---")

        if (sessions.isEmpty() || sessions.all { it.messages.isEmpty() }) {
            appendLine("No messages in any subscribed session.")
            appendLine("--- END OF CONVERSATION LOG ---")
            return@buildString
        }

        for (session in sessions) {
            val messageCount = session.messages.size
            val outputTag = if (session.isOutputSession) " | output: true" else ""

            appendLine(" --- SESSION: ${session.sessionName} | uuid: ${session.sessionUUID} | $messageCount messages$outputTag ---")

            if (session.messages.isEmpty()) {
                appendLine("  (no messages)")
            } else {
                for (msg in session.messages) {
                    val formattedTimestamp = platformDependencies.formatIsoTimestamp(msg.timestamp)
                    appendLine("  --- ${msg.senderName} (${msg.senderId}) @ $formattedTimestamp ---")
                    appendLine(msg.content)
                    appendLine("  ---")
                }
            }

            appendLine(" --- END OF SESSION ---")
        }

        appendLine("--- END OF CONVERSATION LOG ---")
    }

    /**
     * Extracts unique participants across all sessions for multi-agent context building.
     *
     * @return List of (senderId, senderName) pairs, deduplicated.
     */
    fun extractParticipants(sessions: List<SessionLedgerSnapshot>): List<Pair<String, String>> {
        return sessions
            .flatMap { it.messages }
            .map { it.senderId to it.senderName }
            .distinct()
    }
}