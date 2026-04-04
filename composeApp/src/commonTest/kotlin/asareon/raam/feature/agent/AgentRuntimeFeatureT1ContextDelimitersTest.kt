package app.auf.feature.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [ContextDelimiters] — token rounding, delimiter formatting,
 * and system prompt wrapping.
 */
class ContextDelimitersT1Test {

    // =========================================================================
    // roundTokensUp — 2 significant figures, rounded UP
    // =========================================================================

    @Test
    fun `roundTokensUp - zero returns zero`() {
        assertEquals(0, ContextDelimiters.roundTokensUp(0))
    }

    @Test
    fun `roundTokensUp - single digit values round to nearest 10`() {
        assertEquals(10, ContextDelimiters.roundTokensUp(1))
        assertEquals(10, ContextDelimiters.roundTokensUp(8))
        assertEquals(10, ContextDelimiters.roundTokensUp(9))
    }

    @Test
    fun `roundTokensUp - 2-digit values round to nearest 10`() {
        assertEquals(10, ContextDelimiters.roundTokensUp(10))
        assertEquals(20, ContextDelimiters.roundTokensUp(12))
        assertEquals(60, ContextDelimiters.roundTokensUp(51))
        assertEquals(100, ContextDelimiters.roundTokensUp(99))
    }

    @Test
    fun `roundTokensUp - 3-digit values rounded to nearest 10`() {
        assertEquals(100, ContextDelimiters.roundTokensUp(100))
        assertEquals(560, ContextDelimiters.roundTokensUp(551))
        assertEquals(560, ContextDelimiters.roundTokensUp(560))
        assertEquals(1000, ContextDelimiters.roundTokensUp(999))
    }

    @Test
    fun `roundTokensUp - 4-digit values rounded to nearest 100`() {
        assertEquals(1000, ContextDelimiters.roundTokensUp(1000))
        assertEquals(2400, ContextDelimiters.roundTokensUp(2389))
        assertEquals(5000, ContextDelimiters.roundTokensUp(5000))
        assertEquals(10000, ContextDelimiters.roundTokensUp(9999))
    }

    @Test
    fun `roundTokensUp - 5-digit values rounded to nearest 1000`() {
        assertEquals(10000, ContextDelimiters.roundTokensUp(10000))
        assertEquals(23000, ContextDelimiters.roundTokensUp(22476))
        assertEquals(13000, ContextDelimiters.roundTokensUp(12500))
    }

    // =========================================================================
    // approxTokens — chars to rounded token display string
    // =========================================================================

    @Test
    fun `approxTokens - converts chars to rounded tokens with commas`() {
        // 9556 chars → 2389 tokens → 2400
        assertEquals("2,400", ContextDelimiters.approxTokens(9556))
        // 2204 chars → 551 tokens → 560
        assertEquals("560", ContextDelimiters.approxTokens(2204))
        // 89904 chars → 22476 tokens → 23000
        assertEquals("23,000", ContextDelimiters.approxTokens(89904))
    }

    @Test
    fun `approxTokens - small char counts round to nearest 10`() {
        // 20 chars → 5 tokens → 10
        assertEquals("10", ContextDelimiters.approxTokens(20))
        // 0 chars → 0 tokens
        assertEquals("0", ContextDelimiters.approxTokens(0))
    }

    // =========================================================================
    // formatWithCommas
    // =========================================================================

    @Test
    fun `formatWithCommas - formats numbers correctly`() {
        assertEquals("0", ContextDelimiters.formatWithCommas(0))
        assertEquals("100", ContextDelimiters.formatWithCommas(100))
        assertEquals("1,000", ContextDelimiters.formatWithCommas(1000))
        assertEquals("12,500", ContextDelimiters.formatWithCommas(12500))
        assertEquals("1,000,000", ContextDelimiters.formatWithCommas(1000000))
    }

    // =========================================================================
    // h1 — top-level partition headers
    // =========================================================================

    @Test
    fun `h1 - includes name, tokens, and state`() {
        val header = ContextDelimiters.h1("CONVERSATION_LOG", 10000, ContextDelimiters.EXPANDED)
        assertTrue(header.contains("- [ CONVERSATION_LOG ]"))
        assertTrue(header.contains("(~2,500 tokens)"))
        assertTrue(header.contains("[EXPANDED]"))
        assertTrue(header.contains(" -"))
    }

    @Test
    fun `h1 - omits tokens and state when null`() {
        val header = ContextDelimiters.h1("NAME")
        assertEquals("\n\n- [ NAME ] -\n", header)
    }

    // =========================================================================
    // h2 — sub-partition headers
    // =========================================================================

    @Test
    fun `h2 - includes text, tokens, and state`() {
        val header = ContextDelimiters.h2("SESSION: Chat | uuid: abc | 5 messages", 6400, ContextDelimiters.EXPANDED)
        assertTrue(header.contains("--- SESSION: Chat | uuid: abc | 5 messages"))
        assertTrue(header.contains("(~1,600 tokens)"))
        assertTrue(header.contains("[EXPANDED]"))
        assertTrue(header.contains("---"))
    }

    @Test
    fun `h2End - closing tag format`() {
        val close = ContextDelimiters.h2End("SESSION")
        assertEquals("\n\n--- END OF SESSION ---\n\n", close)
    }

    // =========================================================================
    // h3/h4 — indented headers
    // =========================================================================


    @Test
    fun `h4 - 4-space indent`() {
        val header = ContextDelimiters.h4("Sub-entry detail")
        assertTrue(header.contains("    --- Sub-entry detail"))
    }

    // =========================================================================
    // wrapSystemPrompt
    // =========================================================================

    @Test
    fun `wrapSystemPrompt - wraps content with outer delimiters`() {
        val wrapped = ContextDelimiters.wrapSystemPrompt("Hello World")
        assertTrue(wrapped.contains("[[[ - SYSTEM PROMPT - ]]]"))
        assertTrue(wrapped.contains("Hello World"))
        assertTrue(wrapped.contains("[[[ - END OF SYSTEM PROMPT - ]]]"))
    }

    // =========================================================================
    // State badge constants
    // =========================================================================

    @Test
    fun `state badge constants are correct`() {
        assertEquals("PROTECTED", ContextDelimiters.PROTECTED)
        assertEquals("EXPANDED", ContextDelimiters.EXPANDED)
        assertEquals("COLLAPSED", ContextDelimiters.COLLAPSED)
        assertEquals("TRUNCATED", ContextDelimiters.TRUNCATED)
    }
}