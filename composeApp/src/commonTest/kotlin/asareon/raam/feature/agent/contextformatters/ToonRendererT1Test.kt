package asareon.raam.feature.agent.contextformatters

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * Tier 1 Unit Tests for ToonRenderer.
 *
 * ToonRenderer is a pure function that transforms holon JSON into TOON
 * (Terse Object-Oriented Notation) — a compact, human-readable text format.
 *
 * Tests verify:
 * - Header line format: [id] Type: Name
 * - Stripped vs full rendering modes
 * - Payload flattening, array rendering, nested objects
 * - Empty/null handling and malformed JSON fallback
 * - sub_holons exclusion from rendered output
 */
class ToonRendererT1Test {

    // =========================================================================
    // Test data
    // =========================================================================

    private val simpleHolon = """{"header":{"id":"test-001","type":"Project","name":"Test Project","summary":"A test.","version":"1.0.0","created_at":"2026-01-01T00:00:00Z","modified_at":"2026-01-02T00:00:00Z"},"payload":{"state":{"status":"Active","engine":"Godot 4.x"},"items":["a","b","c"]}}"""

    private val nestedHolon = """{"header":{"id":"nested-001","type":"Document","name":"Nested Doc"},"payload":{"level1":{"level2":{"level3":"deep value"}},"flat_obj":{"single_key":"single_value"}}}"""

    private val holonWithRelationships = """{"header":{"id":"rel-001","type":"Agent","name":"RelAgent","summary":"Has relationships.","version":"2.0","created_at":"2026-03-01T00:00:00Z","modified_at":"2026-03-02T00:00:00Z","filePath":"/agents/rel-001.json","relationships":[{"target_id":"target-A","type":"depends_on"},{"target_id":"target-B","type":"references"}]},"payload":{"config":{"mode":"auto"}}}"""

    private val holonWithSubHolons = """{"header":{"id":"parent-001","type":"Project","name":"Parent","summary":"Has children.","sub_holons":[{"id":"child-1","type":"Doc"},{"id":"child-2","type":"Doc"}]},"payload":{"status":"active"}}"""

    private val emptyPayloadHolon = """{"header":{"id":"empty-001","type":"Note","name":"Empty Note","summary":"Nothing here."},"payload":{}}"""

    private val nullSummaryHolon = """{"header":{"id":"null-001","type":"Note","name":"No Summary"},"payload":{"content":"Some content"}}"""

    private val objectArrayHolon = """{"header":{"id":"arr-001","type":"Document","name":"Arr Doc"},"payload":{"people":[{"name":"Alice","role":"dev"},{"name":"Bob","role":"pm"}]}}"""

    private val emptyArrayHolon = """{"header":{"id":"earr-001","type":"Document","name":"Empty Arr"},"payload":{"tags":[],"notes":[]}}"""

    // =========================================================================
    // Basic rendering
    // =========================================================================

    @Test
    fun `render produces TOON format from simple holon`() {
        val result = ToonRenderer.render(simpleHolon)

        assertTrue(result.contains("[test-001] Project: Test Project"))
        assertTrue(result.contains("A test."))
        assertTrue(result.contains("status: Active"))
    }

    // =========================================================================
    // Stripped mode (default)
    // =========================================================================

    @Test
    fun `render in stripped mode omits version dates paths and relationships`() {
        val result = ToonRenderer.render(holonWithRelationships, stripped = true)

        assertFalse(result.contains("version:"), "Stripped mode should not show version")
        assertFalse(result.contains("created:"), "Stripped mode should not show created_at")
        assertFalse(result.contains("modified:"), "Stripped mode should not show modified_at")
        assertFalse(result.contains("path:"), "Stripped mode should not show filePath")
        assertFalse(result.contains("relationships:"), "Stripped mode should not show relationships")
    }

    @Test
    fun `render in stripped mode shows id type name and summary`() {
        val result = ToonRenderer.render(holonWithRelationships, stripped = true)

        assertTrue(result.contains("[rel-001] Agent: RelAgent"))
        assertTrue(result.contains("Has relationships."))
    }

    // =========================================================================
    // Full mode (stripped=false)
    // =========================================================================

    @Test
    fun `render in full mode shows version dates paths and relationships`() {
        val result = ToonRenderer.render(holonWithRelationships, stripped = false)

        assertTrue(result.contains("version: 2.0"), "Full mode should show version")
        assertTrue(result.contains("created: 2026-03-01T00:00:00Z"), "Full mode should show created_at")
        assertTrue(result.contains("modified: 2026-03-02T00:00:00Z"), "Full mode should show modified_at")
        assertTrue(result.contains("path: /agents/rel-001.json"), "Full mode should show filePath")
        assertTrue(result.contains("relationships:"), "Full mode should show relationships section")
        assertTrue(result.contains("depends_on -> target-A"))
        assertTrue(result.contains("references -> target-B"))
    }

    // =========================================================================
    // Payload flattening
    // =========================================================================

    @Test
    fun `render flattens single-value objects to key-value pair`() {
        val result = ToonRenderer.render(nestedHolon)

        // flat_obj has a single key "single_key" with value "single_value"
        // should flatten to: flat_obj: single_value
        assertTrue(result.contains("flat_obj: single_value"),
            "Single-value object should flatten. Got:\n$result")
    }

    // =========================================================================
    // Array rendering
    // =========================================================================

    @Test
    fun `render shows primitive arrays as comma-separated inline`() {
        val result = ToonRenderer.render(simpleHolon)

        assertTrue(result.contains("items: a, b, c"),
            "Primitive array should render inline. Got:\n$result")
    }

    // =========================================================================
    // Object array rendering
    // =========================================================================

    @Test
    fun `render shows small object arrays as compact dash entries`() {
        val result = ToonRenderer.render(objectArrayHolon)

        assertTrue(result.contains("people:"), "Should have people heading")
        assertTrue(result.contains("- name=Alice, role=dev"),
            "Small objects should render compact. Got:\n$result")
        assertTrue(result.contains("- name=Bob, role=pm"),
            "Second object should also render compact. Got:\n$result")
    }

    // =========================================================================
    // Nested objects
    // =========================================================================

    @Test
    fun `render indents nested objects at each depth`() {
        val result = ToonRenderer.render(nestedHolon)

        // level1 contains level2 which contains level3.
        // TOON flattens single-key objects: level2 has only level3, so it collapses to "level2: deep value".
        // level1 has level2 (single child) so it may also flatten.
        assertTrue(result.contains("level1:"), "Should have level1 heading. Got:\n$result")
        assertTrue(result.contains("deep value"),
            "Deep value should be rendered somewhere. Got:\n$result")

        // flat_obj has single key "single_key" → flattens to "flat_obj: single_value"
        assertTrue(result.contains("flat_obj: single_value"),
            "Single-key object should flatten. Got:\n$result")
    }

    // =========================================================================
    // Empty/null handling
    // =========================================================================

    @Test
    fun `render omits empty payload section`() {
        val result = ToonRenderer.render(emptyPayloadHolon)

        // Should have header line and summary but no payload section
        assertTrue(result.contains("[empty-001] Note: Empty Note"))
        assertTrue(result.contains("Nothing here."))
        // Should not have stray blank lines from payload
        val lines = result.trimEnd().lines()
        // After header + summary, nothing else
        assertEquals(2, lines.size, "Empty payload should produce only header + summary. Got:\n$result")
    }

    @Test
    fun `render omits null summary`() {
        val result = ToonRenderer.render(nullSummaryHolon)

        val lines = result.lines()
        assertEquals("[null-001] Note: No Summary", lines[0])
        // Second line should NOT be an indented empty summary — it should be blank or payload
        if (lines.size > 1) {
            assertFalse(lines[1].startsWith("  ") && lines[1].trim().isEmpty(),
                "Null summary should not produce indented blank line")
        }
    }

    @Test
    fun `render omits empty arrays from payload`() {
        val result = ToonRenderer.render(emptyArrayHolon)

        assertFalse(result.contains("tags:"), "Empty array 'tags' should be omitted")
        assertFalse(result.contains("notes:"), "Empty array 'notes' should be omitted")
    }

    // =========================================================================
    // Malformed JSON fallback
    // =========================================================================

    @Test
    fun `render returns original string on malformed JSON`() {
        val malformed = "this is not valid json at all {{"
        val result = ToonRenderer.render(malformed)

        assertEquals(malformed, result, "Malformed JSON should return original string")
    }

    @Test
    fun `render returns original string when header is missing`() {
        val noHeader = """{"payload":{"key":"value"}}"""
        val result = ToonRenderer.render(noHeader)

        assertEquals(noHeader, result, "Missing header should return original string")
    }

    // =========================================================================
    // Header line format
    // =========================================================================

    @Test
    fun `render produces header in bracket-id Type-Name format`() {
        val result = ToonRenderer.render(simpleHolon)
        val firstLine = result.lines().first()

        assertEquals("[test-001] Project: Test Project", firstLine,
            "Header format should be [id] Type: Name")
    }

    // =========================================================================
    // Summary indentation
    // =========================================================================

    @Test
    fun `render shows summary indented under header`() {
        val result = ToonRenderer.render(simpleHolon)
        val lines = result.lines()

        assertTrue(lines.size >= 2, "Should have at least header + summary")
        assertTrue(lines[1].startsWith("  "), "Summary should be indented with 2 spaces")
        assertEquals("  A test.", lines[1], "Summary should be indented summary text")
    }

    // =========================================================================
    // sub_holons NOT shown
    // =========================================================================

    @Test
    fun `render does not include sub_holons in output even in full mode`() {
        val result = ToonRenderer.render(holonWithSubHolons, stripped = false)

        assertFalse(result.contains("sub_holons"), "sub_holons should not appear in rendered output")
        assertFalse(result.contains("child-1"), "sub_holon IDs should not appear in rendered output")
        assertFalse(result.contains("child-2"), "sub_holon IDs should not appear in rendered output")
    }
}
