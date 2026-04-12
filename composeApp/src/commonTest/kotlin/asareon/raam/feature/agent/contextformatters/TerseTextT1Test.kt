package asareon.raam.feature.agent.contextformatters

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Tier 1 Unit Tests for TerseText.
 *
 * TerseText is a pure utility providing verbose/terse text pairs for
 * system-generated content, and a word abbreviation dictionary.
 *
 * Tests verify:
 * - get() returns correct verbose/terse text based on isTerse flag
 * - get() returns empty string for unknown keys
 * - abbreviate() replaces known words and preserves unknown words
 * - abbreviate() case sensitivity
 * - Key consistency between verbose and terse maps
 */
class TerseTextT1Test {

    // =========================================================================
    // Known keys for testing
    // =========================================================================

    private val knownKeys = listOf(
        "SESSION_ROUTING_PRIVATE",
        "SESSION_ROUTING_STANDARD",
        "SESSION_ROUTING_STANDARD_MULTI",
        "SESSION_MSG_FORMAT",
        "HKG_NAVIGATION",
        "ACTIONS_PREAMBLE",
    )

    // =========================================================================
    // get returns verbose when isTerse=false
    // =========================================================================

    @Test
    fun `get returns verbose text when isTerse is false`() {
        for (key in knownKeys) {
            val result = TerseText.get(key, isTerse = false)
            assertTrue(result.isNotEmpty(), "Verbose text for key '$key' should not be empty")
        }
    }

    @Test
    fun `get returns verbose SESSION_ROUTING_PRIVATE with full routing info`() {
        val result = TerseText.get("SESSION_ROUTING_PRIVATE", isTerse = false)
        assertTrue(result.contains("private session", ignoreCase = true),
            "Verbose SESSION_ROUTING_PRIVATE should mention private session")
    }

    // =========================================================================
    // get returns terse when isTerse=true
    // =========================================================================

    @Test
    fun `get returns terse text when isTerse is true`() {
        for (key in knownKeys) {
            val result = TerseText.get(key, isTerse = true)
            assertTrue(result.isNotEmpty(), "Terse text for key '$key' should not be empty")
        }
    }

    @Test
    fun `get returns terse SESSION_ROUTING_PRIVATE with compact routing info`() {
        val result = TerseText.get("SESSION_ROUTING_PRIVATE", isTerse = true)
        assertTrue(result.contains("PRIVATE"), "Terse should mention PRIVATE")
    }

    // =========================================================================
    // get returns empty string for unknown key
    // =========================================================================

    @Test
    fun `get returns empty string for unknown key when isTerse is false`() {
        val result = TerseText.get("NONEXISTENT_KEY_12345", isTerse = false)
        assertEquals("", result, "Unknown key should return empty string in verbose mode")
    }

    @Test
    fun `get returns empty string for unknown key when isTerse is true`() {
        val result = TerseText.get("NONEXISTENT_KEY_12345", isTerse = true)
        assertEquals("", result, "Unknown key should return empty string in terse mode")
    }

    // =========================================================================
    // Verbose text is longer than terse text for all keys
    // =========================================================================

    @Test
    fun `verbose text is longer than terse text for all keys`() {
        for (key in knownKeys) {
            val verbose = TerseText.get(key, isTerse = false)
            val terse = TerseText.get(key, isTerse = true)
            assertTrue(verbose.length > terse.length,
                "Verbose text for '$key' (${verbose.length} chars) should be longer than terse (${terse.length} chars)")
        }
    }

    // =========================================================================
    // abbreviate replaces known words
    // =========================================================================

    @Test
    fun `abbreviate replaces configuration with config`() {
        assertEquals("config file", TerseText.abbreviate("configuration file"))
    }

    @Test
    fun `abbreviate replaces authentication with auth`() {
        assertEquals("auth token", TerseText.abbreviate("authentication token"))
    }

    @Test
    fun `abbreviate replaces authorization with authz`() {
        assertEquals("authz rules", TerseText.abbreviate("authorization rules"))
    }

    @Test
    fun `abbreviate replaces description with desc`() {
        assertEquals("desc field", TerseText.abbreviate("description field"))
    }

    @Test
    fun `abbreviate replaces implementation with impl`() {
        assertEquals("impl details", TerseText.abbreviate("implementation details"))
    }

    @Test
    fun `abbreviate replaces information with info`() {
        assertEquals("info panel", TerseText.abbreviate("information panel"))
    }

    @Test
    fun `abbreviate replaces application with app`() {
        assertEquals("app server", TerseText.abbreviate("application server"))
    }

    @Test
    fun `abbreviate replaces multiple known words in one string`() {
        val input = "configuration and authentication for the application"
        val result = TerseText.abbreviate(input)
        assertEquals("config and auth for the app", result)
    }

    // =========================================================================
    // abbreviate preserves unknown words
    // =========================================================================

    @Test
    fun `abbreviate preserves unknown words unchanged`() {
        val input = "random unknown sentence with no abbreviations"
        assertEquals(input, TerseText.abbreviate(input))
    }

    // =========================================================================
    // abbreviate is case-sensitive
    // =========================================================================

    @Test
    fun `abbreviate does not replace capitalized words`() {
        val result = TerseText.abbreviate("Configuration file")
        // "Configuration" starts with capital C — should not be replaced
        // because replace is ignoreCase = false
        assertEquals("Configuration file", result,
            "Capitalized 'Configuration' should not be replaced (case-sensitive)")
    }

    // =========================================================================
    // abbreviate handles empty string
    // =========================================================================

    @Test
    fun `abbreviate returns empty string for empty input`() {
        assertEquals("", TerseText.abbreviate(""))
    }

    // =========================================================================
    // All verbose keys have matching terse keys
    // =========================================================================

    @Test
    fun `all known keys return non-empty text in both modes`() {
        for (key in knownKeys) {
            val verbose = TerseText.get(key, isTerse = false)
            val terse = TerseText.get(key, isTerse = true)
            assertTrue(verbose.isNotEmpty(), "Verbose text missing for key '$key'")
            assertTrue(terse.isNotEmpty(), "Terse text missing for key '$key'")
        }
    }
}
