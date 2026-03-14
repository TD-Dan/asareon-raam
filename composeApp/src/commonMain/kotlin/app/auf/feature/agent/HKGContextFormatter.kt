package app.auf.feature.agent

import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import kotlinx.serialization.json.*

/**
 * Pipeline-level utility for transforming raw HKG context into the two-partition
 * view described in §4 of the Sovereign Stabilization design document.
 *
 * **INDEX partition** — navigational map of the entire knowledge graph tree.
 * Always present. Uses 2-space indentation per depth level. Shows collapse state
 * badges, sub-holon count badges for collapsed branches, and holon summaries.
 *
 * **FILES partition** — complete JSON files for all EXPANDED holons. Participates
 * in the context budget algorithm (Phase D). Default: all files closed.
 *
 * Lives in `app.auf.feature.agent` (pipeline package), NOT in `strategies`.
 * Consumed by strategies that need HKG awareness (HKGStrategy, SovereignStrategy).
 * Each strategy duplicates its own call sites per §2.3 (absolute decoupling).
 */
object HkgContextFormatter {

    private const val LOG_TAG = "HkgContextFormatter"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // =========================================================================
    // Data classes
    // =========================================================================

    /**
     * Lightweight summary extracted from a holon's header JSON.
     * Used to build the INDEX tree without loading full file content.
     */
    data class HolonSummary(
        val id: String,
        val type: String,
        val name: String,
        val summary: String?,
        val subHolonRefs: List<SubRef>,
        val depth: Int,
        /** The parent holon's ID, null for root. Derived from tree structure. */
        val parentId: String?
    )

    data class SubRef(
        val id: String,
        val type: String?,
        val summary: String?
    )

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Parse holon headers from the raw HKG context map.
     *
     * The input is the `transientHkgContext` JsonObject where each key is a holon ID
     * and each value is the holon's raw JSON string (the complete file content as
     * returned by [KnowledgeGraphFeature.buildContextForPersona]).
     *
     * Returns a map of holon ID → [HolonSummary] with tree depth computed from
     * parent-child relationships.
     */
    fun parseHolonHeaders(
        hkgContext: JsonObject,
        platformDependencies: PlatformDependencies? = null
    ): Map<String, HolonSummary> {
        val rawSummaries = mutableMapOf<String, HolonSummary>()

        for ((holonId, rawValue) in hkgContext) {
            try {
                val rawContent = rawValue.jsonPrimitive.content
                val holonJson = json.parseToJsonElement(rawContent).jsonObject
                val header = holonJson["header"]?.jsonObject ?: continue

                val id = header["id"]?.jsonPrimitive?.contentOrNull ?: holonId
                val type = header["type"]?.jsonPrimitive?.contentOrNull ?: "Unknown"
                val name = header["name"]?.jsonPrimitive?.contentOrNull ?: id
                val summary = header["summary"]?.jsonPrimitive?.contentOrNull

                val subHolons = header["sub_holons"]?.jsonArray?.mapNotNull { subEl ->
                    try {
                        val sub = subEl.jsonObject
                        val subId = sub["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        SubRef(
                            id = subId,
                            type = sub["type"]?.jsonPrimitive?.contentOrNull,
                            summary = sub["summary"]?.jsonPrimitive?.contentOrNull
                        )
                    } catch (_: Exception) { null }
                } ?: emptyList()

                rawSummaries[id] = HolonSummary(
                    id = id,
                    type = type,
                    name = name,
                    summary = summary,
                    subHolonRefs = subHolons,
                    depth = 0, // computed below
                    parentId = null // computed below
                )
            } catch (e: Exception) {
                platformDependencies?.log(
                    LogLevel.WARN, LOG_TAG,
                    "Failed to parse header for holon '$holonId': ${e.message}"
                )
            }
        }

        // Build parent map and compute depths
        val parentMap = mutableMapOf<String, String>() // childId → parentId
        for ((parentId, summary) in rawSummaries) {
            for (subRef in summary.subHolonRefs) {
                parentMap[subRef.id] = parentId
            }
        }

        // Find roots (holons with no parent in the set)
        val roots = rawSummaries.keys.filter { it !in parentMap }

        // BFS to assign depths and parentId
        val result = mutableMapOf<String, HolonSummary>()
        data class QueueEntry(val id: String, val depth: Int, val parentId: String?)
        val queue = ArrayDeque<QueueEntry>()
        roots.forEach { queue.add(QueueEntry(it, 0, null)) }
        val visited = mutableSetOf<String>()

        while (queue.isNotEmpty()) {
            val entry = queue.removeFirst()
            if (entry.id in visited) continue
            visited.add(entry.id)

            val summary = rawSummaries[entry.id] ?: continue
            result[entry.id] = summary.copy(depth = entry.depth, parentId = entry.parentId)

            for (subRef in summary.subHolonRefs) {
                if (subRef.id !in visited && subRef.id in rawSummaries) {
                    queue.add(QueueEntry(subRef.id, entry.depth + 1, entry.id))
                }
            }
        }

        // Add any orphans not reached by BFS (shouldn't happen with well-formed HKGs)
        for ((id, summary) in rawSummaries) {
            if (id !in result) {
                result[id] = summary.copy(depth = 0, parentId = null)
            }
        }

        return result
    }

    /**
     * Count sub-holons recursively for badge display.
     * Includes children, grandchildren, etc. — everything under the given holon.
     */
    fun countSubHolons(holonId: String, headers: Map<String, HolonSummary>): Int {
        val summary = headers[holonId] ?: return 0
        var count = summary.subHolonRefs.size
        for (subRef in summary.subHolonRefs) {
            if (subRef.id in headers) {
                count += countSubHolons(subRef.id, headers)
            }
        }
        return count
    }

    /**
     * Build the INDEX tree string.
     *
     * Rules from §4.2:
     * - COLLAPSED branch: holon ID + type + summary + `<contains N sub-holons>` badge.
     *   All children hidden.
     * - EXPANDED branch (in INDEX): shows its immediate children (with summaries).
     *   Children themselves may be COLLAPSED.
     * - `[EXPANDED]` tag means the holon's file is open in FILES.
     * - `[COLLAPSED]` means the file is not open.
     *
     * @param headers Parsed holon headers from [parseHolonHeaders].
     * @param collapseOverrides Agent's sticky collapse overrides. Key = "hkg:<holonId>".
     * @param personaName Display name of the persona (for the header line).
     */
    fun buildIndexTree(
        headers: Map<String, HolonSummary>,
        collapseOverrides: Map<String, CollapseState>,
        personaName: String? = null
    ): String {
        if (headers.isEmpty()) {
            return "--- HOLON_KNOWLEDGE_GRAPH_INDEX ---\nNo knowledge graph loaded.\n--- END OF HOLON_KNOWLEDGE_GRAPH_INDEX ---"
        }

        // Find root(s)
        val roots = headers.values.filter { it.parentId == null }.sortedBy { it.id }
        val displayName = personaName ?: roots.firstOrNull()?.name ?: "Unknown"
        val totalHolons = headers.size

        return buildString {
            appendLine("--- HOLON_KNOWLEDGE_GRAPH_INDEX ---")
            appendLine("Persona: $displayName | Total holons: $totalHolons")
            appendLine()

            for (root in roots) {
                appendHolonToIndex(root, headers, collapseOverrides, this)
            }

            appendLine("--- END OF HOLON_KNOWLEDGE_GRAPH_INDEX ---")
        }.trimEnd() // Remove trailing newline for clean joining
    }

    /**
     * Build the FILES section string (expanded holons only).
     *
     * @param hkgContext Raw HKG context map (holonId → raw JSON content string).
     * @param collapseOverrides Agent's sticky collapse overrides.
     */
    fun buildFilesSection(
        hkgContext: JsonObject,
        collapseOverrides: Map<String, CollapseState>
    ): String {
        val expandedIds = hkgContext.keys.filter { holonId ->
            resolveCollapseState(holonId, collapseOverrides) == CollapseState.EXPANDED
        }.sorted()

        return buildString {
            appendLine("--- HOLON_KNOWLEDGE_GRAPH_FILES ---")

            if (expandedIds.isEmpty()) {
                appendLine("No files open. Use agent.CONTEXT_UNCOLLAPSE to open holon files.")
            } else {
                appendLine("Files currently open: ${expandedIds.size} of ${hkgContext.size}")
                appendLine()

                for (holonId in expandedIds) {
                    val rawContent = hkgContext[holonId]?.jsonPrimitive?.contentOrNull ?: continue
                    appendLine("--- START OF FILE $holonId.json ---")
                    appendLine(rawContent)
                    appendLine("--- END OF FILE $holonId.json ---")
                    appendLine()
                }
            }

            appendLine("--- END OF HOLON_KNOWLEDGE_GRAPH_FILES ---")
        }.trimEnd()
    }

    /**
     * Resolves the effective collapse state for a holon.
     *
     * Default is COLLAPSED (§4.1: "Default: all files closed").
     * Overrides use the "hkg:<holonId>" key convention.
     */
    fun resolveCollapseState(
        holonId: String,
        collapseOverrides: Map<String, CollapseState>
    ): CollapseState {
        return collapseOverrides["hkg:$holonId"] ?: CollapseState.COLLAPSED
    }

    // =========================================================================
    // Internal tree rendering
    // =========================================================================

    private fun appendHolonToIndex(
        holon: HolonSummary,
        headers: Map<String, HolonSummary>,
        collapseOverrides: Map<String, CollapseState>,
        sb: StringBuilder
    ) {
        val indent = "  ".repeat(holon.depth)
        val collapseState = resolveCollapseState(holon.id, collapseOverrides)
        val tag = if (collapseState == CollapseState.EXPANDED) "[EXPANDED]" else "[COLLAPSED]"

        // Holon header line
        sb.appendLine("${indent}${holon.id} (${holon.type}) — \"${holon.name}\" $tag")

        // Summary (indented under the header)
        if (!holon.summary.isNullOrBlank()) {
            sb.appendLine("${indent}  ${holon.summary}")
        }

        if (collapseState == CollapseState.COLLAPSED) {
            // Collapsed: show sub-holon count badge if any, hide children
            val totalSubs = countSubHolons(holon.id, headers)
            if (totalSubs > 0) {
                sb.appendLine("${indent}  <contains $totalSubs sub-holons>")
            }
        } else {
            // Expanded in INDEX: show immediate children with their own collapse state
            sb.appendLine() // Visual separator
            for (subRef in holon.subHolonRefs) {
                val childSummary = headers[subRef.id]
                if (childSummary != null) {
                    appendHolonToIndex(childSummary, headers, collapseOverrides, sb)
                } else {
                    // Sub-holon referenced but not in the loaded set
                    val childIndent = "  ".repeat(holon.depth + 1)
                    sb.appendLine("${childIndent}${subRef.id} (${subRef.type ?: "Unknown"}) — not loaded")
                }
            }
        }

        sb.appendLine() // Blank line after each holon block
    }
}