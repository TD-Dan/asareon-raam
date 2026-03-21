package app.auf.feature.agent.contextformatters

import app.auf.feature.agent.CollapseState
import app.auf.feature.agent.PromptSection
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import kotlinx.serialization.json.*
import kotlin.collections.iterator

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
                val header = holonJson["header"]?.jsonObject
                if (header == null) {
                    platformDependencies?.log(
                        LogLevel.WARN, LOG_TAG,
                        "Holon '$holonId' has valid JSON but no 'header' object. Skipping."
                    )
                    continue
                }

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
                    } catch (e: Exception) {
                        platformDependencies?.log(
                            LogLevel.WARN, LOG_TAG,
                            "Malformed sub_holon entry in holon '$holonId': ${e.message}. Skipping entry."
                        )
                        null
                    }
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
            return "No knowledge graph loaded."
        }

        // Find root(s)
        val roots = headers.values.filter { it.parentId == null }.sortedBy { it.id }
        val displayName = personaName ?: roots.firstOrNull()?.name ?: "Unknown"
        val totalHolons = headers.size

        return buildString {
            appendLine("Persona: $displayName | Total holons: $totalHolons")
            appendLine()

            for (root in roots) {
                appendHolonToIndex(root, headers, collapseOverrides, this)
            }
        }.trimEnd()
    }

    /**
     * Build the FILES section as a [app.auf.feature.agent.PromptSection.Group] whose internal structure
     * mirrors the actual holon tree. Branch holons become nested [app.auf.feature.agent.PromptSection.Group]s;
     * leaf holons become [app.auf.feature.agent.PromptSection.Section]s.
     *
     * The child key convention is `hkg:<holonId>`, matching the existing
     * `contextCollapseOverrides` key space used by CONTEXT_COLLAPSE/UNCOLLAPSE.
     *
     * ## Tree rendering example
     *
     * ```
     * Group("HOLON_KNOWLEDGE_GRAPH_FILES")
     *   └─ Group("hkg:persona-root")                  ← branch holon (has sub-holons)
     *        header = <persona root file content>
     *        ├─ Section("hkg:memory-bank")             ← leaf holon
     *        └─ Group("hkg:skills")                    ← branch holon
     *             header = <skills file content>
     *             ├─ Section("hkg:skill-writing")      ← leaf
     *             └─ Section("hkg:skill-analysis")     ← leaf
     * ```
     *
     * Collapsing `hkg:skills` in the Context Manager hides its children and their
     * token cost. The budget algorithm can shed entire subtrees.
     *
     * All holons default to COLLAPSED (`defaultCollapsed = true`). The agent opens
     * them via CONTEXT_UNCOLLAPSE which controls both the INDEX tree visibility
     * and the FILES structural collapse.
     *
     * @param hkgContext Raw HKG context map (holonId → raw JSON content string).
     * @param headers Parsed holon headers with tree structure from [parseHolonHeaders].
     * @param collapseOverrides Agent's sticky collapse overrides.
     * @param platformDependencies For logging.
     */
    /**
     * Recursively builds a [app.auf.feature.agent.PromptSection] for a single holon.
     *
     * - **Leaf holon** (no children in the loaded set) → [app.auf.feature.agent.PromptSection.Section]
     * - **Branch holon** (has children in the loaded set) → [app.auf.feature.agent.PromptSection.Group]
     *   whose `header` is the holon's own file content and whose `children` are
     *   the recursive results of its sub-holons.
     *
     * @param isRoot If true, this holon is marked protected (always visible, never
     *   auto-collapsed by the budget algorithm, expanded by default). Only passed
     *   for the root-level call — recursive children are always non-root.
     */
    private fun buildHolonSection(
        summary: HolonSummary,
        hkgContext: JsonObject,
        headers: Map<String, HolonSummary>,
        platformDependencies: PlatformDependencies?,
        isRoot: Boolean = false
    ): PromptSection {
        val holonId = summary.id
        val rawContent = hkgContext[holonId]?.jsonPrimitive?.contentOrNull
        if (rawContent == null) {
            platformDependencies?.log(
                LogLevel.WARN, LOG_TAG,
                "Holon '$holonId' content could not be read as a string."
            )
        }
        val content = rawContent ?: "[Content not loaded for holon '$holonId']"

        // Resolve children that are actually present in the loaded header set
        val childSummaries = summary.subHolonRefs
            .mapNotNull { headers[it.id] }
            .sortedBy { it.id }

        return if (childSummaries.isEmpty()) {
            // Leaf holon → Section
            PromptSection.Section(
                key = "hkg:$holonId",
                content = content,
                isProtected = isRoot,
                isCollapsible = !isRoot,
                priority = if (isRoot) 1000 else 0,
                collapsedSummary = "[${summary.type}: \"${summary.name}\" — file closed]",
                defaultCollapsed = !isRoot
            )
        } else {
            // Branch holon → Group
            val totalSubs = countSubHolons(holonId, headers)
            PromptSection.Group(
                key = "hkg:$holonId",
                header = content,
                children = childSummaries.map { child ->
                    buildHolonSection(child, hkgContext, headers, platformDependencies)
                },
                isProtected = isRoot,
                isCollapsible = !isRoot,
                priority = if (isRoot) 1000 else 0,
                collapsedSummary = "[${summary.type}: \"${summary.name}\" — $totalSubs sub-holons collapsed]",
                defaultCollapsed = !isRoot
            )
        }
    }

    /**
     * Builds a single unified `HOLON_KNOWLEDGE_GRAPH` [PromptSection.Group] that
     * consolidates INDEX, holon file tree, and NAVIGATION into one coherent structure.
     *
     * Replaces the former three-partition pattern:
     * - `HOLON_KNOWLEDGE_GRAPH_INDEX` (flat Section)
     * - `HOLON_KNOWLEDGE_GRAPH_FILES` (Group with tree)
     * - `HKG NAVIGATION` (strategy-owned Section)
     *
     * ## Structure
     *
     * ```
     * Group("HOLON_KNOWLEDGE_GRAPH")
     *   ├─ Section("HOLON_KNOWLEDGE_GRAPH:INDEX")      ← protected, always visible
     *   ├─ Group("hkg:persona-root")                   ← recursive holon tree
     *   │    ├─ Section("hkg:memory-bank")
     *   │    └─ Group("hkg:skills") ...
     *   └─ Section("HOLON_KNOWLEDGE_GRAPH:NAVIGATION") ← protected, always visible
     * ```
     *
     * @param hkgContext Raw HKG context map (holonId → raw JSON content string).
     * @param headers Parsed holon headers with tree structure from [parseHolonHeaders].
     * @param collapseOverrides Agent's sticky collapse overrides.
     * @param personaName Display name for the INDEX header line.
     * @param platformDependencies For logging.
     */
    fun buildUnifiedSection(
        hkgContext: JsonObject,
        headers: Map<String, HolonSummary>,
        collapseOverrides: Map<String, CollapseState>,
        personaName: String? = null,
        protectRoots: Boolean = true,
        platformDependencies: PlatformDependencies? = null
    ): PromptSection.Group {
        val roots = headers.values
            .filter { it.parentId == null }
            .sortedBy { it.id }

        val expandedCount = hkgContext.keys.count { holonId ->
            resolveCollapseState(holonId, collapseOverrides) == CollapseState.EXPANDED
        }

        // Header = INDEX tree + NAVIGATION commands (always visible with the Group)
        val header = buildString {
            append(buildIndexTree(headers, collapseOverrides, personaName))
            appendLine()
            appendLine()
            append(buildNavigationContent())
        }

        // Children = only the holon file tree
        val children = roots.map { root ->
            buildHolonSection(root, hkgContext, headers, platformDependencies, isRoot = protectRoots)
        }

        return PromptSection.Group(
            key = "HOLON_KNOWLEDGE_GRAPH",
            header = header,
            children = children,
            isProtected = true,
            isCollapsible = false,
            priority = 1000,
            collapsedSummary = "[Knowledge Graph collapsed — ${headers.size} holons. " +
                    "Use agent.CONTEXT_UNCOLLAPSE with \"hkg:<holonId>\" to navigate.]"
        )
    }

    /**
     * Builds the HKG navigation command reference content.
     * HKG navigation command reference. Included in the unified HOLON_KNOWLEDGE_GRAPH
     * that owns the HKG context structure.
     */
    private fun buildNavigationContent(): String = buildString {
        appendLine("Your Knowledge Graph is presented as an INDEX (tree overview) and holon files.")
        appendLine("By default, all files are closed. Use these commands to navigate:")
        appendLine()
        appendLine("Open a single holon file:")
        appendLine("```auf_agent.CONTEXT_UNCOLLAPSE")
        appendLine("""{ "partitionKey": "hkg:<holonId>", "scope": "single" }""")
        appendLine("```")
        appendLine()
        appendLine("Open a holon and all its children:")
        appendLine("```auf_agent.CONTEXT_UNCOLLAPSE")
        appendLine("""{ "partitionKey": "hkg:<holonId>", "scope": "subtree" }""")
        appendLine("```")
        appendLine()
        appendLine("Close a holon file:")
        appendLine("```auf_agent.CONTEXT_COLLAPSE")
        appendLine("""{ "partitionKey": "hkg:<holonId>" }""")
        appendLine("```")
        appendLine()
        appendLine("IMPORTANT: You must expand a holon file before writing to it.")
        appendLine("The system will block writes to collapsed holons to prevent data loss.")
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