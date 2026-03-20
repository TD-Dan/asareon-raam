package app.auf.feature.agent

import app.auf.util.PlatformDependencies

/**
 * Formats multi-session conversation ledgers into a structured text partition
 * for injection into the agent's system prompt.
 *
 * ## Delimiter Convention (ContextDelimiters §2.6)
 *
 * This formatter produces content at h2/h3 level. The h1 wrapper
 * (`- [ CONVERSATION_LOG ] -`) is added by the pipeline.
 *
 * ```
 * --- SESSION: Pet language studies | uuid: xxx | 3 messages (~400 tokens) [EXPANDED] ---
 *
 *   --- Daniel (user.daniel) @ 2026-03-13T17:13:56Z ---
 *
 * What is your favourite animal?
 *
 *   ---
 *
 * --- END OF SESSION ---
 * ```
 *
 * ## Truncation Direction
 *
 * Sessions truncate from the START (oldest messages removed first).
 * This is set via [ContextCollapseLogic.ContextPartition.truncateFromStart]
 * in the pipeline, not by this formatter.
 *
 * ## Design Decisions
 *
 * - Session order follows the order of [sessions] input (caller controls priority).
 * - Messages within each session are in chronological order.
 * - The formatter is a pure function — no state, no side effects.
 * - Pipeline-level utility, not strategy-owned (§2.3 absolute decoupling).
 */
object ConversationLogFormatter {

    /**
     * A snapshot of one session's ledger for formatting.
     */
    data class SessionLedgerSnapshot(
        val sessionName: String,
        val sessionUUID: String,
        val sessionHandle: String,
        val messages: List<GatewayMessage>,
        val isOutputSession: Boolean = false
    )

    /**
     * Builds a [PromptSection.Group] with per-session children for the unified
     * partition model. Each session becomes an individually collapsible child
     * [PromptSection.Section], enabling per-session budget management and
     * visibility in the Context Manager UI.
     *
     * The child key convention is `session:<uuid>`, matching the
     * `contextCollapseOverrides` key space.
     */
    fun buildSections(
        sessions: List<SessionLedgerSnapshot>,
        platformDependencies: PlatformDependencies
    ): PromptSection.Group {
        if (sessions.isEmpty() || sessions.all { it.messages.isEmpty() }) {
            return PromptSection.Group(
                key = "CONVERSATION_LOG",
                children = listOf(
                    PromptSection.Section(
                        key = "CONVERSATION_LOG:empty",
                        content = "No messages in any subscribed session.",
                        isProtected = true,
                        isCollapsible = false
                    )
                ),
                isCollapsible = true,
                priority = 100,
                collapsedSummary = "[Conversation collapsed — no messages]",
                truncateFromStart = true
            )
        }

        val children = sessions.map { session ->
            val content = buildSessionContent(session, platformDependencies)
            val messageCount = session.messages.size

            PromptSection.Section(
                key = "session:${session.sessionUUID}",
                content = content,
                isProtected = false,
                isCollapsible = true,
                priority = 100,
                collapsedSummary = "[Session '${session.sessionName}' collapsed — $messageCount messages]",
                truncateFromStart = true
            )
        }

        val totalMessages = sessions.sumOf { it.messages.size }
        return PromptSection.Group(
            key = "CONVERSATION_LOG",
            children = children,
            isCollapsible = true,
            priority = 100,
            collapsedSummary = "[Conversation collapsed — $totalMessages messages across ${sessions.size} sessions. " +
                    "Use agent.CONTEXT_UNCOLLAPSE to expand.]",
            truncateFromStart = true
        )
    }

    /**
     * Builds the formatted content string for a single session's messages.
     * Used by both [buildSections] (structured path) and [format] (legacy string path).
     */
    fun buildSessionContent(
        session: SessionLedgerSnapshot,
        platformDependencies: PlatformDependencies
    ): String = buildString {
        if (session.messages.isEmpty()) {
            appendLine("(no messages)")
        } else {
            for (msg in session.messages) {
                val formattedTimestamp = platformDependencies.formatIsoTimestamp(msg.timestamp)
                append(ContextDelimiters.h3("${msg.senderName} (${msg.senderId}) @ $formattedTimestamp"))
                appendLine(msg.content)
                append(ContextDelimiters.h3End())
            }
        }
    }

    /**
     * Formats multiple session ledgers into a single structured conversation log.
     *
     * Returns raw content (no h1 wrapper — pipeline adds it via ContextDelimiters).
     * Each session is an h2 section with token count and collapse state badge.
     *
     * @deprecated Use [buildSections] for the structured partition model.
     *   Retained during migration for backward compatibility.
     */
    fun format(
        sessions: List<SessionLedgerSnapshot>,
        platformDependencies: PlatformDependencies
    ): String = buildString {
        if (sessions.isEmpty() || sessions.all { it.messages.isEmpty() }) {
            appendLine("No messages in any subscribed session.")
            return@buildString
        }

        for (session in sessions) {
            val messageCount = session.messages.size
            val outputTag = if (session.isOutputSession) " | output: true" else ""
            val sessionContent = buildSessionContent(session, platformDependencies)

            val sessionLabel = "SESSION: ${session.sessionName} | uuid: ${session.sessionUUID} | $messageCount messages$outputTag"
            val state = if (session.messages.isEmpty()) ContextDelimiters.COLLAPSED else ContextDelimiters.EXPANDED

            append(ContextDelimiters.h2(sessionLabel, sessionContent.length, state))
            append(sessionContent)
            append(ContextDelimiters.h2End("SESSION"))
        }
    }

    /**
     * Extracts unique participants across all sessions for multi-agent context building.
     */
    fun extractParticipants(sessions: List<SessionLedgerSnapshot>): List<Pair<String, String>> {
        return sessions
            .flatMap { it.messages }
            .map { it.senderId to it.senderName }
            .distinct()
    }
}