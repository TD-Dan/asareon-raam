package asareon.raam.feature.session

import asareon.raam.fakes.FakePlatformDependencies
import kotlin.test.*

/**
 * Tier 1 Unit Test for MessageResolution.
 *
 * Mandate (P-TEST-001, T1): To test the pure resolution logic in complete isolation.
 * MessageResolution is agent-facing diagnostic infrastructure — incorrect error messages
 * directly degrade agent self-correction, making this a high-priority coverage target.
 *
 * Strategy: We use the FakePlatformDependencies' own formatIsoTimestamp/parseIsoTimestamp
 * cycle to construct test fixtures, ensuring tests are self-consistent regardless of
 * the fake's internal date format.
 */
class SessionFeatureT1MessageResolutionTest {

    private val platform = FakePlatformDependencies("test")

    // --- Test Fixtures ---

    /** Creates a ledger entry with the given senderId and timestamp (epoch millis). */
    private fun entry(id: String, senderId: String, timestamp: Long, rawContent: String = "msg"): LedgerEntry =
        LedgerEntry(id = id, timestamp = timestamp, senderId = senderId, rawContent = rawContent)

    /** Converts epoch millis to ISO via the platform, round-tripping through the same logic the production code uses. */
    private fun isoOf(millis: Long): String = platform.formatIsoTimestamp(millis)

    // ================================================================
    // 1. Exact match — happy path
    // ================================================================

    @Test
    fun `exact match by senderId and ISO timestamp returns the entry`() {
        val ledger = listOf(
            entry("m1", "agent-1", 1000L),
            entry("m2", "user", 2000L),
            entry("m3", "agent-1", 3000L)
        )

        val result = MessageResolution.resolve(ledger, "agent-1", isoOf(3000L), platform)

        assertNotNull(result.entry)
        assertEquals("m3", result.entry!!.id)
        assertNull(result.errorMessage)
    }

    @Test
    fun `exact match returns null error message`() {
        val ledger = listOf(entry("m1", "user", 5000L))

        val result = MessageResolution.resolve(ledger, "user", isoOf(5000L), platform)

        assertNotNull(result.entry)
        assertNull(result.errorMessage)
    }

    // ================================================================
    // 2. Raw epoch millis fallback
    // ================================================================

    @Test
    fun `raw epoch millis with matching entry returns the entry despite wrong format`() {
        val ledger = listOf(entry("m1", "agent-1", 1000L))

        // Agent sends "1000" instead of ISO format — but it matches an entry
        val result = MessageResolution.resolve(ledger, "agent-1", "1000", platform)

        assertNotNull(result.entry, "Should find the entry via raw epoch fallback")
        assertEquals("m1", result.entry!!.id)
        assertNull(result.errorMessage)
    }

    @Test
    fun `raw epoch millis with no matching entry returns error with ISO hint`() {
        val ledger = listOf(entry("m1", "agent-1", 1000L))

        // Agent sends "9999" — looks like epoch but matches nothing
        val result = MessageResolution.resolve(ledger, "agent-1", "9999", platform)

        assertNull(result.entry)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("raw epoch"), "Should hint that it looks like a raw epoch value")
        assertTrue(result.errorMessage!!.contains("ISO 8601"), "Should suggest ISO 8601 format")
        // The hint should include the formatted version of the raw value
        assertTrue(result.errorMessage!!.contains(isoOf(9999L)), "Should include the ISO equivalent as a hint")
    }

    // ================================================================
    // 3. Completely unparseable timestamp
    // ================================================================

    @Test
    fun `unparseable non-numeric timestamp returns parse error`() {
        val ledger = listOf(entry("m1", "user", 1000L))

        val result = MessageResolution.resolve(ledger, "user", "not-a-timestamp", platform)

        assertNull(result.entry)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("Could not parse timestamp"))
        assertTrue(result.errorMessage!!.contains("not-a-timestamp"))
        assertTrue(result.errorMessage!!.contains("ISO 8601"))
    }

    @Test
    fun `empty timestamp string returns parse error`() {
        val ledger = listOf(entry("m1", "user", 1000L))

        val result = MessageResolution.resolve(ledger, "user", "", platform)

        assertNull(result.entry)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("Could not parse timestamp"))
    }

    // ================================================================
    // 4. Right sender, wrong timestamp — suggests closest
    // ================================================================

    @Test
    fun `correct sender but wrong timestamp suggests closest message`() {
        val ledger = listOf(
            entry("m1", "agent-1", 1000L),
            entry("m2", "agent-1", 5000L),
            entry("m3", "agent-1", 9000L)
        )

        // Agent sends a valid ISO timestamp that doesn't match any entry
        val result = MessageResolution.resolve(ledger, "agent-1", isoOf(4800L), platform)

        assertNull(result.entry)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("Message not found"))
        // Should mention the closest timestamp (5000L is closest to 4800L)
        assertTrue(result.errorMessage!!.contains(isoOf(5000L)),
            "Should suggest the closest timestamp. Error: ${result.errorMessage}")
        assertTrue(result.errorMessage!!.contains("3 message(s) from 'agent-1'"),
            "Should report the count of messages from this sender")
    }

    // ================================================================
    // 5. Right timestamp, wrong sender — reveals actual sender
    // ================================================================

    @Test
    fun `correct timestamp but wrong sender reveals actual sender`() {
        val ledger = listOf(
            entry("m1", "agent-1", 1000L),
            entry("m2", "user", 2000L)
        )

        // Agent provides correct timestamp for m1 but wrong senderId
        val result = MessageResolution.resolve(ledger, "wrong-sender", isoOf(1000L), platform)

        assertNull(result.entry)
        assertNotNull(result.errorMessage)
        // Should reveal who actually sent the message at that timestamp
        assertTrue(result.errorMessage!!.contains("agent-1"),
            "Should reveal the actual sender at that timestamp. Error: ${result.errorMessage}")
    }

    // ================================================================
    // 6. Unknown sender — lists known senders
    // ================================================================

    @Test
    fun `completely unknown sender lists all known senders`() {
        val ledger = listOf(
            entry("m1", "agent-1", 1000L),
            entry("m2", "user", 2000L),
            entry("m3", "agent-2", 3000L)
        )

        val result = MessageResolution.resolve(ledger, "nonexistent", isoOf(1000L), platform)

        assertNull(result.entry)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("No messages found from 'nonexistent'"),
            "Should say no messages from this sender")
        assertTrue(result.errorMessage!!.contains("agent-1"),
            "Should list known senders. Error: ${result.errorMessage}")
        assertTrue(result.errorMessage!!.contains("user"),
            "Should list known senders. Error: ${result.errorMessage}")
        assertTrue(result.errorMessage!!.contains("agent-2"),
            "Should list known senders. Error: ${result.errorMessage}")
    }

    // ================================================================
    // 7. Empty ledger
    // ================================================================

    @Test
    fun `empty ledger returns not found with unknown sender diagnostic`() {
        val ledger = emptyList<LedgerEntry>()

        val result = MessageResolution.resolve(ledger, "agent-1", isoOf(1000L), platform)

        assertNull(result.entry)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("Message not found"))
    }

    // ================================================================
    // 8. Multiple messages at the same timestamp
    // ================================================================

    @Test
    fun `multiple messages at same timestamp from different senders are distinguished`() {
        val ledger = listOf(
            entry("m1", "agent-1", 1000L),
            entry("m2", "agent-2", 1000L)
        )

        // Exact match for agent-1 at 1000
        val result1 = MessageResolution.resolve(ledger, "agent-1", isoOf(1000L), platform)
        assertNotNull(result1.entry)
        assertEquals("m1", result1.entry!!.id)

        // Exact match for agent-2 at 1000
        val result2 = MessageResolution.resolve(ledger, "agent-2", isoOf(1000L), platform)
        assertNotNull(result2.entry)
        assertEquals("m2", result2.entry!!.id)
    }

    // ================================================================
    // 9. Combined diagnostics — wrong sender AND wrong timestamp
    // ================================================================

    @Test
    fun `wrong sender with no messages and no timestamp match gives combined diagnostics`() {
        val ledger = listOf(
            entry("m1", "user", 1000L),
            entry("m2", "user", 2000L)
        )

        // Unknown sender AND a timestamp that doesn't match any entry
        val result = MessageResolution.resolve(ledger, "ghost", isoOf(9999L), platform)

        assertNull(result.entry)
        assertNotNull(result.errorMessage)
        // Should mention unknown sender and list known senders
        assertTrue(result.errorMessage!!.contains("No messages found from 'ghost'"))
        assertTrue(result.errorMessage!!.contains("user"))
    }
}