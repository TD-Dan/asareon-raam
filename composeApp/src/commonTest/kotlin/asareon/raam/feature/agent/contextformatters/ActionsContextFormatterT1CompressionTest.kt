package asareon.raam.feature.agent.contextformatters

import asareon.raam.feature.agent.CompressionConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tier 1 Compression Tests for ActionsContextFormatter.
 *
 * ActionsContextFormatter.buildSections() requires a real Store with ActionRegistry
 * and Identity for permission resolution, making full integration testing a T2 concern.
 *
 * These tests verify the compression behavior accessible without full infrastructure:
 * - TerseText preamble selection (terse vs verbose)
 * - Abbreviation application to preamble text
 * - Content length comparisons between strategies
 *
 * Full action formatting with Store/Identity is deferred to T2 integration tests.
 */
class ActionsContextFormatterT1CompressionTest {

    // =========================================================================
    // TerseText ACTIONS_PREAMBLE (Strategy 4 / Strategy 3 overlap)
    // =========================================================================

    @Test
    fun `terse ACTIONS_PREAMBLE is shorter than verbose`() {
        val verbose = TerseText.get("ACTIONS_PREAMBLE", isTerse = false)
        val terse = TerseText.get("ACTIONS_PREAMBLE", isTerse = true)

        assertTrue(verbose.isNotBlank(), "Verbose preamble should not be blank")
        assertTrue(terse.isNotBlank(), "Terse preamble should not be blank")
        assertTrue(terse.length < verbose.length,
            "Terse preamble (${terse.length} chars) should be shorter than verbose (${verbose.length} chars)")
    }

    @Test
    fun `terse ACTIONS_PREAMBLE contains key instruction elements`() {
        val terse = TerseText.get("ACTIONS_PREAMBLE", isTerse = true)

        assertTrue(terse.contains("SYSTEM ACTIONS"),
            "Terse preamble should contain 'SYSTEM ACTIONS' heading")
        assertTrue(terse.contains("raam_"),
            "Terse preamble should reference raam_ code block syntax")
        assertTrue(terse.contains("JSON"),
            "Terse preamble should mention JSON payload format")
    }

    @Test
    fun `verbose ACTIONS_PREAMBLE contains full heading`() {
        val verbose = TerseText.get("ACTIONS_PREAMBLE", isTerse = false)

        assertTrue(verbose.contains("AVAILABLE SYSTEM ACTIONS"),
            "Verbose preamble should contain full 'AVAILABLE SYSTEM ACTIONS' heading")
    }

    @Test
    fun `terse preamble uses compact heading`() {
        val terse = TerseText.get("ACTIONS_PREAMBLE", isTerse = true)

        assertTrue(terse.contains("--- SYSTEM ACTIONS ---"),
            "Terse preamble should use compact '--- SYSTEM ACTIONS ---' heading")
        assertFalse(terse.contains("AVAILABLE SYSTEM ACTIONS"),
            "Terse preamble should not contain the verbose heading")
    }

    // =========================================================================
    // Abbreviations applied to preamble (Strategy 5)
    // =========================================================================

    @Test
    fun `abbreviations shorten verbose preamble`() {
        val verbose = TerseText.get("ACTIONS_PREAMBLE", isTerse = false)
        val abbreviated = TerseText.abbreviate(verbose)

        // The verbose preamble does not necessarily contain abbreviation-target words,
        // but abbreviate should at minimum return the same or shorter text
        assertTrue(abbreviated.length <= verbose.length,
            "Abbreviated text should not be longer than the original")
    }

    @Test
    fun `abbreviations shorten terse preamble`() {
        val terse = TerseText.get("ACTIONS_PREAMBLE", isTerse = true)
        val abbreviated = TerseText.abbreviate(terse)

        assertTrue(abbreviated.length <= terse.length,
            "Abbreviated terse text should not be longer than the terse original")
    }

    // =========================================================================
    // CompressionConfig flag routing
    // =========================================================================

    @Test
    fun `terseActions flag selects terse preamble`() {
        // Verify that the config flags correctly map to preamble selection
        val configTerseActions = CompressionConfig(terseActions = true)
        val configTerseSystem = CompressionConfig(terseSystemText = true)
        val configDefault = CompressionConfig()

        // Both terseActions and terseSystemText should trigger terse preamble
        // (as implemented in ActionsContextFormatter.buildSections)
        val tersePreamble = TerseText.get("ACTIONS_PREAMBLE", isTerse = true)
        val verbosePreamble = TerseText.get("ACTIONS_PREAMBLE", isTerse = false)

        // Verify the terse flag produces the correct preamble
        val resolvedTerse = TerseText.get(
            "ACTIONS_PREAMBLE",
            isTerse = configTerseActions.terseSystemText || configTerseActions.terseActions
        )
        val resolvedDefault = TerseText.get(
            "ACTIONS_PREAMBLE",
            isTerse = configDefault.terseSystemText || configDefault.terseActions
        )

        assertTrue(resolvedTerse == tersePreamble,
            "terseActions=true should select terse preamble")
        assertTrue(resolvedDefault == verbosePreamble,
            "Default config should select verbose preamble")
    }

    @Test
    fun `terseSystemText flag also selects terse preamble`() {
        val configTerseSystem = CompressionConfig(terseSystemText = true)

        val resolvedTerse = TerseText.get(
            "ACTIONS_PREAMBLE",
            isTerse = configTerseSystem.terseSystemText || configTerseSystem.terseActions
        )
        val tersePreamble = TerseText.get("ACTIONS_PREAMBLE", isTerse = true)

        assertTrue(resolvedTerse == tersePreamble,
            "terseSystemText=true should also select terse preamble")
    }

    // =========================================================================
    // Backward compatibility
    // =========================================================================

    @Test
    fun `default CompressionConfig selects verbose preamble`() {
        val config = CompressionConfig()
        val resolved = TerseText.get(
            "ACTIONS_PREAMBLE",
            isTerse = config.terseSystemText || config.terseActions
        )

        assertTrue(resolved.contains("AVAILABLE SYSTEM ACTIONS"),
            "Default config should produce verbose preamble with full heading")
        assertTrue(resolved.contains("IMPORTANT CONSTRAINTS"),
            "Default config should produce verbose preamble with constraints section")
    }

    @Test
    fun `verbose preamble contains example blocks`() {
        val verbose = TerseText.get("ACTIONS_PREAMBLE", isTerse = false)

        assertTrue(verbose.contains("EXAMPLE"),
            "Verbose preamble should contain example blocks")
        assertTrue(verbose.contains("filesystem.WRITE"),
            "Verbose preamble should contain WRITE example")
        assertTrue(verbose.contains("filesystem.LIST"),
            "Verbose preamble should contain LIST example")
    }

    @Test
    fun `terse preamble omits verbose examples`() {
        val terse = TerseText.get("ACTIONS_PREAMBLE", isTerse = true)

        assertFalse(terse.contains("EXAMPLE \u2014 Listing your workspace"),
            "Terse preamble should omit verbose example descriptions")
    }
}
