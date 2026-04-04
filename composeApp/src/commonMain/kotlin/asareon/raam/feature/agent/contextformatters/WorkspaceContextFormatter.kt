package app.auf.feature.agent.contextformatters

import app.auf.feature.agent.CollapseState
import app.auf.feature.agent.PromptSection
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pipeline-level utility for transforming raw workspace file listings into a
 * consolidated context partition for agent context injection.
 *
 * **WORKSPACE_FILES** — a single Group partition whose header contains:
 * 1. Explanatory text about workspace file ownership and permissions
 * 2. A navigational index tree with collapse state badges
 *
 * The Group's children are the actual file contents (for EXPANDED files)
 * and directory sub-Groups, participating in the context budget algorithm.
 * Default: all files closed.
 *
 * ## Two-Axis Collapse Model
 *
 * The workspace uses a unified key space (`ws:<relativePath>`) with type-aware
 * interpretation:
 *
 * - **Directories** (`ws:src/`): EXPANDED = children visible in INDEX tree.
 *   COLLAPSED = shows `<contains N items>` badge, children hidden.
 * - **Files** (`ws:src/main.kt`): EXPANDED = content loaded into WORKSPACE_FILES.
 *   COLLAPSED = listed in INDEX with no content.
 *
 * ## Scope Semantics (for CONTEXT_UNCOLLAPSE)
 *
 * - `single` on a directory: expand that one directory (show immediate children)
 * - `single` on a file: open that file's content
 * - `subtree` on a directory: expand that directory AND all nested sub-directories
 *   recursively, but do NOT expand any files (tree navigation only)
 * - `full`: expand all directories and all files
 *
 * ## Default State
 *
 * Root workspace directory is EXPANDED (agent sees top-level contents).
 * Everything else defaults to COLLAPSED.
 *
 * Lives in `app.auf.feature.agent` (pipeline package). Consumed by strategies that
 * need workspace awareness. Each strategy duplicates its own call sites per §2.3.
 */
object WorkspaceContextFormatter {

    private const val LOG_TAG = "WorkspaceContextFormatter"

    // =========================================================================
    // Data classes
    // =========================================================================

    /**
     * Lightweight representation of a workspace entry parsed from the filesystem
     * listing response. Used to build the INDEX tree.
     */
    data class WorkspaceEntry(
        /** Relative path from the workspace root (e.g., "src/main.kt", "src/util/") */
        val relativePath: String,
        val name: String,
        val isDirectory: Boolean,
        val depth: Int,
        /** Parent directory's relative path, null for root-level entries. */
        val parentPath: String?
    )

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Parse workspace entries from the raw filesystem listing payload.
     *
     * The input is the `listing` JsonArray from a `filesystem.RETURN_LIST` response,
     * where each element has `path` (String) and `isDirectory` (Boolean).
     * The listing is expected to be from a recursive workspace listing where paths
     * are relative to the agent sandbox root (e.g., "{agentId}/workspace/src/main.kt").
     *
     * @param listingArray The raw listing JsonArray from the filesystem response.
     * @param workspacePrefix The path prefix to strip (e.g., "{agentId}/workspace").
     *   Entries not under this prefix are ignored.
     * @param platformDependencies For logging.
     * @return A list of [WorkspaceEntry] with depth and parent computed from path structure.
     */
    fun parseListingEntries(
        listingArray: JsonArray,
        workspacePrefix: String,
        platformDependencies: PlatformDependencies? = null
    ): List<WorkspaceEntry> {
        val normalizedPrefix = workspacePrefix.trimEnd('/').replace("\\", "/")
        val entries = mutableListOf<WorkspaceEntry>()

        for (element in listingArray) {
            try {
                val obj = element.jsonObject
                val rawPath = obj["path"]?.jsonPrimitive?.contentOrNull ?: continue
                val isDirectory = obj["isDirectory"]?.jsonPrimitive?.booleanOrNull ?: false

                val normalizedPath = rawPath.replace("\\", "/")

                // Strip the workspace prefix to get a relative path
                val relativePath = normalizedPath
                    .removePrefix(normalizedPrefix)
                    .removePrefix("/")

                if (relativePath.isBlank()) continue // Skip the workspace root itself

                // Normalize: directories always end with "/", files never do
                val canonicalPath = if (isDirectory) {
                    if (relativePath.endsWith("/")) relativePath else "$relativePath/"
                } else {
                    relativePath.trimEnd('/')
                }

                val name = canonicalPath.trimEnd('/').substringAfterLast('/')
                val parentPath = computeParentPath(canonicalPath)
                val depth = canonicalPath.trimEnd('/').count { it == '/' }

                entries.add(
                    WorkspaceEntry(
                        relativePath = canonicalPath,
                        name = name,
                        isDirectory = isDirectory,
                        depth = depth,
                        parentPath = parentPath
                    )
                )
            } catch (e: Exception) {
                platformDependencies?.log(
                    LogLevel.WARN, LOG_TAG,
                    "Failed to parse workspace listing entry: ${e.message}. Skipping."
                )
            }
        }

        return entries.sortedWith(compareBy({ it.depth }, { !it.isDirectory }, { it.relativePath }))
    }

    /**
     * Count items recursively under a directory path for badge display.
     * Includes files, sub-directories, and all nested descendants.
     */
    fun countItemsUnder(directoryPath: String, entries: List<WorkspaceEntry>): Int {
        val normalizedDir = if (directoryPath.endsWith("/")) directoryPath else "$directoryPath/"
        return entries.count { it.relativePath != normalizedDir && it.relativePath.startsWith(normalizedDir) }
    }

    /**
     * Build the workspace directory index tree string.
     *
     * Produces a navigational tree showing the workspace directory structure with
     * collapse state badges. Embedded in the WORKSPACE_FILES group header by
     * [buildFilesSections] — no longer a standalone partition.
     *
     * Rules:
     * - COLLAPSED directory: shows name + `<contains N items>` badge. Children hidden.
     * - EXPANDED directory: shows its immediate children (each with their own state).
     * - `[EXPANDED]` on a file means its content is loaded.
     * - `[COLLAPSED]` on a file means it appears in the tree only (no content loaded).
     *
     * @param entries Parsed workspace entries from [parseListingEntries].
     * @param collapseOverrides Agent's sticky collapse overrides. Key = "ws:<relativePath>".
     */
    fun buildIndexTree(
        entries: List<WorkspaceEntry>,
        collapseOverrides: Map<String, CollapseState>
    ): String {
        if (entries.isEmpty()) {
            return "Workspace is empty."
        }

        return buildString {
            // Build a parent→children map for efficient tree traversal
            val childrenMap = buildChildrenMap(entries)

            // Render root-level entries (parentPath == null)
            val rootEntries = entries.filter { it.parentPath == null }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name }))

            for (entry in rootEntries) {
                appendEntryToIndex(entry, entries, childrenMap, collapseOverrides, this)
            }
        }.trimEnd()
    }

    /**
     * Build the WORKSPACE_FILES section as a [app.auf.feature.agent.PromptSection.Group] whose
     * header contains the navigational index tree and explanatory text, and whose
     * internal structure mirrors the actual directory tree. Directories become nested
     * [app.auf.feature.agent.PromptSection.Group]s; files become [app.auf.feature.agent.PromptSection.Section]s.
     *
     * This is the single consolidated partition for the agent's workspace — the index
     * tree and file contents live together. No separate WORKSPACE_INDEX partition.
     *
     * The child key convention is `ws:<relativePath>`, matching the existing
     * `contextCollapseOverrides` key space used by CONTEXT_COLLAPSE/UNCOLLAPSE.
     *
     * ## Tree rendering example
     *
     * ```
     * Group("WORKSPACE_FILES")
     *   ├─ Section("ws:config.yaml")                 ← root-level file
     *   └─ Group("ws:src/")                          ← directory
     *        ├─ Section("ws:src/main.kt")            ← file in src/
     *        └─ Group("ws:src/util/")                ← sub-directory
     *             └─ Section("ws:src/util/helpers.kt")
     * ```
     *
     * Collapsing `ws:src/` in the Context Manager hides the entire subtree.
     * The budget algorithm can shed entire directories.
     *
     * All entries default to COLLAPSED (`defaultCollapsed = true`). The agent opens
     * them via CONTEXT_UNCOLLAPSE. Root directories are auto-expanded by the pipeline
     * via `effectiveOverrides`.
     *
     * @param entries All workspace entries from [parseListingEntries] (files AND directories).
     * @param expandedFileContents Map of relative path → file content for files
     *   that have been fetched (content is available for rendering).
     * @param collapseOverrides Agent's sticky collapse overrides.
     * @param platformDependencies For logging.
     */
    fun buildFilesSections(
        entries: List<WorkspaceEntry>,
        expandedFileContents: Map<String, String>,
        collapseOverrides: Map<String, CollapseState>,
        canWrite: Boolean = true,
        platformDependencies: PlatformDependencies? = null
    ): PromptSection.Group {
        val childrenMap = buildChildrenMap(entries)

        // Build root-level children (parentPath == null)
        val rootEntries = (childrenMap[null] ?: emptyList())
            .sortedWith(compareBy({ !it.isDirectory }, { it.name }))

        val totalFiles = entries.count { !it.isDirectory }
        val totalDirs = entries.count { it.isDirectory }
        val expandedCount = entries.count { entry ->
            !entry.isDirectory && resolveCollapseState(entry.relativePath, collapseOverrides) == CollapseState.EXPANDED
        }

        // Build summary strings that describe files and/or directories
        val contentDesc = buildString {
            if (totalFiles > 0) append("$totalFiles file${if (totalFiles != 1) "s" else ""}")
            if (totalDirs > 0) {
                if (totalFiles > 0) append(", ")
                append("$totalDirs director${if (totalDirs != 1) "ies" else "y"}")
            }
        }.ifEmpty { "empty" }

        // Build the navigational index tree and include it in the group header
        val indexTree = buildIndexTree(entries, collapseOverrides)
        val permDesc = if (canWrite) "You have full read and write permissions." else "You have read-only access."
        val header = buildString {
            appendLine("Workspace files are your own files contained in your private agent sandbox. $permDesc Other participants cannot access these files.")
            appendLine()
            appendLine("$contentDesc | $expandedCount files open")
            appendLine()
            appendLine(indexTree)
        }.trimEnd()

        return PromptSection.Group(
            key = "WORKSPACE_FILES",
            header = header,
            children = rootEntries.map { entry ->
                buildEntrySection(entry, entries, childrenMap, expandedFileContents, platformDependencies)
            },
            isCollapsible = true,
            priority = 10,
            collapsedSummary = "[Workspace collapsed — $contentDesc. " +
                    "Use agent.CONTEXT_UNCOLLAPSE to open.]"
        )
    }

    /**
     * Recursively builds a [PromptSection] for a single workspace entry.
     *
     * - **Directory** → [PromptSection.Group] whose children are the directory's
     *   immediate contents (sub-directories and files), built recursively.
     * - **File** → [PromptSection.Section] with fetched content or a placeholder.
     */
    private fun buildEntrySection(
        entry: WorkspaceEntry,
        allEntries: List<WorkspaceEntry>,
        childrenMap: Map<String?, List<WorkspaceEntry>>,
        expandedFileContents: Map<String, String>,
        platformDependencies: PlatformDependencies?
    ): PromptSection {
        return if (entry.isDirectory) {
            // Directory → Group
            val dirChildren = (childrenMap[entry.relativePath] ?: emptyList())
                .sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            val itemCount = countItemsUnder(entry.relativePath, allEntries)

            PromptSection.Group(
                key = "ws:${entry.relativePath}",
                header = "",  // no own content for directories
                children = dirChildren.map { child ->
                    buildEntrySection(child, allEntries, childrenMap, expandedFileContents, platformDependencies)
                },
                isCollapsible = true,
                priority = 10,
                collapsedSummary = "[${entry.name}/ — $itemCount items. Use agent.CONTEXT_UNCOLLAPSE to expand.]",
                defaultCollapsed = true
            )
        } else {
            // File → Section
            val content = expandedFileContents[entry.relativePath]
                ?: "[File not loaded. Use agent.CONTEXT_UNCOLLAPSE to open.]"

            PromptSection.Section(
                key = "ws:${entry.relativePath}",
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
     * Resolves the effective collapse state for a workspace entry.
     *
     * Default is COLLAPSED — the agent must explicitly expand entries.
     * The sole exception is handled by the pipeline: the root workspace directory
     * (equivalent to HKG's root auto-expand) is seeded as EXPANDED in effective
     * overrides when building the context.
     *
     * Overrides use the "ws:<relativePath>" key convention.
     */
    fun resolveCollapseState(
        relativePath: String,
        collapseOverrides: Map<String, CollapseState>
    ): CollapseState {
        return collapseOverrides["ws:$relativePath"] ?: CollapseState.COLLAPSED
    }

    /**
     * Identifies all files that are marked EXPANDED in the collapse overrides.
     * Used by the pipeline to determine which file reads to dispatch during
     * context gathering.
     *
     * @param entries Parsed workspace entries (to validate that EXPANDED keys
     *   actually correspond to files in the listing, not stale references).
     * @param collapseOverrides Agent's sticky collapse overrides.
     * @return Set of relative file paths that need their content fetched.
     */
    fun getExpandedFilePaths(
        entries: List<WorkspaceEntry>,
        collapseOverrides: Map<String, CollapseState>
    ): Set<String> {
        val filePaths = entries.filter { !it.isDirectory }.map { it.relativePath }.toSet()
        return collapseOverrides
            .filter { (key, state) ->
                state == CollapseState.EXPANDED &&
                        key.startsWith("ws:") &&
                        key.removePrefix("ws:") in filePaths
            }
            .keys
            .map { it.removePrefix("ws:") }
            .toSet()
    }

    /**
     * Identifies all directory paths that would need to be expanded for a subtree
     * uncollapse operation. Used by the side-effect handler to dispatch individual
     * CONTEXT_UNCOLLAPSE actions for each sub-directory.
     *
     * @param directoryPath The root directory of the subtree (e.g., "src/").
     * @param entries Parsed workspace entries.
     * @return Set of directory relative paths under the target (inclusive).
     */
    fun getSubtreeDirectoryPaths(
        directoryPath: String,
        entries: List<WorkspaceEntry>
    ): Set<String> {
        val normalizedDir = if (directoryPath.endsWith("/")) directoryPath else "$directoryPath/"
        return entries
            .filter { it.isDirectory && (it.relativePath == normalizedDir || it.relativePath.startsWith(normalizedDir)) }
            .map { it.relativePath }
            .toSet()
    }

    // =========================================================================
    // Internal tree rendering
    // =========================================================================

    /**
     * Builds a map of parentPath → list of child entries for efficient tree traversal.
     */
    private fun buildChildrenMap(entries: List<WorkspaceEntry>): Map<String?, List<WorkspaceEntry>> {
        return entries.groupBy { it.parentPath }
    }

    private fun appendEntryToIndex(
        entry: WorkspaceEntry,
        allEntries: List<WorkspaceEntry>,
        childrenMap: Map<String?, List<WorkspaceEntry>>,
        collapseOverrides: Map<String, CollapseState>,
        sb: StringBuilder
    ) {
        val indent = "  ".repeat(entry.depth)
        val collapseState = resolveCollapseState(entry.relativePath, collapseOverrides)
        val tag = if (collapseState == CollapseState.EXPANDED) "[EXPANDED]" else "[COLLAPSED]"

        if (entry.isDirectory) {
            // Directory entry
            sb.appendLine("${indent}${entry.name}/ $tag")

            if (collapseState == CollapseState.COLLAPSED) {
                // Collapsed directory: show item count badge, hide children
                val totalItems = countItemsUnder(entry.relativePath, allEntries)
                if (totalItems > 0) {
                    sb.appendLine("${indent}  <contains $totalItems items>")
                }
            } else {
                // Expanded directory: show immediate children with their own state
                val children = childrenMap[entry.relativePath]
                    ?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                    ?: emptyList()

                for (child in children) {
                    appendEntryToIndex(child, allEntries, childrenMap, collapseOverrides, sb)
                }
            }
        } else {
            // File entry
            sb.appendLine("${indent}${entry.name} $tag")
        }
    }

    // =========================================================================
    // Path utilities
    // =========================================================================

    /**
     * Computes the parent directory path for a given path.
     * Returns null for root-level entries (no parent directory in the workspace).
     *
     * Examples:
     * - "src/main.kt" → "src/"
     * - "src/util/" → "src/"
     * - "config.yaml" → null (root-level)
     * - "src/" → null (root-level directory)
     */
    private fun computeParentPath(path: String): String? {
        val trimmed = path.trimEnd('/')
        val lastSlash = trimmed.lastIndexOf('/')
        if (lastSlash < 0) return null // Root-level entry
        return trimmed.substring(0, lastSlash + 1)
    }
}