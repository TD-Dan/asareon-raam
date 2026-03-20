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
     * Builds a unified `SESSIONS` [PromptSection.Group] that consolidates session
     * subscription metadata, multi-agent participant context, and conversation
     * messages into one coherent structure.
     *
     * The Group is PROTECTED (always visible). Its header contains:
     * 1. Session subscription list with routing tags (STANDARD or PRIVATE format)
     * 2. Multi-agent participant roster (when >2 participants)
     * 3. Message format instructions
     *
     * Each session becomes a collapsible child with its messages.
     *
     * ## Structure
     *
     * ```
     * Group("SESSIONS")                         ← protected, header = subscriptions + participants
     *   ├─ Section("session:chat.main")          ← collapsible, truncateFromStart
     *   └─ Section("session:chat.debug")
     * ```
     *
     * The child key convention is `session:<handle>`.
     */
    fun buildSessionsGroup(
        sessions: List<SessionLedgerSnapshot>,
        sessionInfos: List<SessionInfo>,
        isPrivateFormat: Boolean = false,
        platformDependencies: PlatformDependencies
    ): PromptSection.Group {
        // Build the header: subscription list + multi-agent + format instructions
        val header = buildSessionsHeader(sessions, sessionInfos, isPrivateFormat)

        if (sessions.isEmpty() || sessions.all { it.messages.isEmpty() }) {
            return PromptSection.Group(
                key = "SESSIONS",
                header = header,
                children = listOf(
                    PromptSection.Section(
                        key = "SESSIONS:empty",
                        content = "No messages in any subscribed session.",
                        isProtected = true,
                        isCollapsible = false
                    )
                ),
                isProtected = true,
                isCollapsible = false,
                priority = 1000,
                truncateFromStart = true
            )
        }

        val children = sessions.map { session ->
            val content = buildSessionContent(session, platformDependencies)
            val messageCount = session.messages.size

            PromptSection.Section(
                key = "session:${session.sessionHandle}",
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
            key = "SESSIONS",
            header = header,
            children = children,
            isProtected = true,
            isCollapsible = false,
            priority = 1000,
            collapsedSummary = "[Sessions collapsed — $totalMessages messages across ${sessions.size} sessions]",
            truncateFromStart = true
        )
    }

    /**
     * Builds the SESSIONS header: subscription list, multi-agent roster, format instructions.
     */
    private fun buildSessionsHeader(
        sessions: List<SessionLedgerSnapshot>,
        sessionInfos: List<SessionInfo>,
        isPrivateFormat: Boolean
    ): String = buildString {
        // 1. Subscription list
        if (sessionInfos.isNotEmpty()) {
            appendLine("Subscribed sessions:")
            sessionInfos.forEach { session ->
                if (isPrivateFormat) {
                    val tag = if (session.isOutput) {
                        "PRIVATE — Your direct output is routed here, invisible to others"
                    } else {
                        "PUBLIC — Use session.POST to communicate here"
                    }
                    appendLine("  - ${session.name} (${session.handle}) [$tag] | ${session.messageCount} messages")
                    if (session.participants.isNotEmpty()) {
                        session.participants.forEach { p ->
                            appendLine("      ${p.senderName} (${p.senderId}): ${p.type}, ${p.messageCount} messages")
                        }
                    }
                } else {
                    val primaryTag = if (session.isOutput) {
                        " [PRIMARY — Your output and tool results are routed here]"
                    } else ""
                    appendLine("  - ${session.name} (${session.handle})$primaryTag")
                }
            }
            if (!isPrivateFormat) {
                appendLine("You observe messages from all subscribed sessions. Your responses are posted to the primary session.")
            }
            appendLine()
        }

        // 2. Multi-agent participant roster (only when >2 distinct participants)
        val allParticipants = extractParticipants(sessions)
        if (allParticipants.size > 2) {
            appendLine("MULTI-AGENT ENVIRONMENT:")
            appendLine("This is a multi-agent conversation with the following participants:")
            sessionInfos.flatMap { it.participants }.distinctBy { it.senderId }.forEach { p ->
                appendLine("  - ${p.senderName} (${p.senderId}): ${p.type}")
            }
            appendLine()
        }

        // 3. Message format instructions
        appendLine("Each message in the conversation is wrapped with sender headers (name, id, timestamp).")
        appendLine("When YOU respond, do NOT include these headers — the system adds them automatically.")
    }

    /**
     * Builds the formatted content string for a single session's messages.
     * Used by [buildSessionsGroup] for per-session child content.
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
     * Extracts unique participants across all sessions for multi-agent context building.
     */
    fun extractParticipants(sessions: List<SessionLedgerSnapshot>): List<Pair<String, String>> {
        return sessions
            .flatMap { it.messages }
            .map { it.senderId to it.senderName }
            .distinct()
    }
}