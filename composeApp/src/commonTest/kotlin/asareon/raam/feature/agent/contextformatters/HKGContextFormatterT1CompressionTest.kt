package asareon.raam.feature.agent.contextformatters

import asareon.raam.fakes.FakePlatformDependencies
import asareon.raam.feature.agent.CollapseState
import asareon.raam.feature.agent.CompressionConfig
import asareon.raam.feature.agent.PromptSection
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tier 1 Compression Tests for HkgContextFormatter.
 *
 * Verifies that CompressionConfig strategies correctly transform HKG output:
 * - Three-Tier Display: sub_holons stripping, STRIPPED mode field removal
 * - Strategy 1 (TOON): compact text rendering
 * - Strategy 2 (JSON Minify): whitespace removal
 * - Strategy 7 (Consolidate Collapsed): shorter collapsed summaries
 * - Backward compatibility: default config preserves existing behavior
 */
class HKGContextFormatterT1CompressionTest {

    private val platform = FakePlatformDependencies("test")

    // =========================================================================
    // Helpers (same pattern as HKGContextFormatterT1Test)
    // =========================================================================

    private fun holonJson(
        id: String,
        type: String = "Project",
        name: String = id,
        summary: String? = null,
        subHolons: List<Triple<String, String, String?>> = emptyList()
    ): String {
        val subHolonArray = subHolons.joinToString(",") { (subId, subType, subSummary) ->
            buildString {
                append("""{"id":"$subId","type":"$subType"""")
                if (subSummary != null) append(""","summary":"$subSummary"""")
                append("}")
            }
        }
        val summaryField = if (summary != null) ""","summary":"$summary"""" else ""
        return """{"header":{"id":"$id","type":"$type","name":"$name"$summaryField,"sub_holons":[$subHolonArray]},"payload":{}}"""
    }

    private fun hkgContext(vararg entries: Pair<String, String>): JsonObject =
        JsonObject(entries.associate { (id, json) -> id to JsonPrimitive(json) })

    /**
     * Recursively collects all content from Section/Group nodes.
     */
    private fun collectContent(section: PromptSection): String = when (section) {
        is PromptSection.Section -> section.content
        is PromptSection.Group -> {
            val headerContent = if (section.header.isNotBlank()) section.header + "\n" else ""
            headerContent + section.children.joinToString("\n") { collectContent(it) }
        }
        else -> ""
    }

    /**
     * Renders a [PromptSection.Group] to a flat string for content assertions.
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
     * Extracts the content of the first expanded holon child (hkg:*) from a unified section.
     */
    private fun extractFirstHolonContent(unified: PromptSection.Group): String {
        for (child in unified.children) {
            when (child) {
                is PromptSection.Section -> if (child.key.startsWith("hkg:")) return child.content
                is PromptSection.Group -> if (child.key.startsWith("hkg:")) return child.header
                else -> {}
            }
        }
        return ""
    }

    // =========================================================================
    // Test data
    // =========================================================================

    private val richHolon = """{"header":{"id":"rich-001","type":"Project","name":"Rich Project","summary":"A rich test.","version":"2.0.0","created_at":"2026-01-01T00:00:00Z","modified_at":"2026-04-01T00:00:00Z","sub_holons":[{"id":"child-001","type":"Document","summary":"A child."}],"filePath":"rich/path.json","parentId":"parent-001","depth":1},"payload":{"purpose":{"definition":"Test project for compression."},"state":{"status":"Active","engine":"Godot 4.x","genre":"2D platformer"},"items":["alpha","beta","gamma"]}}"""

    private val childHolon = holonJson(
        id = "child-001",
        type = "Document",
        name = "Child Doc",
        summary = "A child."
    )

    private val richContext = hkgContext(
        "rich-001" to richHolon,
        "child-001" to childHolon
    )

    private val leafHolon = holonJson(
        id = "leaf-001",
        type = "Note",
        name = "Leaf Note",
        summary = "A leaf."
    )

    private val leafContext = hkgContext("leaf-001" to leafHolon)

    // =========================================================================
    // Three-Tier Display (permanent, no toggle)
    // =========================================================================

    @Test
    fun `sub_holons stripped from rendered content with default config`() {
        val headers = HkgContextFormatter.parseHolonHeaders(richContext, platform)
        val overrides = mapOf("hkg:rich-001" to CollapseState.EXPANDED)
        val unified = HkgContextFormatter.buildUnifiedSection(
            richContext, headers, overrides, "Test", platformDependencies = platform
        )
        val content = extractFirstHolonContent(unified)
        assertFalse(content.contains("sub_holons"), "Expanded holon content should not contain sub_holons key")
    }

    @Test
    fun `STRIPPED mode strips metadata fields`() {
        val headers = HkgContextFormatter.parseHolonHeaders(richContext, platform)
        val overrides = mapOf("hkg:rich-001" to CollapseState.EXPANDED)
        val unified = HkgContextFormatter.buildUnifiedSection(
            richContext, headers, overrides, "Test", platformDependencies = platform
        )
        val content = extractFirstHolonContent(unified)

        assertFalse(content.contains("\"version\""), "Stripped content should not contain version")
        assertFalse(content.contains("\"created_at\""), "Stripped content should not contain created_at")
        assertFalse(content.contains("\"modified_at\""), "Stripped content should not contain modified_at")
        assertFalse(content.contains("\"filePath\""), "Stripped content should not contain filePath")
        assertFalse(content.contains("\"parentId\""), "Stripped content should not contain parentId")
        assertFalse(content.contains("\"depth\""), "Stripped content should not contain depth")
    }

    @Test
    fun `STRIPPED mode preserves payload`() {
        val headers = HkgContextFormatter.parseHolonHeaders(richContext, platform)
        val overrides = mapOf("hkg:rich-001" to CollapseState.EXPANDED)
        val unified = HkgContextFormatter.buildUnifiedSection(
            richContext, headers, overrides, "Test", platformDependencies = platform
        )
        val content = extractFirstHolonContent(unified)

        assertTrue(content.contains("purpose"), "Stripped content should preserve payload.purpose")
        assertTrue(content.contains("Test project for compression."), "Stripped content should preserve payload values")
        assertTrue(content.contains("items"), "Stripped content should preserve payload.items")
        assertTrue(content.contains("alpha"), "Stripped content should preserve array items")
    }

    @Test
    fun `STRIPPED mode preserves id type name summary`() {
        val headers = HkgContextFormatter.parseHolonHeaders(richContext, platform)
        val overrides = mapOf("hkg:rich-001" to CollapseState.EXPANDED)
        val unified = HkgContextFormatter.buildUnifiedSection(
            richContext, headers, overrides, "Test", platformDependencies = platform
        )
        val content = extractFirstHolonContent(unified)

        assertTrue(content.contains("rich-001"), "Stripped content should preserve id")
        assertTrue(content.contains("Project"), "Stripped content should preserve type")
        assertTrue(content.contains("Rich Project"), "Stripped content should preserve name")
        assertTrue(content.contains("A rich test."), "Stripped content should preserve summary")
    }

    // =========================================================================
    // TOON Format (Strategy 1)
    // =========================================================================

    @Test
    fun `TOON rendering produces non-JSON output`() {
        val config = CompressionConfig(useToon = true)
        val headers = HkgContextFormatter.parseHolonHeaders(richContext, platform)
        val overrides = mapOf("hkg:rich-001" to CollapseState.EXPANDED)
        val unified = HkgContextFormatter.buildUnifiedSection(
            richContext, headers, overrides, "Test",
            platformDependencies = platform, compressionConfig = config
        )
        val content = extractFirstHolonContent(unified)

        assertFalse(content.trimStart().startsWith("{"), "TOON content should not start with {")
    }

    @Test
    fun `TOON header line format`() {
        val config = CompressionConfig(useToon = true)
        val headers = HkgContextFormatter.parseHolonHeaders(richContext, platform)
        val overrides = mapOf("hkg:rich-001" to CollapseState.EXPANDED)
        val unified = HkgContextFormatter.buildUnifiedSection(
            richContext, headers, overrides, "Test",
            platformDependencies = platform, compressionConfig = config
        )
        val content = extractFirstHolonContent(unified)

        assertTrue(content.contains("[rich-001] Project: Rich Project"),
            "TOON should render header line as [id] Type: Name")
    }

    @Test
    fun `TOON shows summary`() {
        val config = CompressionConfig(useToon = true)
        val headers = HkgContextFormatter.parseHolonHeaders(richContext, platform)
        val overrides = mapOf("hkg:rich-001" to CollapseState.EXPANDED)
        val unified = HkgContextFormatter.buildUnifiedSection(
            richContext, headers, overrides, "Test",
            platformDependencies = platform, compressionConfig = config
        )
        val content = extractFirstHolonContent(unified)

        assertTrue(content.contains("A rich test."), "TOON should show holon summary")
    }

    @Test
    fun `TOON renders payload keys`() {
        val config = CompressionConfig(useToon = true)
        val headers = HkgContextFormatter.parseHolonHeaders(richContext, platform)
        val overrides = mapOf("hkg:rich-001" to CollapseState.EXPANDED)
        val unified = HkgContextFormatter.buildUnifiedSection(
            richContext, headers, overrides, "Test",
            platformDependencies = platform, compressionConfig = config
        )
        val content = extractFirstHolonContent(unified)

        assertTrue(content.contains("purpose:"), "TOON should render purpose key")
        assertTrue(content.contains("state:"), "TOON should render state key")
        assertTrue(content.contains("items:"), "TOON should render items key")
    }

    @Test
    fun `TOON flattens single-value objects`() {
        val config = CompressionConfig(useToon = true)
        val headers = HkgContextFormatter.parseHolonHeaders(richContext, platform)
        val overrides = mapOf("hkg:rich-001" to CollapseState.EXPANDED)
        val unified = HkgContextFormatter.buildUnifiedSection(
            richContext, headers, overrides, "Test",
            platformDependencies = platform, compressionConfig = config
        )
        val content = extractFirstHolonContent(unified)

        // purpose has a single child "definition" with a string value — should flatten
        assertTrue(content.contains("purpose: Test project for compression."),
            "TOON should flatten single-value objects to key: value")
    }

    @Test
    fun `TOON renders arrays inline`() {
        val config = CompressionConfig(useToon = true)
        val headers = HkgContextFormatter.parseHolonHeaders(richContext, platform)
        val overrides = mapOf("hkg:rich-001" to CollapseState.EXPANDED)
        val unified = HkgContextFormatter.buildUnifiedSection(
            richContext, headers, overrides, "Test",
            platformDependencies = platform, compressionConfig = config
        )
        val content = extractFirstHolonContent(unified)

        assertTrue(content.contains("items: alpha, beta, gamma"),
            "TOON should render primitive arrays inline as comma-separated values")
    }

    @Test
    fun `default config produces JSON output`() {
        val headers = HkgContextFormatter.parseHolonHeaders(richContext, platform)
        val overrides = mapOf("hkg:rich-001" to CollapseState.EXPANDED)
        val unified = HkgContextFormatter.buildUnifiedSection(
            richContext, headers, overrides, "Test", platformDependencies = platform
        )
        val content = extractFirstHolonContent(unified)

        assertTrue(content.trimStart().startsWith("{"),
            "Default config (no TOON) should produce JSON output starting with {")
    }

    // =========================================================================
    // JSON Minify (Strategy 2)
    // =========================================================================

    @Test
    fun `minified output has no newlines in JSON`() {
        val config = CompressionConfig(jsonMinify = true)
        val headers = HkgContextFormatter.parseHolonHeaders(leafContext, platform)
        val overrides = mapOf("hkg:leaf-001" to CollapseState.EXPANDED)
        val unified = HkgContextFormatter.buildUnifiedSection(
            leafContext, headers, overrides, "Test",
            platformDependencies = platform, compressionConfig = config
        )
        val content = extractFirstHolonContent(unified)

        assertFalse(content.contains("\n"), "Minified JSON should be a single line with no newlines")
    }

    @Test
    fun `minified output has no indentation`() {
        val config = CompressionConfig(jsonMinify = true)
        val headers = HkgContextFormatter.parseHolonHeaders(richContext, platform)
        val overrides = mapOf("hkg:rich-001" to CollapseState.EXPANDED)
        val unified = HkgContextFormatter.buildUnifiedSection(
            richContext, headers, overrides, "Test",
            platformDependencies = platform, compressionConfig = config
        )
        val content = extractFirstHolonContent(unified)

        assertFalse(content.contains("    "), "Minified JSON should have no four-space indentation")
    }

    @Test
    fun `minify and TOON both enabled — TOON wins`() {
        val config = CompressionConfig(useToon = true, jsonMinify = true)
        val headers = HkgContextFormatter.parseHolonHeaders(richContext, platform)
        val overrides = mapOf("hkg:rich-001" to CollapseState.EXPANDED)
        val unified = HkgContextFormatter.buildUnifiedSection(
            richContext, headers, overrides, "Test",
            platformDependencies = platform, compressionConfig = config
        )
        val content = extractFirstHolonContent(unified)

        assertFalse(content.trimStart().startsWith("{"),
            "When both TOON and minify are enabled, TOON should take precedence (non-JSON)")
    }

    // =========================================================================
    // Collapsed Consolidation (Strategy 7)
    // =========================================================================

    @Test
    fun `consolidated collapsed summary for leaf uses short format`() {
        val config = CompressionConfig(consolidateCollapsed = true)
        val headers = HkgContextFormatter.parseHolonHeaders(leafContext, platform)
        val unified = HkgContextFormatter.buildUnifiedSection(
            leafContext, headers, emptyMap(), "Test",
            platformDependencies = platform, compressionConfig = config
        )

        val leafChild = unified.children.first()
        val summary = when (leafChild) {
            is PromptSection.Section -> leafChild.collapsedSummary
            is PromptSection.Group -> leafChild.collapsedSummary
            else -> null
        }

        assertTrue(summary != null, "Leaf should have a collapsed summary")
        assertTrue(summary!!.contains("[Note: Leaf Note]"),
            "Consolidated leaf summary should be [Type: Name], got: $summary")
        assertFalse(summary.contains("file closed"),
            "Consolidated summary should not contain 'file closed'")
    }

    @Test
    fun `consolidated collapsed summary for branch contains children count`() {
        val config = CompressionConfig(consolidateCollapsed = true)
        val headers = HkgContextFormatter.parseHolonHeaders(richContext, platform)
        val unified = HkgContextFormatter.buildUnifiedSection(
            richContext, headers, emptyMap(), "Test",
            platformDependencies = platform, compressionConfig = config
        )

        val branchChild = unified.children.first()
        val summary = when (branchChild) {
            is PromptSection.Section -> branchChild.collapsedSummary
            is PromptSection.Group -> branchChild.collapsedSummary
            else -> null
        }

        assertTrue(summary != null, "Branch should have a collapsed summary")
        assertTrue(summary!!.contains("children"),
            "Consolidated branch summary should contain 'children', got: $summary")
        assertTrue(summary.contains("|"),
            "Consolidated branch summary should use '|' separator, got: $summary")
    }

    // =========================================================================
    // Backward Compatibility
    // =========================================================================

    @Test
    fun `default CompressionConfig preserves existing behavior`() {
        val headers = HkgContextFormatter.parseHolonHeaders(richContext, platform)
        val overrides = mapOf("hkg:rich-001" to CollapseState.EXPANDED)
        val unified = HkgContextFormatter.buildUnifiedSection(
            richContext, headers, overrides, "Test", platformDependencies = platform
        )
        val content = extractFirstHolonContent(unified)

        // Default produces JSON with header fields (minus sub_holons which is always stripped)
        assertTrue(content.trimStart().startsWith("{"), "Default should produce JSON")
        assertTrue(content.contains("\"id\""), "Default should contain id field")
        assertTrue(content.contains("\"type\""), "Default should contain type field")
        assertFalse(content.contains("sub_holons"), "sub_holons should always be stripped")
    }
}
