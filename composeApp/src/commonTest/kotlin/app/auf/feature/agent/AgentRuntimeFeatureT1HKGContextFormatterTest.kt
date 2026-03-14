package app.auf.feature.agent

import app.auf.fakes.FakePlatformDependencies
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tier 1 Unit Tests for HkgContextFormatter.
 *
 * HkgContextFormatter is a pipeline-level pure utility (§2.3 absolute decoupling)
 * that transforms raw HKG context into the two-partition INDEX + FILES view (§4).
 *
 * Tests verify:
 * - parseHolonHeaders: header extraction, tree depth, parent-child, malformed handling
 * - buildIndexTree: COLLAPSED/EXPANDED tags, sub-holon badges, indentation, children visibility
 * - buildFilesSection: only EXPANDED holons, START/END markers, empty message
 * - countSubHolons: recursive counting
 * - resolveCollapseState: default COLLAPSED, override EXPANDED
 */
class AgentRuntimeFeatureT1HKGContextFormatterTest {

    private val platform = FakePlatformDependencies("test")

    // =========================================================================
    // Helper: build raw HKG context (holonId → raw JSON string)
    // =========================================================================

    /**
     * Builds a minimal holon JSON string with the given header fields.
     */
    private fun holonJson(
        id: String,
        type: String = "Project",
        name: String = id,
        summary: String? = null,
        subHolons: List<Triple<String, String, String?>> = emptyList() // (id, type, summary)
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

    /**
     * Builds a JsonObject HKG context map from vararg (holonId, rawJson) pairs.
     */
    private fun hkgContext(vararg entries: Pair<String, String>): JsonObject =
        JsonObject(entries.associate { (id, json) -> id to JsonPrimitive(json) })

    // =========================================================================
    // A Meridian-like test HKG for reuse across tests
    // =========================================================================

    private val meridianRoot = holonJson(
        id = "meridian-root",
        type = "AI_Persona_Root",
        name = "Meridian",
        summary = "A cartographic intelligence.",
        subHolons = listOf(
            Triple("foundational-core", "Project", "Origin story."),
            Triple("session-logs", "Project", "Session chronicle."),
            Triple("shared-knowledge", "Project", "Universal knowledge.")
        )
    )

    private val foundationalCore = holonJson(
        id = "foundational-core",
        type = "Project",
        name = "Foundational Core",
        summary = "Meridian's origin.",
        subHolons = listOf(
            Triple("awakening-log", "Document", "First boot log.")
        )
    )

    private val awakeningLog = holonJson(
        id = "awakening-log",
        type = "Document",
        name = "Awakening Log",
        summary = "The first boot."
    )

    private val sessionLogs = holonJson(
        id = "session-logs",
        type = "Project",
        name = "Session Logs",
        summary = "All sessions.",
        subHolons = listOf(
            Triple("session-001", "Document", "Session 1."),
            Triple("session-002", "Document", "Session 2.")
        )
    )

    private val session001 = holonJson("session-001", "Document", "Session 001", "First session.")
    private val session002 = holonJson("session-002", "Document", "Session 002", "Second session.")

    private val sharedKnowledge = holonJson(
        id = "shared-knowledge",
        type = "Project",
        name = "Shared Knowledge",
        summary = "Universal artifacts."
    )

    private val fullMeridianContext = hkgContext(
        "meridian-root" to meridianRoot,
        "foundational-core" to foundationalCore,
        "awakening-log" to awakeningLog,
        "session-logs" to sessionLogs,
        "session-001" to session001,
        "session-002" to session002,
        "shared-knowledge" to sharedKnowledge
    )

    // =========================================================================
    // parseHolonHeaders
    // =========================================================================

    @Test
    fun `parseHolonHeaders extracts all holons from Meridian-like context`() {
        val headers = HkgContextFormatter.parseHolonHeaders(fullMeridianContext, platform)

        assertEquals(7, headers.size)
        assertTrue(headers.containsKey("meridian-root"))
        assertTrue(headers.containsKey("foundational-core"))
        assertTrue(headers.containsKey("awakening-log"))
        assertTrue(headers.containsKey("session-logs"))
    }

    @Test
    fun `parseHolonHeaders computes correct depth for tree`() {
        val headers = HkgContextFormatter.parseHolonHeaders(fullMeridianContext, platform)

        assertEquals(0, headers["meridian-root"]!!.depth)
        assertEquals(1, headers["foundational-core"]!!.depth)
        assertEquals(1, headers["session-logs"]!!.depth)
        assertEquals(1, headers["shared-knowledge"]!!.depth)
        assertEquals(2, headers["awakening-log"]!!.depth)
        assertEquals(2, headers["session-001"]!!.depth)
        assertEquals(2, headers["session-002"]!!.depth)
    }

    @Test
    fun `parseHolonHeaders assigns correct parentId`() {
        val headers = HkgContextFormatter.parseHolonHeaders(fullMeridianContext, platform)

        assertEquals(null, headers["meridian-root"]!!.parentId)
        assertEquals("meridian-root", headers["foundational-core"]!!.parentId)
        assertEquals("meridian-root", headers["session-logs"]!!.parentId)
        assertEquals("foundational-core", headers["awakening-log"]!!.parentId)
        assertEquals("session-logs", headers["session-001"]!!.parentId)
    }

    @Test
    fun `parseHolonHeaders extracts sub-holon refs`() {
        val headers = HkgContextFormatter.parseHolonHeaders(fullMeridianContext, platform)

        val root = headers["meridian-root"]!!
        assertEquals(3, root.subHolonRefs.size)
        assertTrue(root.subHolonRefs.any { it.id == "foundational-core" })
        assertTrue(root.subHolonRefs.any { it.id == "session-logs" })
        assertTrue(root.subHolonRefs.any { it.id == "shared-knowledge" })
    }

    @Test
    fun `parseHolonHeaders handles empty context`() {
        val headers = HkgContextFormatter.parseHolonHeaders(JsonObject(emptyMap()), platform)
        assertTrue(headers.isEmpty())
    }

    @Test
    fun `parseHolonHeaders skips holon with missing header`() {
        val context = hkgContext(
            "good-holon" to holonJson("good-holon", name = "Good"),
            "bad-holon" to """{"payload": {}}""" // No header
        )

        val headers = HkgContextFormatter.parseHolonHeaders(context, platform)

        assertEquals(1, headers.size)
        assertTrue(headers.containsKey("good-holon"))
        assertFalse(headers.containsKey("bad-holon"))
    }

    @Test
    fun `parseHolonHeaders handles malformed sub_holon entry gracefully`() {
        val holonWithBadSub = """{"header":{"id":"parent","type":"Project","name":"Parent","sub_holons":[{"id":"good-child","type":"Doc"},{"garbage":true}]}}"""
        val context = hkgContext("parent" to holonWithBadSub)

        val headers = HkgContextFormatter.parseHolonHeaders(context, platform)

        assertEquals(1, headers.size)
        // Only the good child ref should be present
        assertEquals(1, headers["parent"]!!.subHolonRefs.size)
        assertEquals("good-child", headers["parent"]!!.subHolonRefs[0].id)
    }

    // =========================================================================
    // countSubHolons
    // =========================================================================

    @Test
    fun `countSubHolons counts recursively`() {
        val headers = HkgContextFormatter.parseHolonHeaders(fullMeridianContext, platform)

        // root has 3 direct children; foundational-core has 1 child; session-logs has 2
        // Total under root: 3 + 1 + 2 = 6
        assertEquals(6, HkgContextFormatter.countSubHolons("meridian-root", headers))

        // foundational-core has 1 direct child (awakening-log), which has 0
        assertEquals(1, HkgContextFormatter.countSubHolons("foundational-core", headers))

        // session-logs has 2 direct children, each with 0
        assertEquals(2, HkgContextFormatter.countSubHolons("session-logs", headers))

        // leaf has 0
        assertEquals(0, HkgContextFormatter.countSubHolons("awakening-log", headers))
    }

    @Test
    fun `countSubHolons returns 0 for unknown holonId`() {
        val headers = HkgContextFormatter.parseHolonHeaders(fullMeridianContext, platform)
        assertEquals(0, HkgContextFormatter.countSubHolons("nonexistent", headers))
    }

    // =========================================================================
    // resolveCollapseState
    // =========================================================================

    @Test
    fun `resolveCollapseState defaults to COLLAPSED`() {
        assertEquals(
            CollapseState.COLLAPSED,
            HkgContextFormatter.resolveCollapseState("any-holon", emptyMap())
        )
    }

    @Test
    fun `resolveCollapseState uses hkg prefix convention`() {
        val overrides = mapOf("hkg:my-holon" to CollapseState.EXPANDED)

        assertEquals(CollapseState.EXPANDED, HkgContextFormatter.resolveCollapseState("my-holon", overrides))
        assertEquals(CollapseState.COLLAPSED, HkgContextFormatter.resolveCollapseState("other-holon", overrides))
    }

    // =========================================================================
    // buildIndexTree
    // =========================================================================

    @Test
    fun `buildIndexTree renders all-collapsed Meridian tree with badges`() {
        val headers = HkgContextFormatter.parseHolonHeaders(fullMeridianContext, platform)
        val index = HkgContextFormatter.buildIndexTree(headers, emptyMap(), "Meridian")

        assertTrue(index.contains("HOLON_KNOWLEDGE_GRAPH_INDEX"))
        assertTrue(index.contains("Persona: Meridian | Total holons: 7"))

        // Root should be COLLAPSED (no overrides)
        assertTrue(index.contains("""meridian-root (AI_Persona_Root) — "Meridian" [COLLAPSED]"""))

        // Root's sub-holon count badge (all 6 descendants)
        assertTrue(index.contains("<contains 6 sub-holons>"))
    }

    @Test
    fun `buildIndexTree shows EXPANDED tag and reveals children`() {
        val headers = HkgContextFormatter.parseHolonHeaders(fullMeridianContext, platform)
        val overrides = mapOf("hkg:meridian-root" to CollapseState.EXPANDED)

        val index = HkgContextFormatter.buildIndexTree(headers, overrides, "Meridian")

        // Root is EXPANDED
        assertTrue(index.contains("""meridian-root (AI_Persona_Root) — "Meridian" [EXPANDED]"""))

        // Children are visible (but themselves COLLAPSED)
        assertTrue(index.contains("""foundational-core (Project) — "Foundational Core" [COLLAPSED]"""))
        assertTrue(index.contains("""session-logs (Project) — "Session Logs" [COLLAPSED]"""))
        assertTrue(index.contains("""shared-knowledge (Project) — "Shared Knowledge" [COLLAPSED]"""))
    }

    @Test
    fun `buildIndexTree COLLAPSED branch hides children`() {
        val headers = HkgContextFormatter.parseHolonHeaders(fullMeridianContext, platform)
        val overrides = mapOf("hkg:meridian-root" to CollapseState.COLLAPSED)

        val index = HkgContextFormatter.buildIndexTree(headers, overrides, "Meridian")

        // Root is collapsed — children should NOT appear
        assertFalse(index.contains("foundational-core"))
        assertFalse(index.contains("session-logs"))
        assertFalse(index.contains("shared-knowledge"))
        // But badge shows count
        assertTrue(index.contains("<contains 6 sub-holons>"))
    }

    @Test
    fun `buildIndexTree mixed collapse states render correctly`() {
        val headers = HkgContextFormatter.parseHolonHeaders(fullMeridianContext, platform)
        val overrides = mapOf(
            "hkg:meridian-root" to CollapseState.EXPANDED,
            "hkg:session-logs" to CollapseState.EXPANDED,
            "hkg:foundational-core" to CollapseState.COLLAPSED
        )

        val index = HkgContextFormatter.buildIndexTree(headers, overrides, "Meridian")

        // Root expanded → children visible
        assertTrue(index.contains("""foundational-core (Project) — "Foundational Core" [COLLAPSED]"""))
        assertTrue(index.contains("""session-logs (Project) — "Session Logs" [EXPANDED]"""))

        // foundational-core collapsed → awakening-log hidden, badge shown
        assertFalse(index.contains("awakening-log"))
        assertTrue(index.contains("<contains 1 sub-holons>"))

        // session-logs expanded → children visible
        assertTrue(index.contains("""session-001 (Document) — "Session 001" [COLLAPSED]"""))
        assertTrue(index.contains("""session-002 (Document) — "Session 002" [COLLAPSED]"""))
    }

    @Test
    fun `buildIndexTree renders empty context message`() {
        val index = HkgContextFormatter.buildIndexTree(emptyMap(), emptyMap())

        assertTrue(index.contains("No knowledge graph loaded."))
    }

    @Test
    fun `buildIndexTree uses 2-space indentation per depth level`() {
        val headers = HkgContextFormatter.parseHolonHeaders(fullMeridianContext, platform)
        val overrides = mapOf(
            "hkg:meridian-root" to CollapseState.EXPANDED,
            "hkg:foundational-core" to CollapseState.EXPANDED
        )

        val index = HkgContextFormatter.buildIndexTree(headers, overrides, "Meridian")

        // depth 0: no indent
        assertTrue(index.contains("meridian-root (AI_Persona_Root)"))
        // depth 1: 2 spaces
        assertTrue(index.contains("  foundational-core (Project)"))
        // depth 2: 4 spaces
        assertTrue(index.contains("    awakening-log (Document)"))
    }

    // =========================================================================
    // buildFilesSection
    // =========================================================================

    @Test
    fun `buildFilesSection lists only EXPANDED holons with markers`() {
        val overrides = mapOf(
            "hkg:meridian-root" to CollapseState.EXPANDED,
            "hkg:foundational-core" to CollapseState.EXPANDED
        )

        val files = HkgContextFormatter.buildFilesSection(fullMeridianContext, overrides, platform)

        assertTrue(files.contains("HOLON_KNOWLEDGE_GRAPH_FILES"))
        assertTrue(files.contains("Files currently open: 2 of 7"))
        assertTrue(files.contains("--- START OF FILE meridian-root.json ---"))
        assertTrue(files.contains("--- END OF FILE meridian-root.json ---"))
        assertTrue(files.contains("--- START OF FILE foundational-core.json ---"))
        assertTrue(files.contains("--- END OF FILE foundational-core.json ---"))

        // Collapsed holons should NOT appear in FILES
        assertFalse(files.contains("START OF FILE session-logs.json"))
        assertFalse(files.contains("START OF FILE awakening-log.json"))
    }

    @Test
    fun `buildFilesSection shows empty message when all collapsed`() {
        val files = HkgContextFormatter.buildFilesSection(fullMeridianContext, emptyMap(), platform)

        assertTrue(files.contains("No files open. Use agent.CONTEXT_UNCOLLAPSE to open holon files."))
        assertFalse(files.contains("START OF FILE"))
    }

    @Test
    fun `buildFilesSection includes actual JSON content of expanded holons`() {
        val overrides = mapOf("hkg:shared-knowledge" to CollapseState.EXPANDED)
        val files = HkgContextFormatter.buildFilesSection(fullMeridianContext, overrides, platform)

        // Should contain the raw JSON content of the expanded holon
        assertTrue(files.contains("\"id\":\"shared-knowledge\""))
        assertTrue(files.contains("\"type\":\"Project\""))
    }

    @Test
    fun `buildFilesSection handles single expanded holon`() {
        val overrides = mapOf("hkg:awakening-log" to CollapseState.EXPANDED)
        val files = HkgContextFormatter.buildFilesSection(fullMeridianContext, overrides, platform)

        assertTrue(files.contains("Files currently open: 1 of 7"))
        assertTrue(files.contains("START OF FILE awakening-log.json"))
    }
}