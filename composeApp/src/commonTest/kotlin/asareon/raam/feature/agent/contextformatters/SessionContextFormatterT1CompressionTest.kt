package asareon.raam.feature.agent.contextformatters

import asareon.raam.fakes.FakePlatformDependencies
import asareon.raam.feature.agent.CompressionConfig
import asareon.raam.feature.agent.GatewayMessage
import asareon.raam.feature.agent.PromptSection
import asareon.raam.feature.agent.SessionInfo
import asareon.raam.feature.agent.SessionParticipant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tier 1 Compression Tests for SessionContextFormatter.
 *
 * Verifies that CompressionConfig strategies correctly transform session output:
 * - Strategy 3 (Terse System Text): shorter headers and routing text
 * - Strategy 5 (Abbreviations): word substitution in headers
 * - Strategy 6 (Slim Message Headers): compact message delimiters
 * - Combined strategies produce shortest output
 * - Backward compatibility: default config preserves existing format
 */
class SessionContextFormatterT1CompressionTest {

    private val platform = FakePlatformDependencies("test")

    // =========================================================================
    // Helpers (same pattern as SessionContextFormatterT1Test)
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

    private fun buildAndRender(
        sessions: List<SessionContextFormatter.SessionLedgerSnapshot>,
        isPrivateFormat: Boolean = false,
        compressionConfig: CompressionConfig = CompressionConfig()
    ): String {
        val sessionInfos = sessionInfosFor(sessions)
        val group = SessionContextFormatter.buildSessionsGroup(
            sessions = sessions,
            sessionInfos = sessionInfos,
            isPrivateFormat = isPrivateFormat,
            platformDependencies = platform,
            compressionConfig = compressionConfig
        )
        return group.renderFlat()
    }

    // =========================================================================
    // Shared test data
    // =========================================================================

    private val testSessions = listOf(
        snapshot("Chat", "s1", "session.chat", listOf(
            msg("user", "Hello world", "user.alice", "Alice", 1000L),
            msg("model", "Hi there!", "agent.bot", "Bot", 2000L),
            msg("user", "How are you?", "user.alice", "Alice", 3000L)
        ), isOutput = true)
    )

    private val multiSessions = listOf(
        snapshot("Chat", "s1", "session.chat", listOf(
            msg("user", "Hello", "user.alice", "Alice", 1000L),
            msg("model", "Hi", "agent.bot", "Bot", 2000L)
        ), isOutput = true),
        snapshot("Debug", "s2", "session.debug", listOf(
            msg("user", "Debug info", "user.bob", "Bob", 3000L)
        ))
    )

    // =========================================================================
    // Terse System Text (Strategy 3)
    // =========================================================================

    @Test
    fun `terse header is shorter than verbose header`() {
        val verbose = buildAndRender(testSessions)
        val terse = buildAndRender(testSessions, compressionConfig = CompressionConfig(terseSystemText = true))

        assertTrue(terse.length < verbose.length,
            "Terse header (${ terse.length } chars) should be shorter than verbose (${verbose.length} chars)")
    }

    @Test
    fun `terse header contains compact participant format`() {
        val terse = buildAndRender(testSessions, compressionConfig = CompressionConfig(terseSystemText = true))

        // Terse uses pipe-separated inline format
        assertTrue(terse.contains("|"), "Terse header should use | separator for participants")
        assertTrue(terse.contains("msg"), "Terse header should use 'msg' shorthand for message count")
    }

    @Test
    fun `terse routing uses short form`() {
        val terse = buildAndRender(
            testSessions,
            isPrivateFormat = true,
            compressionConfig = CompressionConfig(terseSystemText = true)
        )

        assertTrue(terse.contains("\u2192"),
            "Terse routing should use arrow symbol")
    }

    @Test
    fun `terse message format instruction is short`() {
        val terse = buildAndRender(testSessions, compressionConfig = CompressionConfig(terseSystemText = true))

        assertTrue(terse.contains("Msg headers auto-added"),
            "Terse format should contain compact message format instruction")
    }

    @Test
    fun `verbose header contains full instructions`() {
        val verbose = buildAndRender(testSessions)

        assertTrue(verbose.contains("You are participating in"),
            "Verbose header should contain full participation text")
        assertTrue(verbose.contains("Each message in the conversation is wrapped with sender headers"),
            "Verbose header should contain full message format instructions")
    }

    // =========================================================================
    // Slim Message Headers (Strategy 6)
    // =========================================================================

    @Test
    fun `slim headers use bracket format`() {
        val config = CompressionConfig(slimMessageHeaders = true)
        val session = testSessions.first()
        val content = SessionContextFormatter.buildSessionContent(session, platform, config)

        assertTrue(content.contains("[Alice|user.alice|"),
            "Slim headers should use [Name|id| format, got:\n$content")
    }

    @Test
    fun `slim headers drop closing delimiter`() {
        val config = CompressionConfig(slimMessageHeaders = true)
        val session = testSessions.first()
        val content = SessionContextFormatter.buildSessionContent(session, platform, config)

        // Standard format uses h3End() which produces "  ---" closing delimiter
        // Slim should not have standalone closing delimiters
        val lines = content.lines()
        val closingDelimiters = lines.filter { it.trim() == "---" }
        assertTrue(closingDelimiters.isEmpty(),
            "Slim headers should not have standalone '---' closing delimiters")
    }

    @Test
    fun `slim headers shorten timestamp`() {
        val config = CompressionConfig(slimMessageHeaders = true)
        val session = testSessions.first()
        val content = SessionContextFormatter.buildSessionContent(session, platform, config)

        // The platform formats timestamp 1000L as "2026-01-01T00:00:01Z"
        // Slim should shorten to MM-DD HH:MM format
        assertFalse(content.contains("2026-01-01T"),
            "Slim headers should not contain full ISO timestamp")
        assertTrue(content.contains("01-01 00:00"),
            "Slim headers should contain shortened MM-DD HH:MM timestamp")
    }

    @Test
    fun `default config uses standard delimiters`() {
        val session = testSessions.first()
        val content = SessionContextFormatter.buildSessionContent(session, platform)

        assertTrue(content.contains("---"),
            "Default config should use standard --- delimiters")
        assertTrue(content.contains("Alice (user.alice)"),
            "Default config should use Name (id) format")
    }

    // =========================================================================
    // Word Abbreviations (Strategy 5)
    // =========================================================================

    @Test
    fun `abbreviations applied to header text`() {
        // TerseText.abbreviate replaces "configuration" -> "config" etc.
        // The verbose header text contains words like "automatically"
        val verbose = buildAndRender(testSessions)
        val abbreviated = buildAndRender(testSessions, compressionConfig = CompressionConfig(abbreviations = true))

        // The verbose text contains "automatically" which should become "auto"
        if (verbose.contains("automatically")) {
            assertTrue(abbreviated.contains("auto"),
                "Abbreviations should replace 'automatically' with 'auto'")
            assertFalse(abbreviated.contains("automatically"),
                "Abbreviations should remove full word 'automatically'")
        }
    }

    // =========================================================================
    // Combined Strategies
    // =========================================================================

    @Test
    fun `all strategies enabled produces shortest output`() {
        val defaultOutput = buildAndRender(testSessions)
        val terseOnly = buildAndRender(testSessions,
            compressionConfig = CompressionConfig(terseSystemText = true))
        val slimOnly = buildAndRender(testSessions,
            compressionConfig = CompressionConfig(slimMessageHeaders = true))
        val allEnabled = buildAndRender(testSessions,
            compressionConfig = CompressionConfig(
                terseSystemText = true,
                slimMessageHeaders = true,
                abbreviations = true
            ))

        assertTrue(allEnabled.length < defaultOutput.length,
            "All strategies combined (${allEnabled.length}) should be shorter than default (${defaultOutput.length})")
        assertTrue(allEnabled.length < terseOnly.length,
            "All strategies combined (${allEnabled.length}) should be shorter than terse-only (${terseOnly.length})")
        assertTrue(allEnabled.length < slimOnly.length,
            "All strategies combined (${allEnabled.length}) should be shorter than slim-only (${slimOnly.length})")
    }

    @Test
    fun `default config preserves existing output format`() {
        val output = buildAndRender(testSessions)

        assertTrue(output.contains("You are participating in"),
            "Default config should preserve verbose participation text")
        assertTrue(output.contains("---"),
            "Default config should preserve standard delimiters")
        assertTrue(output.contains("Subscribed sessions:"),
            "Default config should preserve session subscription list")
    }
}
