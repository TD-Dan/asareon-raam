package asareon.raam.feature.agent.contextformatters

import asareon.raam.feature.agent.ContextDelimiters
import asareon.raam.feature.agent.GatewayMessage
import asareon.raam.feature.agent.PromptSection
import asareon.raam.feature.agent.SessionInfo
import asareon.raam.util.PlatformDependencies

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
 * This is set via [asareon.raam.feature.agent.ContextCollapseLogic.ContextPartition.truncateFromStart]
 * in the pipeline, not by this formatter.
 *
 * ## Design Decisions
 *
 * - Session order follows the order of [sessions] input (caller controls priority).
 * - Messages within each session are in chronological order.
 * - The formatter is a pure function — no state, no side effects.
 * - Pipeline-level utility, not strategy-owned (§2.3 absolute decoupling).
 */
object SessionContextFormatter {

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
     * Builds a unified `SESSIONS` [asareon.raam.feature.agent.PromptSection.Group] that consolidates session
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
     * Builds the SESSIONS header: summary line, subscription list with per-session
     * participant rosters, routing instructions, and message format notice.
     */
    private fun buildSessionsHeader(
        sessions: List<SessionLedgerSnapshot>,
        sessionInfos: List<SessionInfo>,
        isPrivateFormat: Boolean
    ): String = buildString {
        if (sessionInfos.isEmpty()) return@buildString

        // 1. Summary line
        val sessionCount = sessionInfos.size
        val totalParticipants = sessionInfos
            .flatMap { it.participants }
            .distinctBy { it.senderId }
            .size
        val sessionWord = if (sessionCount == 1) "1 session" else "$sessionCount sessions"
        appendLine("You are participating in $sessionWord with $totalParticipants participants.")

        // 2. Subscription list with per-session participants
        appendLine("Subscribed sessions:")
        sessionInfos.forEach { session ->
            val tag = when {
                isPrivateFormat && session.isOutput ->
                    " [PRIVATE — Your direct output is routed here, invisible to others]"
                isPrivateFormat ->
                    " [Use session.POST to publish here]"
                session.isOutput ->
                    " [PRIMARY — Your output and tool results are routed here]"
                else ->
                    " [Use session.POST to publish here]"
            }
            appendLine("  - ${session.name} (${session.handle})$tag")
            if (session.participants.isNotEmpty()) {
                session.participants.forEach { p ->
                    appendLine("    - ${p.senderName} (${p.senderId}): ${p.type} (${p.messageCount} messages)")
                }
            }
        }

        // 3. Routing instructions
        appendLine()
        if (isPrivateFormat) {
            appendLine("You observe messages from all subscribed sessions. Your direct response goes to your private session.")
            appendLine("Use session.POST to publish to public sessions.")
        } else {
            appendLine("You observe messages from all subscribed sessions. Your responses are posted to the primary session.")
            if (sessionCount > 1) {
                appendLine("Use session.POST to publish to other sessions.")
            }
        }

        // 4. Message format instructions
        appendLine()
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

}