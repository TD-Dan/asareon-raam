package app.auf.feature.agent

import app.auf.fakes.FakePlatformDependencies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tier 1 Unit Tests for ConversationLogFormatter.
 *
 * ConversationLogFormatter is a pipeline-level pure utility (§2.3 absolute decoupling)
 * that formats multi-session conversation ledgers into a structured text partition
 * for injection into the agent's system prompt (§6.3, §6.5).
 *
 * Tests verify:
 * - Delimiter convention: `---` / ` ---` / `  ---` indentation hierarchy (§2.6)
 * - Multi-session formatting with session names, UUIDs, and message counts
 * - Empty session handling (all empty → notice, some empty → "(no messages)")
 * - Output session tagging (isOutputSession flag)
 * - Timestamp formatting delegation to PlatformDependencies
 * - extractParticipants: deduplication across sessions
 */
class AgentRuntimeFeatureT1ConversationLogFormatterTest {

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
    ) = ConversationLogFormatter.SessionLedgerSnapshot(
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

    // =========================================================================
    // 1. Basic formatting — single session with messages
    // =========================================================================

    @Test
    fun `format should produce CONVERSATION LOG envelope`() {
        val sessions = listOf(
            snapshot("Chat", "s1", "session.chat",
                listOf(msg("user", "Hello!", "user.alice", "Alice", 1000L)))
        )

        val result = ConversationLogFormatter.format(sessions, platform)

        assertTrue(result.startsWith("--- CONVERSATION LOG ---"), "Should open with top-level delimiter")
        assertTrue(result.trimEnd().endsWith("--- END OF CONVERSATION LOG ---"), "Should close with top-level delimiter")
    }

    @Test
    fun `format should include session header with name uuid and message count`() {
        val sessions = listOf(
            snapshot("Chat", "s1-uuid", "session.chat",
                listOf(
                    msg("user", "Hello", "u1", "Alice", 1000L),
                    msg("model", "Hi!", "a1", "Bot", 2000L)
                ))
        )

        val result = ConversationLogFormatter.format(sessions, platform)

        assertTrue(result.contains("SESSION: Chat"), "Should include session name")
        assertTrue(result.contains("uuid: s1-uuid"), "Should include session UUID")
        assertTrue(result.contains("2 messages"), "Should include message count")
    }

    @Test
    fun `format should include message entries with sender and timestamp`() {
        val sessions = listOf(
            snapshot("Chat", "s1", "session.chat",
                listOf(msg("user", "What is 2+2?", "user.alice", "Alice", 5000L)))
        )

        val result = ConversationLogFormatter.format(sessions, platform)

        assertTrue(result.contains("Alice (user.alice)"), "Should include sender name and ID")
        assertTrue(result.contains("2026-01-01T00:00:05Z"), "Should format timestamp via platform")
        assertTrue(result.contains("What is 2+2?"), "Should include message content")
    }

    @Test
    fun `format should include message content at zero indent between entry delimiters`() {
        val sessions = listOf(
            snapshot("Chat", "s1", "session.chat",
                listOf(msg("user", "Hello world", "u1", "Alice", 1000L)))
        )

        val result = ConversationLogFormatter.format(sessions, platform)

        val lines = result.lines()
        val contentLine = lines.find { it.contains("Hello world") }
        assertTrue(contentLine != null && !contentLine.startsWith(" "),
            "Message content should be at zero indent")
    }

    // =========================================================================
    // 2. Delimiter convention (§2.6)
    // =========================================================================

    @Test
    fun `format should use three-level delimiter hierarchy`() {
        val sessions = listOf(
            snapshot("Chat", "s1", "session.chat",
                listOf(msg("user", "Hey", "u1", "Alice", 1000L)))
        )

        val result = ConversationLogFormatter.format(sessions, platform)
        val lines = result.lines()

        assertTrue(lines.any { it.startsWith("---") && it.contains("CONVERSATION LOG") },
            "Top-level delimiter at column 0")
        assertTrue(lines.any { it.startsWith(" ---") && it.contains("SESSION:") },
            "Partition delimiter with single-space indent")
        assertTrue(lines.any { it.startsWith("  ---") && it.contains("Alice") },
            "Entry delimiter with two-space indent")
    }

    @Test
    fun `format should close each session with END OF SESSION`() {
        val sessions = listOf(
            snapshot("Chat", "s1", "session.chat",
                listOf(msg("user", "Hey", "u1", "Alice", 1000L)))
        )

        val result = ConversationLogFormatter.format(sessions, platform)

        assertTrue(result.contains(" --- END OF SESSION ---"),
            "Each session should close with indented END OF SESSION")
    }

    // =========================================================================
    // 3. Multi-session formatting
    // =========================================================================

    @Test
    fun `format should include all sessions in order`() {
        val sessions = listOf(
            snapshot("Chat", "s1", "session.chat",
                listOf(msg("user", "Hello", "u1", "Alice", 1000L))),
            snapshot("General", "s2", "session.general",
                listOf(msg("user", "World", "u2", "Bob", 2000L)))
        )

        val result = ConversationLogFormatter.format(sessions, platform)

        val chatPos = result.indexOf("SESSION: Chat")
        val generalPos = result.indexOf("SESSION: General")
        assertTrue(chatPos < generalPos, "Sessions should appear in input order")
        assertTrue(result.contains("Hello"), "First session content present")
        assertTrue(result.contains("World"), "Second session content present")
    }

    @Test
    fun `format should include multiple messages per session in chronological order`() {
        val sessions = listOf(
            snapshot("Chat", "s1", "session.chat", listOf(
                msg("user", "First", "u1", "Alice", 1000L),
                msg("model", "Second", "a1", "Bot", 2000L),
                msg("user", "Third", "u1", "Alice", 3000L)
            ))
        )

        val result = ConversationLogFormatter.format(sessions, platform)

        val firstPos = result.indexOf("First")
        val secondPos = result.indexOf("Second")
        val thirdPos = result.indexOf("Third")
        assertTrue(firstPos < secondPos && secondPos < thirdPos,
            "Messages should be in chronological order")
    }

    // =========================================================================
    // 4. Output session tagging
    // =========================================================================

    @Test
    fun `format should tag output session in header`() {
        val sessions = listOf(
            snapshot("Private", "p1", "session.private",
                listOf(msg("model", "Thinking...", "a1", "Bot", 1000L)),
                isOutput = true)
        )

        val result = ConversationLogFormatter.format(sessions, platform)

        assertTrue(result.contains("output: true"),
            "Output session should be tagged in header")
    }

    @Test
    fun `format should not tag non-output sessions`() {
        val sessions = listOf(
            snapshot("Chat", "s1", "session.chat",
                listOf(msg("user", "Hi", "u1", "Alice", 1000L)),
                isOutput = false)
        )

        val result = ConversationLogFormatter.format(sessions, platform)

        assertFalse(result.contains("output:"),
            "Non-output sessions should not have output tag")
    }

    @Test
    fun `format should correctly tag mixed output and non-output sessions`() {
        val sessions = listOf(
            snapshot("Chat", "s1", "session.chat",
                listOf(msg("user", "Hi", "u1", "Alice", 1000L)),
                isOutput = false),
            snapshot("Private", "p1", "session.private",
                listOf(msg("model", "Inner thoughts", "a1", "Bot", 2000L)),
                isOutput = true)
        )

        val result = ConversationLogFormatter.format(sessions, platform)

        val chatHeader = result.lines().find { it.contains("SESSION: Chat") }!!
        val privateHeader = result.lines().find { it.contains("SESSION: Private") }!!

        assertFalse(chatHeader.contains("output:"), "Public session should not have output tag")
        assertTrue(privateHeader.contains("output: true"), "Private session should have output tag")
    }

    // =========================================================================
    // 5. Empty session handling
    // =========================================================================

    @Test
    fun `format with all sessions empty should show no-messages notice`() {
        val sessions = listOf(
            snapshot("Chat", "s1", "session.chat", emptyList()),
            snapshot("General", "s2", "session.general", emptyList())
        )

        val result = ConversationLogFormatter.format(sessions, platform)

        assertTrue(result.contains("No messages in any subscribed session"))
    }

    @Test
    fun `format with empty session list should show no-messages notice`() {
        val result = ConversationLogFormatter.format(emptyList(), platform)

        assertTrue(result.contains("No messages in any subscribed session"))
        assertTrue(result.contains("--- END OF CONVERSATION LOG ---"))
    }

    @Test
    fun `format with one empty session and one with messages should show no messages for empty`() {
        val sessions = listOf(
            snapshot("Chat", "s1", "session.chat",
                listOf(msg("user", "Hello", "u1", "Alice", 1000L))),
            snapshot("Empty Room", "s2", "session.empty", emptyList())
        )

        val result = ConversationLogFormatter.format(sessions, platform)

        assertTrue(result.contains("(no messages)"))
        assertTrue(result.contains("Hello"))
    }

    @Test
    fun `format with single empty session should show no-messages notice`() {
        val sessions = listOf(
            snapshot("Chat", "s1", "session.chat", emptyList())
        )

        val result = ConversationLogFormatter.format(sessions, platform)

        assertTrue(result.contains("No messages in any subscribed session"))
    }

    // =========================================================================
    // 6. extractParticipants
    // =========================================================================

    @Test
    fun `extractParticipants should return unique participants across sessions`() {
        val sessions = listOf(
            snapshot("Chat", "s1", "session.chat", listOf(
                msg("user", "Hello", "user.alice", "Alice", 1000L),
                msg("model", "Hi!", "agent.bot", "Bot", 2000L)
            )),
            snapshot("General", "s2", "session.general", listOf(
                msg("user", "Hey", "user.alice", "Alice", 3000L),
                msg("user", "World", "user.bob", "Bob", 4000L)
            ))
        )

        val participants = ConversationLogFormatter.extractParticipants(sessions)

        assertEquals(3, participants.size, "Should deduplicate Alice across sessions")
        assertTrue(participants.contains("user.alice" to "Alice"))
        assertTrue(participants.contains("agent.bot" to "Bot"))
        assertTrue(participants.contains("user.bob" to "Bob"))
    }

    @Test
    fun `extractParticipants with empty sessions should return empty list`() {
        val participants = ConversationLogFormatter.extractParticipants(emptyList())
        assertTrue(participants.isEmpty())
    }

    @Test
    fun `extractParticipants with single participant should return one entry`() {
        val sessions = listOf(
            snapshot("Chat", "s1", "session.chat", listOf(
                msg("user", "A", "user.alice", "Alice", 1000L),
                msg("user", "B", "user.alice", "Alice", 2000L)
            ))
        )

        val participants = ConversationLogFormatter.extractParticipants(sessions)

        assertEquals(1, participants.size)
        assertEquals("user.alice" to "Alice", participants.first())
    }

    // =========================================================================
    // 7. Content with special characters
    // =========================================================================

    @Test
    fun `format should handle message content containing markdown hr delimiter`() {
        val sessions = listOf(
            snapshot("Chat", "s1", "session.chat",
                listOf(msg("user", "Here is a divider:\n---\nEnd.", "u1", "Alice", 1000L)))
        )

        val result = ConversationLogFormatter.format(sessions, platform)

        assertTrue(result.contains("Here is a divider:"))
        assertTrue(result.contains("End."))
    }

    @Test
    fun `format should handle empty message content`() {
        val sessions = listOf(
            snapshot("Chat", "s1", "session.chat",
                listOf(msg("user", "", "u1", "Alice", 1000L)))
        )

        val result = ConversationLogFormatter.format(sessions, platform)

        assertTrue(result.contains("Alice (u1)"))
    }

    // =========================================================================
    // 8. Message counts
    // =========================================================================

    @Test
    fun `format should show correct message count per session`() {
        val sessions = listOf(
            snapshot("Chat", "s1", "session.chat", listOf(
                msg("user", "A", "u1", "Alice", 1000L),
                msg("user", "B", "u1", "Alice", 2000L),
                msg("model", "C", "a1", "Bot", 3000L)
            ))
        )

        val result = ConversationLogFormatter.format(sessions, platform)

        assertTrue(result.contains("3 messages"))
    }

    @Test
    fun `format should show 0 messages for empty individual session when mixed`() {
        val sessions = listOf(
            snapshot("Active", "s1", "session.active",
                listOf(msg("user", "Hi", "u1", "Alice", 1000L))),
            snapshot("Empty", "s2", "session.empty", emptyList())
        )

        val result = ConversationLogFormatter.format(sessions, platform)

        val emptyHeader = result.lines().find { it.contains("SESSION: Empty") }
        assertTrue(emptyHeader != null && emptyHeader.contains("0 messages"))
    }
}