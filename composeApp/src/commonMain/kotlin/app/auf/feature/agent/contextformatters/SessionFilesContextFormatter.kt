package app.auf.feature.agent.contextformatters

import app.auf.feature.agent.CollapseState
import app.auf.feature.agent.PromptSection
import app.auf.util.PlatformDependencies
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pipeline-level utility for transforming session workspace file listings
 * into context partitions for injection into the agent's system prompt.
 *
 * Mirrors [WorkspaceContextFormatter]'s architecture but adapted for
 * cross-sandbox session workspace files accessed via delegation.
 *
 * ## Key Prefix Convention
 *
 * All partition keys use the `sf:<sessionHandle>:<relativePath>` format:
 * - `sf:session.pet-studies:readme.md` — a file
 * - `sf:session.pet-studies:src/` — a directory
 *
 * The top-level Group key for each session's files is `SESSION_FILES:<sessionHandle>`.
 *
 * ## Collapse Model
 *
 * Follows the same two-axis model as [WorkspaceContextFormatter]:
 * - Directories: EXPANDED = children visible, COLLAPSED = summary badge.
 * - Files: EXPANDED = content loaded, COLLAPSED = listed only.
 * - Default state: COLLAPSED for all entries.
 *
 * ## Design Decisions
 *
 * - Pure function — no state, no side effects.
 * - Pipeline-level utility, not strategy-owned (§2.3 absolute decoupling).
 * - Reuses [WorkspaceContextFormatter.WorkspaceEntry] data class for
 *   parsed listing entries (identical structure).
 */
object SessionFilesContextFormatter {

    private const val LOG_TAG = "SessionFilesContextFormatter"

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Parse session workspace entries from the raw filesystem listing payload.
     *
     * The input is the `listing` JsonArray from a `session.RETURN_WORKSPACE_FILES`
     * response, where each element has `path` (String) and `isDirectory` (Boolean).
     * Paths are relative to the session sandbox root (e.g., "{uuid}/workspace/oak.txt").
     *
     * @param listingArray The raw listing JsonArray.
     * @param sessionUUID The session's UUID (used to compute the workspace prefix).
     * @param platformDependencies For logging.
     * @return A list of [WorkspaceContextFormatter.WorkspaceEntry] with depth and parent.
     */
    fun parseListingEntries(
        listingArray: JsonArray,
        sessionUUID: String,
        platformDependencies: PlatformDependencies? = null
    ): List<WorkspaceContextFormatter.WorkspaceEntry> {
        // Session workspace paths: "{sessionUUID}/workspace/{file}"
        // We need to strip the prefix to get relative paths.
        val workspacePrefix = "$sessionUUID/workspace"
        return WorkspaceContextFormatter.parseListingEntries(
            listingArray, workspacePrefix, platformDependencies
        )
    }

    /**
     * Builds a per-session SESSION_FILES Group containing the session's
     * workspace file tree as collapsible children.
     *
     * ## Structure
     *
     * ```
     * Group("SESSION_FILES:session.pet-studies")
     *   ├─ Section("sf:session.pet-studies:readme.md")
     *   └─ Group("sf:session.pet-studies:src/")
     *        └─ Section("sf:session.pet-studies:src/main.kt")
     * ```
     *
     * @param sessionHandle The session's handle (e.g., "session.pet-studies").
     * @param sessionName The session's display name.
     * @param entries Parsed workspace entries from [parseListingEntries].
     * @param expandedFileContents Map of relative path → file content for files
     *   that have been fetched (keyed by relative path, not sf:-prefixed).
     * @param collapseOverrides Agent's sticky collapse overrides.
     * @param platformDependencies For logging.
     */
    fun buildSessionFilesGroup(
        sessionHandle: String,
        sessionName: String,
        entries: List<WorkspaceContextFormatter.WorkspaceEntry>,
        expandedFileContents: Map<String, String>,
        collapseOverrides: Map<String, CollapseState>,
        platformDependencies: PlatformDependencies? = null
    ): PromptSection.Group {
        val groupKey = "SESSION_FILES:$sessionHandle"
        val keyPrefix = "sf:$sessionHandle:"

        val childrenMap = buildChildrenMap(entries)
        val rootEntries = (childrenMap[null] ?: emptyList())
            .sortedWith(compareBy({ !it.isDirectory }, { it.name }))

        val totalFiles = entries.count { !it.isDirectory }
        val totalDirs = entries.count { it.isDirectory }
        val expandedCount = entries.count { entry ->
            !entry.isDirectory &&
                    resolveCollapseState(sessionHandle, entry.relativePath, collapseOverrides) == CollapseState.EXPANDED
        }

        val contentDesc = buildString {
            if (totalFiles > 0) append("$totalFiles file${if (totalFiles != 1) "s" else ""}")
            if (totalDirs > 0) {
                if (totalFiles > 0) append(", ")
                append("$totalDirs director${if (totalDirs != 1) "ies" else "y"}")
            }
        }.ifEmpty { "empty" }

        return PromptSection.Group(
            key = groupKey,
            header = "Session workspace '$sessionName': $contentDesc | $expandedCount files open",
            children = rootEntries.map { entry ->
                buildEntrySection(entry, entries, childrenMap, expandedFileContents, sessionHandle, platformDependencies)
            },
            isCollapsible = true,
            priority = 10,
            collapsedSummary = "[Session '$sessionName' workspace collapsed — $contentDesc. " +
                    "Use agent.CONTEXT_UNCOLLAPSE to open.]",
            defaultCollapsed = true
        )
    }

    /**
     * Builds the SESSION_FILES index tree string for a single session.
     * Used by the pipeline for the navigational index partition.
     *
     * @param sessionHandle The session's handle.
     * @param sessionName The session's display name.
     * @param entries Parsed workspace entries.
     * @param collapseOverrides Agent's sticky collapse overrides.
     */
    fun buildIndexTree(
        sessionHandle: String,
        sessionName: String,
        entries: List<WorkspaceContextFormatter.WorkspaceEntry>,
        collapseOverrides: Map<String, CollapseState>
    ): String {
        if (entries.isEmpty()) {
            return "Session '$sessionName' workspace is empty."
        }

        val totalFiles = entries.count { !it.isDirectory }
        val totalDirs = entries.count { it.isDirectory }
        val expandedFileCount = entries.count { !it.isDirectory &&
                resolveCollapseState(sessionHandle, it.relativePath, collapseOverrides) == CollapseState.EXPANDED
        }

        return buildString {
            appendLine("Session '$sessionName' workspace: $totalFiles files, $totalDirs directories | $expandedFileCount files open")
            appendLine()

            val childrenMap = buildChildrenMap(entries)
            val rootEntries = entries.filter { it.parentPath == null }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name }))

            for (entry in rootEntries) {
                appendEntryToIndex(entry, entries, childrenMap, collapseOverrides, sessionHandle, this)
            }
        }.trimEnd()
    }

    /**
     * Resolves the effective collapse state for a session file entry.
     * Default is COLLAPSED. Override key format: "sf:<sessionHandle>:<relativePath>".
     */
    fun resolveCollapseState(
        sessionHandle: String,
        relativePath: String,
        collapseOverrides: Map<String, CollapseState>
    ): CollapseState {
        return collapseOverrides["sf:$sessionHandle:$relativePath"] ?: CollapseState.COLLAPSED
    }

    /**
     * Identifies all files that are marked EXPANDED in the collapse overrides
     * for a given session. Used to determine which file reads to request during
     * context gathering.
     *
     * @param sessionHandle The session's handle.
     * @param entries Parsed workspace entries (to validate keys reference real files).
     * @param collapseOverrides Agent's sticky collapse overrides.
     * @return Set of relative file paths that need content fetched.
     */
    fun getExpandedFilePaths(
        sessionHandle: String,
        entries: List<WorkspaceContextFormatter.WorkspaceEntry>,
        collapseOverrides: Map<String, CollapseState>
    ): Set<String> {
        val keyPrefix = "sf:$sessionHandle:"
        val filePaths = entries.filter { !it.isDirectory }.map { it.relativePath }.toSet()
        return collapseOverrides
            .filter { (key, state) ->
                state == CollapseState.EXPANDED &&
                        key.startsWith(keyPrefix) &&
                        key.removePrefix(keyPrefix) in filePaths
            }
            .keys
            .map { it.removePrefix(keyPrefix) }
            .filter { it.isNotBlank() } // Filter out empty paths (malformed keys)
            .toSet()
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Builds a parent→children map for efficient tree traversal.
     */
    private fun buildChildrenMap(
        entries: List<WorkspaceContextFormatter.WorkspaceEntry>
    ): Map<String?, List<WorkspaceContextFormatter.WorkspaceEntry>> {
        return entries.groupBy { it.parentPath }
    }

    /**
     * Recursively builds a [PromptSection] for a single session file entry.
     */
    private fun buildEntrySection(
        entry: WorkspaceContextFormatter.WorkspaceEntry,
        allEntries: List<WorkspaceContextFormatter.WorkspaceEntry>,
        childrenMap: Map<String?, List<WorkspaceContextFormatter.WorkspaceEntry>>,
        expandedFileContents: Map<String, String>,
        sessionHandle: String,
        platformDependencies: PlatformDependencies?
    ): PromptSection {
        val keyPrefix = "sf:$sessionHandle:"

        return if (entry.isDirectory) {
            val dirChildren = (childrenMap[entry.relativePath] ?: emptyList())
                .sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            val itemCount = WorkspaceContextFormatter.countItemsUnder(entry.relativePath, allEntries)

            PromptSection.Group(
                key = "$keyPrefix${entry.relativePath}",
                header = "",
                children = dirChildren.map { child ->
                    buildEntrySection(child, allEntries, childrenMap, expandedFileContents, sessionHandle, platformDependencies)
                },
                isCollapsible = true,
                priority = 10,
                collapsedSummary = "[${entry.name}/ — $itemCount items. Use agent.CONTEXT_UNCOLLAPSE to expand.]",
                defaultCollapsed = true
            )
        } else {
            val content = expandedFileContents[entry.relativePath]
                ?: "[File not loaded. Use agent.CONTEXT_UNCOLLAPSE to open.]"

            PromptSection.Section(
                key = "$keyPrefix${entry.relativePath}",
                content = content,
                isProtected = false,
                isCollapsible = true,
                priority = 10,
                collapsedSummary = "[${entry.name} — file closed]",
                defaultCollapsed = true
            )
        }
    }

    /**
     * Recursively appends an entry (and its visible children) to the index tree.
     */
    private fun appendEntryToIndex(
        entry: WorkspaceContextFormatter.WorkspaceEntry,
        allEntries: List<WorkspaceContextFormatter.WorkspaceEntry>,
        childrenMap: Map<String?, List<WorkspaceContextFormatter.WorkspaceEntry>>,
        collapseOverrides: Map<String, CollapseState>,
        sessionHandle: String,
        sb: StringBuilder,
        indentLevel: Int = 0
    ) {
        val indent = "  ".repeat(indentLevel)
        val state = resolveCollapseState(sessionHandle, entry.relativePath, collapseOverrides)
        val badge = if (state == CollapseState.EXPANDED) "[EXPANDED]" else "[COLLAPSED]"

        if (entry.isDirectory) {
            val itemCount = WorkspaceContextFormatter.countItemsUnder(entry.relativePath, allEntries)
            if (state == CollapseState.COLLAPSED) {
                sb.appendLine("$indent${entry.name}/ $badge <contains $itemCount items>")
            } else {
                sb.appendLine("$indent${entry.name}/ $badge")
                val children = (childrenMap[entry.relativePath] ?: emptyList())
                    .sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                for (child in children) {
                    appendEntryToIndex(child, allEntries, childrenMap, collapseOverrides, sessionHandle, sb, indentLevel + 1)
                }
            }
        } else {
            sb.appendLine("$indent${entry.name} $badge")
        }
    }
}