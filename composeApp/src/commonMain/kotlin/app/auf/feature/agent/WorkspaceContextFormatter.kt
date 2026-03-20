package app.auf.feature.agent

import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import kotlinx.serialization.json.*

/**
 * Pipeline-level utility for transforming raw workspace file listings into the
 * two-partition view for agent context injection.
 *
 * Mirrors [HkgContextFormatter]'s INDEX/FILES architecture but adapted for the
 * workspace's directory/file hierarchy:
 *
 * **WORKSPACE_INDEX** — navigational tree of the workspace directory structure.
 * Uses 2-space indentation per depth level. Shows collapse state badges for both
 * directories (controls tree visibility) and files (controls content loading).
 * Collapsed directories show `<contains N items>` badge and hide children.
 *
 * **WORKSPACE_FILES** — complete file contents for all EXPANDED files. Participates
 * in the context budget algorithm. Default: all files closed.
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
     * Build the WORKSPACE_INDEX tree string.
     *
     * Rules:
     * - COLLAPSED directory: shows name + `<contains N items>` badge. Children hidden.
     * - EXPANDED directory: shows its immediate children (each with their own state).
     * - `[EXPANDED]` on a file means its content is in WORKSPACE_FILES.
     * - `[COLLAPSED]` on a file means it appears in INDEX only (no content loaded).
     *
     * @param entries Parsed workspace entries from [parseListingEntries].
     * @param collapseOverrides Agent's sticky collapse overrides. Key = "ws:<relativePath>".
     */
    fun buildIndexTree(
        entries: List<WorkspaceEntry>,
        collapseOverrides: Map<String, CollapseState>
    ): String {
        if (entries.isEmpty()) {
            return "--- WORKSPACE_INDEX ---\nWorkspace is empty.\n--- END OF WORKSPACE_INDEX ---"
        }

        val totalFiles = entries.count { !it.isDirectory }
        val totalDirs = entries.count { it.isDirectory }
        val expandedFileCount = entries.count { !it.isDirectory && resolveCollapseState(it.relativePath, collapseOverrides) == CollapseState.EXPANDED }

        return buildString {
            appendLine("--- WORKSPACE_INDEX ---")
            appendLine("Workspace: $totalFiles files, $totalDirs directories | $expandedFileCount files open in WORKSPACE_FILES")
            appendLine()

            // Build a parent→children map for efficient tree traversal
            val childrenMap = buildChildrenMap(entries)

            // Render root-level entries (parentPath == null)
            val rootEntries = entries.filter { it.parentPath == null }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name }))

            for (entry in rootEntries) {
                appendEntryToIndex(entry, entries, childrenMap, collapseOverrides, this)
            }

            appendLine("--- END OF WORKSPACE_INDEX ---")
        }.trimEnd()
    }

    /**
     * Build the WORKSPACE_FILES section as a [PromptSection.Group] with per-file
     * children for the unified partition model. Each file becomes an individually
     * collapsible child [PromptSection.Section].
     *
     * The child key convention is `ws:<relativePath>`, matching the existing
     * `contextCollapseOverrides` key space used by CONTEXT_COLLAPSE/UNCOLLAPSE.
     *
     * Unlike [buildFilesSection] (which bakes collapse into string content),
     * this method emits ALL fetched files as children — the collapse algorithm in
     * [flattenWithCascade] handles visibility based on override state.
     *
     * @param expandedFileContents Map of relative path → file content for files
     *   that have been fetched (content is available for rendering).
     * @param collapseOverrides Agent's sticky collapse overrides.
     * @param platformDependencies For logging.
     */
    fun buildFilesSections(
        expandedFileContents: Map<String, String>,
        collapseOverrides: Map<String, CollapseState>,
        platformDependencies: PlatformDependencies? = null
    ): PromptSection.Group {
        val children = expandedFileContents.keys.sorted().map { path ->
            val content = expandedFileContents[path]
            if (content == null) {
                platformDependencies?.log(
                    LogLevel.WARN, LOG_TAG,
                    "File '$path' is in the fetched map but has null content. " +
                            "It will appear in INDEX but be missing from FILES."
                )
            }
            PromptSection.Section(
                key = "ws:$path",
                content = content ?: "[Content unavailable for file '$path']",
                isProtected = false,
                isCollapsible = true,
                priority = 10,
                collapsedSummary = "[File '$path' closed. Use agent.CONTEXT_UNCOLLAPSE to open.]",
                defaultCollapsed = true
            )
        }

        val expandedCount = expandedFileContents.keys.count { path ->
            resolveCollapseState(path, collapseOverrides) == CollapseState.EXPANDED
        }

        return PromptSection.Group(
            key = "WORKSPACE_FILES",
            header = if (children.isNotEmpty())
                "Files currently open: $expandedCount"
            else "",
            children = children,
            isCollapsible = true,
            priority = 10,
            collapsedSummary = "[Workspace files collapsed. Use agent.CONTEXT_UNCOLLAPSE to open specific files.]"
        )
    }

    /**
     * Build the WORKSPACE_FILES section string (expanded files only).
     *
     * @param expandedFileContents Map of relative path → file content for files that
     *   have been fetched. Only files whose collapse state is EXPANDED appear here.
     * @param collapseOverrides Agent's sticky collapse overrides.
     *
     * @deprecated Use [buildFilesSections] for the structured partition model.
     *   Retained during migration for backward compatibility.
     */
    fun buildFilesSection(
        expandedFileContents: Map<String, String>,
        collapseOverrides: Map<String, CollapseState>,
        platformDependencies: PlatformDependencies? = null
    ): String {
        // Filter to only include files that are actually EXPANDED in the overrides
        val expandedPaths = expandedFileContents.keys.filter { path ->
            resolveCollapseState(path, collapseOverrides) == CollapseState.EXPANDED
        }.sorted()

        return buildString {
            appendLine("--- WORKSPACE_FILES ---")

            if (expandedPaths.isEmpty()) {
                appendLine("No files open. Use agent.CONTEXT_UNCOLLAPSE to open workspace files.")
            } else {
                appendLine("Files currently open: ${expandedPaths.size}")
                appendLine()

                for (path in expandedPaths) {
                    val content = expandedFileContents[path]
                    if (content == null) {
                        platformDependencies?.log(
                            LogLevel.WARN, LOG_TAG,
                            "File '$path' is EXPANDED but has no content in the fetched map. " +
                                    "It will appear as [EXPANDED] in INDEX but be missing from FILES."
                        )
                        continue
                    }
                    val ext = path.substringAfterLast('.', "")
                    appendLine("--- START OF FILE $path ---")
                    appendLine(content)
                    appendLine("--- END OF FILE $path ---")
                    appendLine()
                }
            }

            appendLine("--- END OF WORKSPACE_FILES ---")
        }.trimEnd()
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