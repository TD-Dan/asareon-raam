package asareon.raam.feature.agent.contextformatters

import asareon.raam.feature.agent.CompressionConfig
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
        platformDependencies: PlatformDependencies,
        compressionConfig: CompressionConfig = CompressionConfig()
    ): PromptSection.Group {
        // Build the header: subscription list + multi-agent + format instructions
        val header = buildSessionsHeader(sessions, sessionInfos, isPrivateFormat, compressionConfig)

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
            val content = buildSessionContent(session, platformDependencies, compressionConfig)
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
        isPrivateFormat: Boolean,
        compressionConfig: CompressionConfig = CompressionConfig()
    ): String = buildString {
        if (sessionInfos.isEmpty()) return@buildString

        val isTerse = compressionConfig.terseSystemText

        // 1. Summary line
        val sessionCount = sessionInfos.size
        val totalParticipants = sessionInfos
            .flatMap { it.participants }
            .distinctBy { it.senderId }
            .size

        if (isTerse) {
            appendLine("$sessionCount sessions, $totalParticipants parts:")
        } else {
            val sessionWord = if (sessionCount == 1) "1 session" else "$sessionCount sessions"
            appendLine("You are participating in $sessionWord with $totalParticipants participants.")
        }

        // 2. Subscription list with per-session participants
        if (isTerse) {
            sessionInfos.forEach { session ->
                val tag = when {
                    isPrivateFormat && session.isOutput -> " [PRIVATE]"
                    session.isOutput -> " [PRIMARY]"
                    else -> " [POST]"
                }
                append("${session.name} (${session.handle})$tag")
                if (session.participants.isNotEmpty()) {
                    append(" ")
                    append(session.participants.joinToString(" | ") { p ->
                        "${p.senderName} (${p.senderId}) ${p.messageCount}msg"
                    })
                }
                appendLine()
            }
        } else {
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
        }

        // 3. Routing instructions
        appendLine()
        val routingKey = if (isPrivateFormat) {
            "SESSION_ROUTING_PRIVATE"
        } else if (sessionCount > 1) {
            "SESSION_ROUTING_STANDARD_MULTI"
        } else {
            "SESSION_ROUTING_STANDARD"
        }
        appendLine(TerseText.get(routingKey, isTerse))

        // 4. Message format instructions
        appendLine()
        appendLine(TerseText.get("SESSION_MSG_FORMAT", isTerse))
    }

    /**
     * Builds the formatted content string for a single session's messages.
     * Used by [buildSessionsGroup] for per-session child content.
     */
    fun buildSessionContent(
        session: SessionLedgerSnapshot,
        platformDependencies: PlatformDependencies,
        compressionConfig: CompressionConfig = CompressionConfig()
    ): String = buildString {
        if (session.messages.isEmpty()) {
            appendLine("(no messages)")
        } else {
            for (msg in session.messages) {
                val formattedTimestamp = platformDependencies.formatIsoTimestamp(msg.timestamp)
                val lockIndicator = if (msg.isLocked) " [🔒]" else ""

                if (compressionConfig.slimMessageHeaders) {
                    // Strategy 6: compact bracket notation, short timestamp, no closing delimiter
                    val shortTs = shortenTimestamp(formattedTimestamp)
                    var header = "[${msg.senderName}|${msg.senderId}|$shortTs$lockIndicator]"
                    if (compressionConfig.abbreviations) {
                        header = TerseText.abbreviate(header)
                    }
                    appendLine(header)
                    appendLine(msg.content)
                } else {
                    append(ContextDelimiters.h3("${msg.senderName} (${msg.senderId}) @ $formattedTimestamp$lockIndicator"))
                    appendLine(msg.content)
                    append(ContextDelimiters.h3End())
                }
            }
        }
    }

    /**
     * Shortens an ISO 8601 timestamp (e.g. "2026-04-10T13:35:28Z") to compact
     * "MM-DD HH:MM" form (e.g. "04-10 13:35"). Falls back to the original
     * string if parsing fails.
     */
    private fun shortenTimestamp(iso: String): String {
        // Expected format: YYYY-MM-DDTHH:MM:SS... — extract MM-DD HH:MM
        return try {
            val datePart = iso.substringBefore('T')
            val timePart = iso.substringAfter('T')
            val monthDay = datePart.substring(datePart.length - 5) // "MM-DD"
            val hourMin = timePart.substring(0, 5) // "HH:MM"
            "$monthDay $hourMin"
        } catch (_: Exception) {
            iso
        }
    }
}