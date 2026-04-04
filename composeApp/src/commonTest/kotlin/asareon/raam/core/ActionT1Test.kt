package asareon.raam.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Tier 1 Unit Tests for the Action data class.
 *
 * Mandate (P-TEST-001, T1): To test Action's toString() formatting,
 * payload truncation, and copy semantics for originator stamping.
 */
class ActionT1Test {

    // ========================================================================
    // toString() FORMATTING
    // ========================================================================

    @Test
    fun `toString with name only`() {
        val action = Action(name = "test.SIMPLE")
        assertEquals("<'test.SIMPLE'>", action.toString())
    }

    @Test
    fun `toString with originator`() {
        val action = Action(name = "test.SIMPLE", originator = "session.chat1")
        assertEquals("<'test.SIMPLE' from 'session.chat1'>", action.toString())
    }

    @Test
    fun `toString with targetRecipient`() {
        val action = Action(name = "test.RESPONSE", targetRecipient = "beta")
        assertEquals("<'test.RESPONSE' → 'beta'>", action.toString())
    }

    @Test
    fun `toString with originator and targetRecipient`() {
        val action = Action(name = "alpha.RESPONSE", originator = "alpha", targetRecipient = "beta")
        assertEquals("<'alpha.RESPONSE' from 'alpha' → 'beta'>", action.toString())
    }

    @Test
    fun `toString with short payload`() {
        val payload = buildJsonObject { put("key", "value") }
        val action = Action(name = "test.WITH_PAYLOAD", payload = payload)
        val result = action.toString()
        assertTrue(result.startsWith("<'test.WITH_PAYLOAD'"), "Should start with action name.")
        assertTrue(result.contains("with '{\"key\":\"value\"}'"), "Should include the full short payload.")
    }

    @Test
    fun `toString truncates payload longer than 130 characters`() {
        val longValue = "x".repeat(200)
        val payload = buildJsonObject { put("data", longValue) }
        val action = Action(name = "test.LONG_PAYLOAD", payload = payload)
        val result = action.toString()

        assertTrue(result.contains("...'"), "Long payload should be truncated with ellipsis.")
        // The raw payload string is > 130 chars, so it should be cut
        assertFalse(result.contains(longValue), "Full long value should not appear in truncated toString.")
    }

    @Test
    fun `toString with all fields`() {
        val payload = buildJsonObject { put("msg", "hello") }
        val action = Action(
            name = "alpha.RESPONSE",
            payload = payload,
            originator = "alpha.agent-1",
            targetRecipient = "beta.session-2"
        )
        val result = action.toString()
        assertTrue(result.contains("from 'alpha.agent-1'"))
        assertTrue(result.contains("→ 'beta.session-2'"))
        assertTrue(result.contains("with "))
    }

    // ========================================================================
    // COPY / ORIGINATOR STAMPING
    // ========================================================================

    @Test
    fun `copy with originator stamps the action correctly`() {
        val original = Action(name = "test.CMD", payload = buildJsonObject { put("x", 1) })
        val stamped = original.copy(originator = "session.chat1")

        assertEquals("session.chat1", stamped.originator)
        assertEquals(original.name, stamped.name)
        assertEquals(original.payload, stamped.payload)
        assertNull(original.originator, "Original should be unmodified.")
    }

    @Test
    fun `copy preserves targetRecipient`() {
        val original = Action(name = "test.RESP", targetRecipient = "beta")
        val stamped = original.copy(originator = "alpha")

        assertEquals("alpha", stamped.originator)
        assertEquals("beta", stamped.targetRecipient)
    }

    // ========================================================================
    // DEFAULT VALUES
    // ========================================================================

    @Test
    fun `action defaults have null optional fields`() {
        val action = Action(name = "test.MINIMAL")
        assertNull(action.payload)
        assertNull(action.originator)
        assertNull(action.targetRecipient)
    }
}