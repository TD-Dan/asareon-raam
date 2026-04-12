package asareon.raam.feature.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tier 1 Unit Tests for CompressionConfig.
 *
 * CompressionConfig is a pure data class with companion factory/update methods
 * for managing token compression strategy toggles.
 *
 * Tests verify:
 * - fromSettings: all true, all false, mixed, missing keys, null values
 * - updateField: per-key updates and unknown key handling
 * - settingDefinitions completeness
 * - Default constructor values
 * - TOON/minify independence
 */
class CompressionConfigT1Test {

    // =========================================================================
    // fromSettings with all true
    // =========================================================================

    @Test
    fun `fromSettings with all true sets all flags`() {
        val values = mapOf(
            CompressionConfig.KEY_USE_TOON to "true",
            CompressionConfig.KEY_JSON_MINIFY to "true",
            CompressionConfig.KEY_TERSE_SYSTEM_TEXT to "true",
            CompressionConfig.KEY_TERSE_ACTIONS to "true",
            CompressionConfig.KEY_ABBREVIATIONS to "true",
            CompressionConfig.KEY_SLIM_MESSAGE_HEADERS to "true",
            CompressionConfig.KEY_CONSOLIDATE_COLLAPSED to "true",
        )

        val config = CompressionConfig.fromSettings(values)

        assertTrue(config.useToon)
        assertTrue(config.jsonMinify)
        assertTrue(config.terseSystemText)
        assertTrue(config.terseActions)
        assertTrue(config.abbreviations)
        assertTrue(config.slimMessageHeaders)
        assertTrue(config.consolidateCollapsed)
    }

    // =========================================================================
    // fromSettings with all false
    // =========================================================================

    @Test
    fun `fromSettings with all false sets all flags to false`() {
        val values = mapOf(
            CompressionConfig.KEY_USE_TOON to "false",
            CompressionConfig.KEY_JSON_MINIFY to "false",
            CompressionConfig.KEY_TERSE_SYSTEM_TEXT to "false",
            CompressionConfig.KEY_TERSE_ACTIONS to "false",
            CompressionConfig.KEY_ABBREVIATIONS to "false",
            CompressionConfig.KEY_SLIM_MESSAGE_HEADERS to "false",
            CompressionConfig.KEY_CONSOLIDATE_COLLAPSED to "false",
        )

        val config = CompressionConfig.fromSettings(values)

        assertFalse(config.useToon)
        assertFalse(config.jsonMinify)
        assertFalse(config.terseSystemText)
        assertFalse(config.terseActions)
        assertFalse(config.abbreviations)
        assertFalse(config.slimMessageHeaders)
        assertFalse(config.consolidateCollapsed)
    }

    // =========================================================================
    // fromSettings with mixed values
    // =========================================================================

    @Test
    fun `fromSettings with mixed values sets each flag independently`() {
        val values = mapOf(
            CompressionConfig.KEY_USE_TOON to "true",
            CompressionConfig.KEY_JSON_MINIFY to "false",
            CompressionConfig.KEY_TERSE_SYSTEM_TEXT to "true",
            CompressionConfig.KEY_TERSE_ACTIONS to "false",
            CompressionConfig.KEY_ABBREVIATIONS to "true",
            CompressionConfig.KEY_SLIM_MESSAGE_HEADERS to "false",
            CompressionConfig.KEY_CONSOLIDATE_COLLAPSED to "true",
        )

        val config = CompressionConfig.fromSettings(values)

        assertTrue(config.useToon)
        assertFalse(config.jsonMinify)
        assertTrue(config.terseSystemText)
        assertFalse(config.terseActions)
        assertTrue(config.abbreviations)
        assertFalse(config.slimMessageHeaders)
        assertTrue(config.consolidateCollapsed)
    }

    // =========================================================================
    // fromSettings with missing keys
    // =========================================================================

    @Test
    fun `fromSettings with missing keys defaults to false`() {
        val values = mapOf(
            CompressionConfig.KEY_USE_TOON to "true",
            // All other keys missing
        )

        val config = CompressionConfig.fromSettings(values)

        assertTrue(config.useToon, "Provided key should be true")
        assertFalse(config.jsonMinify, "Missing key should default to false")
        assertFalse(config.terseSystemText, "Missing key should default to false")
        assertFalse(config.terseActions, "Missing key should default to false")
        assertFalse(config.abbreviations, "Missing key should default to false")
        assertFalse(config.slimMessageHeaders, "Missing key should default to false")
        assertFalse(config.consolidateCollapsed, "Missing key should default to false")
    }

    @Test
    fun `fromSettings with empty map defaults all to false`() {
        val config = CompressionConfig.fromSettings(emptyMap())

        assertFalse(config.useToon)
        assertFalse(config.jsonMinify)
        assertFalse(config.terseSystemText)
        assertFalse(config.terseActions)
        assertFalse(config.abbreviations)
        assertFalse(config.slimMessageHeaders)
        assertFalse(config.consolidateCollapsed)
    }

    // =========================================================================
    // fromSettings with null values
    // =========================================================================

    @Test
    fun `fromSettings with null values defaults to false`() {
        val values = mapOf<String, String?>(
            CompressionConfig.KEY_USE_TOON to null,
            CompressionConfig.KEY_JSON_MINIFY to null,
            CompressionConfig.KEY_TERSE_SYSTEM_TEXT to null,
            CompressionConfig.KEY_TERSE_ACTIONS to null,
            CompressionConfig.KEY_ABBREVIATIONS to null,
            CompressionConfig.KEY_SLIM_MESSAGE_HEADERS to null,
            CompressionConfig.KEY_CONSOLIDATE_COLLAPSED to null,
        )

        val config = CompressionConfig.fromSettings(values)

        assertFalse(config.useToon, "Null value should default to false")
        assertFalse(config.jsonMinify, "Null value should default to false")
        assertFalse(config.terseSystemText, "Null value should default to false")
        assertFalse(config.terseActions, "Null value should default to false")
        assertFalse(config.abbreviations, "Null value should default to false")
        assertFalse(config.slimMessageHeaders, "Null value should default to false")
        assertFalse(config.consolidateCollapsed, "Null value should default to false")
    }

    // =========================================================================
    // updateField updates correct field
    // =========================================================================

    @Test
    fun `updateField updates useToon`() {
        val base = CompressionConfig()
        val updated = CompressionConfig.updateField(base, CompressionConfig.KEY_USE_TOON, "true")

        assertNotNull(updated)
        assertTrue(updated.useToon)
        assertFalse(updated.jsonMinify, "Other fields should remain unchanged")
    }

    @Test
    fun `updateField updates jsonMinify`() {
        val base = CompressionConfig()
        val updated = CompressionConfig.updateField(base, CompressionConfig.KEY_JSON_MINIFY, "true")

        assertNotNull(updated)
        assertTrue(updated.jsonMinify)
        assertFalse(updated.useToon, "Other fields should remain unchanged")
    }

    @Test
    fun `updateField updates terseSystemText`() {
        val base = CompressionConfig()
        val updated = CompressionConfig.updateField(base, CompressionConfig.KEY_TERSE_SYSTEM_TEXT, "true")

        assertNotNull(updated)
        assertTrue(updated.terseSystemText)
    }

    @Test
    fun `updateField updates terseActions`() {
        val base = CompressionConfig()
        val updated = CompressionConfig.updateField(base, CompressionConfig.KEY_TERSE_ACTIONS, "true")

        assertNotNull(updated)
        assertTrue(updated.terseActions)
    }

    @Test
    fun `updateField updates abbreviations`() {
        val base = CompressionConfig()
        val updated = CompressionConfig.updateField(base, CompressionConfig.KEY_ABBREVIATIONS, "true")

        assertNotNull(updated)
        assertTrue(updated.abbreviations)
    }

    @Test
    fun `updateField updates slimMessageHeaders`() {
        val base = CompressionConfig()
        val updated = CompressionConfig.updateField(base, CompressionConfig.KEY_SLIM_MESSAGE_HEADERS, "true")

        assertNotNull(updated)
        assertTrue(updated.slimMessageHeaders)
    }

    @Test
    fun `updateField updates consolidateCollapsed`() {
        val base = CompressionConfig()
        val updated = CompressionConfig.updateField(base, CompressionConfig.KEY_CONSOLIDATE_COLLAPSED, "true")

        assertNotNull(updated)
        assertTrue(updated.consolidateCollapsed)
    }

    @Test
    fun `updateField sets field to false when value is not true`() {
        val base = CompressionConfig(useToon = true)
        val updated = CompressionConfig.updateField(base, CompressionConfig.KEY_USE_TOON, "false")

        assertNotNull(updated)
        assertFalse(updated.useToon)
    }

    @Test
    fun `updateField sets field to false when value is null`() {
        val base = CompressionConfig(useToon = true)
        val updated = CompressionConfig.updateField(base, CompressionConfig.KEY_USE_TOON, null)

        assertNotNull(updated)
        assertFalse(updated.useToon)
    }

    // =========================================================================
    // updateField returns null for unknown key
    // =========================================================================

    @Test
    fun `updateField returns null for unknown key`() {
        val base = CompressionConfig()
        val result = CompressionConfig.updateField(base, "agent.some_other_setting", "true")

        assertNull(result, "Unknown key should return null")
    }

    @Test
    fun `updateField returns null for non-compression key`() {
        val base = CompressionConfig()
        val result = CompressionConfig.updateField(base, "agent.model", "gpt-4")

        assertNull(result, "Non-compression key should return null")
    }

    // =========================================================================
    // settingDefinitions has entries for all keys
    // =========================================================================

    @Test
    fun `settingDefinitions has entries for all compression keys`() {
        val definedKeys = CompressionConfig.settingDefinitions.map { it.first }.toSet()

        assertTrue(definedKeys.contains(CompressionConfig.KEY_USE_TOON))
        assertTrue(definedKeys.contains(CompressionConfig.KEY_JSON_MINIFY))
        assertTrue(definedKeys.contains(CompressionConfig.KEY_TERSE_SYSTEM_TEXT))
        assertTrue(definedKeys.contains(CompressionConfig.KEY_TERSE_ACTIONS))
        assertTrue(definedKeys.contains(CompressionConfig.KEY_ABBREVIATIONS))
        assertTrue(definedKeys.contains(CompressionConfig.KEY_SLIM_MESSAGE_HEADERS))
        assertTrue(definedKeys.contains(CompressionConfig.KEY_CONSOLIDATE_COLLAPSED))
        assertEquals(7, definedKeys.size, "Should have exactly 7 setting definitions")
    }

    @Test
    fun `settingDefinitions all have non-empty labels and descriptions`() {
        for ((key, label, description) in CompressionConfig.settingDefinitions) {
            assertTrue(label.isNotBlank(), "Label for '$key' should not be blank")
            assertTrue(description.isNotBlank(), "Description for '$key' should not be blank")
        }
    }

    // =========================================================================
    // Default constructor has all false
    // =========================================================================

    @Test
    fun `default constructor has all flags set to false`() {
        val config = CompressionConfig()

        assertFalse(config.useToon)
        assertFalse(config.jsonMinify)
        assertFalse(config.terseSystemText)
        assertFalse(config.terseActions)
        assertFalse(config.abbreviations)
        assertFalse(config.slimMessageHeaders)
        assertFalse(config.consolidateCollapsed)
    }

    // =========================================================================
    // TOON subsumes minify — independence verification
    // =========================================================================

    @Test
    fun `useToon and jsonMinify are independent flags`() {
        // When useToon=true, jsonMinify can be true or false independently
        // (it's up to formatters to check useToon first, not the config)
        val bothTrue = CompressionConfig(useToon = true, jsonMinify = true)
        assertTrue(bothTrue.useToon)
        assertTrue(bothTrue.jsonMinify)

        val toonOnly = CompressionConfig(useToon = true, jsonMinify = false)
        assertTrue(toonOnly.useToon)
        assertFalse(toonOnly.jsonMinify)

        val minifyOnly = CompressionConfig(useToon = false, jsonMinify = true)
        assertFalse(minifyOnly.useToon)
        assertTrue(minifyOnly.jsonMinify)
    }
}
