package asareon.raam.feature.agent.contextformatters

import asareon.raam.fakes.FakePlatformDependencies
import asareon.raam.feature.agent.GatewayMessage
import asareon.raam.feature.agent.PromptSection
import asareon.raam.feature.agent.SessionInfo
import asareon.raam.feature.agent.SessionParticipant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tier 1 Unit Tests for SessionContextFormatter.
 *
 * SessionContextFormatter is a pipeline-level pure utility (§2.3 absolute decoupling)
 * that formats multi-session conversation ledgers into structured [PromptSection] partitions
 * for injection into the agent's system prompt (§6.3, §6.5).
 *
 * Tests verify:
 * - buildSessionsGroup returns a correct PromptSection.Group structure
 * - Multi-session formatting with session names and message counts
 * - Empty session handling (all empty → notice, some empty → "(no messages)")
 * - buildSessionContent: message entries with sender and timestamp
 * - Session ordering and chronological message ordering
 */
class SessionContextFormatterT1Test {

    private val platform = FakePlatformDependencies("test")

    // =========================================================================
    // Helper
    // =========================================================================

    private fun snapshot(
        name: String,
        uuid: String,
        handle: String,
        messages: List<GatewayMessage>,
        isOutput: Boolean = false
    ) = SessionContextFormatter.SessionLedgerSnapshot(
        sessionName = name,
        sessionUUID = uuid,
        sessionHandle = handle,
        messages = messages,
        isOutputSession = isOutput
    )

    private fun msg(
        role: String,
        content: String,
        senderId: String,
        senderName: String,
        timestamp: Long
    ) = GatewayMessage(role, content, senderId, senderName, timestamp)

    /**
     * Helper to build a minimal [SessionInfo] list matching the given snapshots.
     * Extracts unique participants from messages per session.
     */
    private fun sessionInfosFor(
        sessions: List<SessionContextFormatter.SessionLedgerSnapshot>
    ): List<SessionInfo> = sessions.map { session ->
        val participants = session.messages
            .distinctBy { it.senderId }
            .map { m ->
                SessionParticipant(
                    senderId = m.senderId,
                    senderName = m.senderName,
                    type = m.role,
                    messageCount = session.messages.count { it.senderId == m.senderId }
                )
            }
        SessionInfo(
            name = session.sessionName,
            handle = session.sessionHandle,
            uuid = session.sessionUUID,
            isOutput = session.isOutputSession,
            participants = participants
        )
    }

    /**
     * Renders a [PromptSection.Group] to a flat string for content assertions.
     * Concatenates header + children content recursively.
     */
    private fun PromptSection.Group.renderFlat(): String = buildString {
        if (header.isNotBlank()) appendLine(header)
        for (child in children) {
            when (child) {
                is PromptSection.Section -> appendLine(child.content)
                is PromptSection.Group -> append(child.renderFlat())
                else -> {}
            }
        }
    }

    /**
     * Convenience: build the sessions group and render to string for assertions.
     */
    private fun buildAndRender(
        sessions: List<SessionContextFormatter.SessionLedgerSnapshot>,
        isPrivateFormat: Boolean = false
    ): String {
        val sessionInfos = sessionInfosFor(sessions)
        val group = SessionContextFormatter.buildSessionsGroup(
            sessions = sessions,
            sessionInfos = sessionInfos,
            isPrivateFormat = isPrivateFormat,
            platformDependencies = platform
        )
        return group.renderFlat()
    }

    // =========================================================================
    // 1. Basic formatting — single session with messages
    // =========================================================================

    @Test
    fun `buildSessionsGroup should produce a SESSIONS group`() {
        val sessions = listOf(
            snapshot("Chat", "s1", "session.chat",
                listOf(msg("user", "Hello!", "user.alice", "Alice", 1000L)))
        )

        val sessionInfos = sessionInfosFor(sessions)
        val group = SessionContextFormatter.buildSessionsGroup(
            sessions, sessionInfos, platformDependencies = platform
        )

        assertEquals("SESSIONS", group.key, "Group key should be SESSIONS")
        assertTrue(group.isProtected, "SESSIONS group should be protected")
    }

    @Test
    fun `buildSessionsGroup should include session child with correct key`() {
        val sessions = listOf(
            snapshot("Chat", "s1-uuid", "session.chat",
                listOf(
                    msg("user", "Hello", "u1", "Alice", 1000L),
                    msg("model", "Hi!", "a1", "Bot", 2000L)
                ))
        )

        val sessionInfos = sessionInfosFor(sessions)
        val group = SessionContextFormatter.buildSessionsGroup(
            sessions, sessionInfos, platformDependencies = platform
        )

        val sessionChild = group.children.filterIsInstance<PromptSection.Section>()
            .find { it.key == "session:session.chat" }
        assertTrue(sessionChild != null, "Should have a child section keyed session:session.chat")
    }

    @Test
    fun `buildSessionContent should include sender and timestamp`() {
        val session = snapshot("Chat", "s1", "session.chat",
            listOf(msg("user", "What is 2+2?", "user.alice", "Alice", 5000L)))

        val content = SessionContextFormatter.buildSessionContent(session, platform)

        assertTrue(content.contains("Alice (user.alice)"), "Should include sender name and ID")
        assertTrue(content.contains("2026-01-01T00:00:05Z"), "Should format timestamp via platform")
        assertTrue(content.contains("What is 2+2?"), "Should include message content")
    }

    @Test
    fun `buildSessionContent should include message content`() {
        val session = snapshot("Chat", "s1", "session.chat",
            listOf(msg("user", "Hello world", "u1", "Alice", 1000L)))

        val content = SessionContextFormatter.buildSessionContent(session, platform)

        assertTrue(content.contains("Hello world"), "Message content should be present")
    }

    // =========================================================================
    // 2. Header content
    // =========================================================================

    @Test
    fun `buildSessionsGroup header should list subscribed sessions`() {
        val sessions = listOf(
            snapshot("Chat", "s1", "session.chat",
                listOf(msg("user", "Hey", "u1", "Alice", 1000L)))
        )

        val result = buildAndRender(sessions)

        assertTrue(result.contains("Chat (session.chat)"),
            "Header should list session name and handle")
    }

    @Test
    fun `buildSessionsGroup header should show participant count`() {
        val sessions = listOf(
            snapshot("Chat", "s1", "session.chat",
                listOf(
                    msg("user", "Hey", "u1", "Alice", 1000L),
                    msg("model", "Hi", "a1", "Bot", 2000L)
                ))
        )

        val result = buildAndRender(sessions)

        assertTrue(result.contains("2 participants"),
            "Header should show total participant count")
    }

    // =========================================================================
    // 3. Multi-session formatting
    // =========================================================================

    @Test
    fun `buildSessionsGroup should include all sessions as children`() {
        val sessions = listOf(
            snapshot("Chat", "s1", "session.chat",
                listOf(msg("user", "Hello", "u1", "Alice", 1000L))),
            snapshot("General", "s2", "session.general",
                listOf(msg("user", "World", "u2", "Bob", 2000L)))
        )

        val sessionInfos = sessionInfosFor(sessions)
        val group = SessionContextFormatter.buildSessionsGroup(
            sessions, sessionInfos, platformDependencies = platform
        )

        val childKeys = group.children.map { (it as PromptSection.Section).key }
        assertTrue(childKeys.contains("session:session.chat"), "Should contain Chat session")
        assertTrue(childKeys.contains("session:session.general"), "Should contain General session")
    }

    @Test
    fun `buildSessionContent should include multiple messages in chronological order`() {
        val session = snapshot("Chat", "s1", "session.chat", listOf(
            msg("user", "First", "u1", "Alice", 1000L),
            msg("model", "Second", "a1", "Bot", 2000L),
            msg("user", "Third", "u1", "Alice", 3000L)
        ))

        val content = SessionContextFormatter.buildSessionContent(session, platform)

        val firstPos = content.indexOf("First")
        val secondPos = content.indexOf("Second")
        val thirdPos = content.indexOf("Third")
        assertTrue(firstPos < secondPos && secondPos < thirdPos,
            "Messages should be in chronological order")
    }

    // =========================================================================
    // 4. Private format tagging
    // =========================================================================

    @Test
    fun `buildSessionsGroup with isPrivateFormat should include PRIVATE tag for output session`() {
        val sessions = listOf(
            snapshot("Private", "p1", "session.private",
                listOf(msg("model", "Thinking...", "a1", "Bot", 1000L)),
                isOutput = true)
        )

        val result = buildAndRender(sessions, isPrivateFormat = true)

        assertTrue(result.contains("PRIVATE"),
            "Output session in private format should be tagged PRIVATE")
    }

    @Test
    fun `buildSessionsGroup with non-private format should include PRIMARY tag for output session`() {
        val sessions = listOf(
            snapshot("Chat", "s1", "session.chat",
                listOf(msg("user", "Hi", "u1", "Alice", 1000L)),
                isOutput = true)
        )

        val result = buildAndRender(sessions, isPrivateFormat = false)

        assertTrue(result.contains("PRIMARY"),
            "Output session in standard format should be tagged PRIMARY")
    }

    // =========================================================================
    // 5. Empty session handling
    // =========================================================================

    @Test
    fun `buildSessionsGroup with all sessions empty should show no-messages notice`() {
        val sessions = listOf(
            snapshot("Chat", "s1", "session.chat", emptyList()),
            snapshot("General", "s2", "session.general", emptyList())
        )

        val result = buildAndRender(sessions)

        assertTrue(result.contains("No messages in any subscribed session"))
    }

    @Test
    fun `buildSessionsGroup with empty session list should show no-messages notice`() {
        val group = SessionContextFormatter.buildSessionsGroup(
            emptyList(), emptyList(), platformDependencies = platform
        )
        val result = group.renderFlat()

        assertTrue(result.contains("No messages in any subscribed session"))
    }

    @Test
    fun `buildSessionContent with empty messages should show no messages marker`() {
        val session = snapshot("Empty Room", "s2", "session.empty", emptyList())

        val content = SessionContextFormatter.buildSessionContent(session, platform)

        assertTrue(content.contains("(no messages)"))
    }

    @Test
    fun `buildSessionsGroup with one empty session and one with messages should have both children`() {
        val sessions = listOf(
            snapshot("Chat", "s1", "session.chat",
                listOf(msg("user", "Hello", "u1", "Alice", 1000L))),
            snapshot("Empty Room", "s2", "session.empty", emptyList())
        )

        val sessionInfos = sessionInfosFor(sessions)
        val group = SessionContextFormatter.buildSessionsGroup(
            sessions, sessionInfos, platformDependencies = platform
        )

        // Both sessions should be present as children
        assertEquals(2, group.children.size, "Should have 2 session children")

        // The empty session child should have "(no messages)" in its content
        val emptyChild = group.children.filterIsInstance<PromptSection.Section>()
            .find { it.key == "session:session.empty" }
        assertTrue(emptyChild != null && emptyChild.content.contains("(no messages)"),
            "Empty session should show (no messages)")
    }

    // =========================================================================
    // 6. Content with special characters
    // =========================================================================

    @Test
    fun `buildSessionContent should handle message content containing markdown hr delimiter`() {
        val session = snapshot("Chat", "s1", "session.chat",
            listOf(msg("user", "Here is a divider:\n---\nEnd.", "u1", "Alice", 1000L)))

        val content = SessionContextFormatter.buildSessionContent(session, platform)

        assertTrue(content.contains("Here is a divider:"))
        assertTrue(content.contains("End."))
    }

    @Test
    fun `buildSessionContent should handle empty message content`() {
        val session = snapshot("Chat", "s1", "session.chat",
            listOf(msg("user", "", "u1", "Alice", 1000L)))

        val content = SessionContextFormatter.buildSessionContent(session, platform)

        assertTrue(content.contains("Alice (u1)"))
    }

    // =========================================================================
    // 7. Collapsed summary
    // =========================================================================

    @Test
    fun `session children should have collapsedSummary with message count`() {
        val sessions = listOf(
            snapshot("Chat", "s1", "session.chat", listOf(
                msg("user", "A", "u1", "Alice", 1000L),
                msg("user", "B", "u1", "Alice", 2000L),
                msg("model", "C", "a1", "Bot", 3000L)
            ))
        )

        val sessionInfos = sessionInfosFor(sessions)
        val group = SessionContextFormatter.buildSessionsGroup(
            sessions, sessionInfos, platformDependencies = platform
        )

        val child = group.children.first() as PromptSection.Section
        assertTrue(child.collapsedSummary?.contains("3 messages") == true,
            "Collapsed summary should show message count")
    }

    @Test
    fun `empty session child should have collapsedSummary with 0 messages`() {
        val sessions = listOf(
            snapshot("Active", "s1", "session.active",
                listOf(msg("user", "Hi", "u1", "Alice", 1000L))),
            snapshot("Empty", "s2", "session.empty", emptyList())
        )

        val sessionInfos = sessionInfosFor(sessions)
        val group = SessionContextFormatter.buildSessionsGroup(
            sessions, sessionInfos, platformDependencies = platform
        )

        val emptyChild = group.children.filterIsInstance<PromptSection.Section>()
            .find { it.key == "session:session.empty" }
        assertTrue(emptyChild?.collapsedSummary?.contains("0 messages") == true,
            "Empty session collapsed summary should show 0 messages")
    }
}