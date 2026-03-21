package app.auf.feature.agent.contextformatters

import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.agent.CollapseState
import app.auf.feature.agent.PromptSection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tier 1 Unit Tests for WorkspaceContextFormatter.
 *
 * WorkspaceContextFormatter is a pipeline-level pure utility (§2.3 absolute decoupling)
 * that transforms raw workspace file listings into the two-partition
 * WORKSPACE_INDEX + WORKSPACE_FILES view.
 *
 * Tests verify:
 * - parseListingEntries: path normalization, depth, parent-child, directory trailing slash,
 *   prefix stripping, malformed entry handling
 * - countItemsUnder: recursive descendant counting
 * - resolveCollapseState: default COLLAPSED, override EXPANDED, ws: prefix convention
 * - buildIndexTree: COLLAPSED/EXPANDED tags, item count badges, indentation, children visibility,
 *   empty workspace
 * - buildFilesSections: tree structure, defaultCollapsed, file content injection, placeholder text
 * - getExpandedFilePaths: correct filtering of expanded files, stale key rejection
 * - getSubtreeDirectoryPaths: inclusive subtree enumeration
 */
class WorkspaceContextFormatterT1Test {

    private val platform = FakePlatformDependencies("test")

    // =========================================================================
    // Helper: build raw workspace listing JsonArray
    // =========================================================================

    /**
     * Builds a single listing entry as a JsonObject.
     */
    private fun listingEntry(path: String, isDirectory: Boolean): JsonObject =
        buildJsonObject {
            put("path", path)
            put("isDirectory", isDirectory)
        }

    /**
     * Builds a JsonArray of listing entries from vararg (path, isDirectory) pairs.
     * All paths are prefixed with the given workspace prefix.
     */
    private fun listingArray(
        workspacePrefix: String,
        vararg entries: Pair<String, Boolean>
    ): JsonArray = buildJsonArray {
        for ((path, isDir) in entries) {
            add(listingEntry("$workspacePrefix/$path", isDir))
        }
    }

    // =========================================================================
    // Reusable test workspace: a small project structure
    //
    //   config.yaml        (file)
    //   README.md           (file)
    //   src/                (dir)
    //     main.kt           (file)
    //     util/             (dir)
    //       helpers.kt      (file)
    //       constants.kt    (file)
    //   docs/               (dir)
    //     guide.md          (file)
    // =========================================================================

    private val testPrefix = "agent-123/workspace"

    private val testListingArray = listingArray(
        testPrefix,
        "config.yaml" to false,
        "README.md" to false,
        "src" to true,
        "src/main.kt" to false,
        "src/util" to true,
        "src/util/helpers.kt" to false,
        "src/util/constants.kt" to false,
        "docs" to true,
        "docs/guide.md" to false
    )

    private val testEntries by lazy {
        WorkspaceContextFormatter.parseListingEntries(testListingArray, testPrefix, platform)
    }

    // =========================================================================
    // parseListingEntries
    // =========================================================================

    @Test
    fun `parseListingEntries extracts all entries from workspace listing`() {
        assertEquals(9, testEntries.size)
    }

    @Test
    fun `parseListingEntries normalizes directory paths with trailing slash`() {
        val dirs = testEntries.filter { it.isDirectory }
        assertTrue(dirs.all { it.relativePath.endsWith("/") },
            "All directory relative paths should end with /")
    }

    @Test
    fun `parseListingEntries normalizes file paths without trailing slash`() {
        val files = testEntries.filter { !it.isDirectory }
        assertTrue(files.none { it.relativePath.endsWith("/") },
            "No file relative path should end with /")
    }

    @Test
    fun `parseListingEntries strips workspace prefix correctly`() {
        val paths = testEntries.map { it.relativePath }
        assertTrue(paths.contains("config.yaml"))
        assertTrue(paths.contains("src/"))
        assertTrue(paths.contains("src/main.kt"))
        assertTrue(paths.contains("src/util/"))
        assertTrue(paths.contains("src/util/helpers.kt"))
    }

    @Test
    fun `parseListingEntries computes correct depth`() {
        val byPath = testEntries.associateBy { it.relativePath }

        assertEquals(0, byPath["config.yaml"]!!.depth, "Root-level file depth")
        assertEquals(0, byPath["README.md"]!!.depth, "Root-level file depth")
        assertEquals(0, byPath["src/"]!!.depth, "Root-level directory depth")
        assertEquals(1, byPath["src/main.kt"]!!.depth, "Depth-1 file")
        assertEquals(1, byPath["src/util/"]!!.depth, "Depth-1 directory")
        assertEquals(2, byPath["src/util/helpers.kt"]!!.depth, "Depth-2 file")
        assertEquals(0, byPath["docs/"]!!.depth, "Root-level directory depth")
        assertEquals(1, byPath["docs/guide.md"]!!.depth, "Depth-1 file")
    }

    @Test
    fun `parseListingEntries assigns correct parentPath`() {
        val byPath = testEntries.associateBy { it.relativePath }

        assertEquals(null, byPath["config.yaml"]!!.parentPath, "Root file has null parent")
        assertEquals(null, byPath["src/"]!!.parentPath, "Root dir has null parent")
        assertEquals("src/", byPath["src/main.kt"]!!.parentPath, "File in src/ has parent src/")
        assertEquals("src/", byPath["src/util/"]!!.parentPath, "Dir in src/ has parent src/")
        assertEquals("src/util/", byPath["src/util/helpers.kt"]!!.parentPath, "File in src/util/ has parent src/util/")
        assertEquals("docs/", byPath["docs/guide.md"]!!.parentPath, "File in docs/ has parent docs/")
    }

    @Test
    fun `parseListingEntries extracts correct name from path`() {
        val byPath = testEntries.associateBy { it.relativePath }

        assertEquals("config.yaml", byPath["config.yaml"]!!.name)
        assertEquals("src", byPath["src/"]!!.name)
        assertEquals("main.kt", byPath["src/main.kt"]!!.name)
        assertEquals("util", byPath["src/util/"]!!.name)
        assertEquals("helpers.kt", byPath["src/util/helpers.kt"]!!.name)
    }

    @Test
    fun `parseListingEntries skips workspace root entry`() {
        // Add the workspace root itself as an entry — should be ignored
        val listingWithRoot = buildJsonArray {
            add(listingEntry("$testPrefix", true))  // The root itself
            add(listingEntry("$testPrefix/file.txt", false))
        }

        val entries = WorkspaceContextFormatter.parseListingEntries(listingWithRoot, testPrefix, platform)

        assertEquals(1, entries.size, "Workspace root itself should be skipped")
        assertEquals("file.txt", entries[0].relativePath)
    }

    @Test
    fun `parseListingEntries handles empty listing`() {
        val entries = WorkspaceContextFormatter.parseListingEntries(
            JsonArray(emptyList()), testPrefix, platform
        )
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `parseListingEntries skips entries without path field`() {
        val listing = buildJsonArray {
            add(buildJsonObject { put("isDirectory", false) }) // No path
            add(listingEntry("$testPrefix/valid.txt", false))
        }

        val entries = WorkspaceContextFormatter.parseListingEntries(listing, testPrefix, platform)

        assertEquals(1, entries.size)
        assertEquals("valid.txt", entries[0].relativePath)
    }

    @Test
    fun `parseListingEntries normalizes backslashes in paths`() {
        val listing = buildJsonArray {
            add(listingEntry("$testPrefix\\src\\main.kt", false))
        }

        val entries = WorkspaceContextFormatter.parseListingEntries(listing, testPrefix, platform)

        assertEquals(1, entries.size)
        assertEquals("src/main.kt", entries[0].relativePath)
    }

    // =========================================================================
    // countItemsUnder
    // =========================================================================

    @Test
    fun `countItemsUnder counts all descendants of a directory`() {
        // src/ contains: main.kt, util/, util/helpers.kt, util/constants.kt = 4 items
        assertEquals(4, WorkspaceContextFormatter.countItemsUnder("src/", testEntries))
    }

    @Test
    fun `countItemsUnder counts nested subdirectory contents`() {
        // src/util/ contains: helpers.kt, constants.kt = 2 items
        assertEquals(2, WorkspaceContextFormatter.countItemsUnder("src/util/", testEntries))
    }

    @Test
    fun `countItemsUnder returns 0 for leaf directory`() {
        // An empty directory or one with no children in the listing
        val entries = WorkspaceContextFormatter.parseListingEntries(
            listingArray(testPrefix, "empty/" to true),
            testPrefix, platform
        )
        assertEquals(0, WorkspaceContextFormatter.countItemsUnder("empty/", entries))
    }

    @Test
    fun `countItemsUnder returns 0 for unknown directory`() {
        assertEquals(0, WorkspaceContextFormatter.countItemsUnder("nonexistent/", testEntries))
    }

    @Test
    fun `countItemsUnder normalizes missing trailing slash`() {
        // Should still work without trailing slash
        assertEquals(4, WorkspaceContextFormatter.countItemsUnder("src", testEntries))
    }

    // =========================================================================
    // resolveCollapseState
    // =========================================================================

    @Test
    fun `resolveCollapseState defaults to COLLAPSED`() {
        assertEquals(
            CollapseState.COLLAPSED,
            WorkspaceContextFormatter.resolveCollapseState("any/file.txt", emptyMap())
        )
    }

    @Test
    fun `resolveCollapseState uses ws prefix convention`() {
        val overrides = mapOf("ws:src/main.kt" to CollapseState.EXPANDED)

        assertEquals(
            CollapseState.EXPANDED,
            WorkspaceContextFormatter.resolveCollapseState("src/main.kt", overrides)
        )
        assertEquals(
            CollapseState.COLLAPSED,
            WorkspaceContextFormatter.resolveCollapseState("other.txt", overrides)
        )
    }

    @Test
    fun `resolveCollapseState works for directory paths`() {
        val overrides = mapOf("ws:src/" to CollapseState.EXPANDED)

        assertEquals(
            CollapseState.EXPANDED,
            WorkspaceContextFormatter.resolveCollapseState("src/", overrides)
        )
    }

    // =========================================================================
    // buildIndexTree
    // =========================================================================

    @Test
    fun `buildIndexTree renders empty workspace message`() {
        val index = WorkspaceContextFormatter.buildIndexTree(emptyList(), emptyMap())

        assertTrue(index.contains("WORKSPACE_INDEX"))
        assertTrue(index.contains("Workspace is empty."))
        assertTrue(index.contains("END OF WORKSPACE_INDEX"))
    }

    @Test
    fun `buildIndexTree shows summary line with file and directory counts`() {
        val index = WorkspaceContextFormatter.buildIndexTree(testEntries, emptyMap())

        // 6 files, 3 directories
        assertTrue(index.contains("6 files"), "Should show file count")
        assertTrue(index.contains("3 directories"), "Should show directory count")
    }

    @Test
    fun `buildIndexTree all collapsed shows root entries with COLLAPSED tags`() {
        val index = WorkspaceContextFormatter.buildIndexTree(testEntries, emptyMap())

        // Root directories should show as COLLAPSED
        assertTrue(index.contains("src/ [COLLAPSED]"))
        assertTrue(index.contains("docs/ [COLLAPSED]"))

        // Root files should show as COLLAPSED
        assertTrue(index.contains("config.yaml [COLLAPSED]"))
        assertTrue(index.contains("README.md [COLLAPSED]"))
    }

    @Test
    fun `buildIndexTree collapsed directory shows item count badge and hides children`() {
        val index = WorkspaceContextFormatter.buildIndexTree(testEntries, emptyMap())

        // src/ is collapsed → should show item count badge
        assertTrue(index.contains("<contains 4 items>"), "Collapsed src/ should show item count badge")

        // Children of collapsed dir should NOT appear
        assertFalse(index.contains("main.kt"), "Children of collapsed src/ should be hidden")
        assertFalse(index.contains("util/"), "Sub-dirs of collapsed src/ should be hidden")
    }

    @Test
    fun `buildIndexTree expanded directory reveals children`() {
        val overrides = mapOf("ws:src/" to CollapseState.EXPANDED)
        val index = WorkspaceContextFormatter.buildIndexTree(testEntries, overrides)

        // src/ is expanded
        assertTrue(index.contains("src/ [EXPANDED]"))

        // Immediate children of src/ should be visible
        assertTrue(index.contains("main.kt"), "Children of expanded src/ should be visible")
        assertTrue(index.contains("util/"), "Sub-dirs of expanded src/ should be visible")
    }

    @Test
    fun `buildIndexTree expanded file shows EXPANDED tag`() {
        val overrides = mapOf(
            "ws:src/" to CollapseState.EXPANDED,
            "ws:src/main.kt" to CollapseState.EXPANDED
        )
        val index = WorkspaceContextFormatter.buildIndexTree(testEntries, overrides)

        assertTrue(index.contains("main.kt [EXPANDED]"))
    }

    @Test
    fun `buildIndexTree mixed collapse states render correctly`() {
        val overrides = mapOf(
            "ws:src/" to CollapseState.EXPANDED,
            "ws:src/util/" to CollapseState.COLLAPSED,
            "ws:docs/" to CollapseState.EXPANDED
        )
        val index = WorkspaceContextFormatter.buildIndexTree(testEntries, overrides)

        // src/ expanded → main.kt and util/ visible
        assertTrue(index.contains("src/ [EXPANDED]"))
        assertTrue(index.contains("main.kt"))
        assertTrue(index.contains("util/ [COLLAPSED]"))

        // util/ collapsed → helpers.kt hidden, badge shown
        assertFalse(index.contains("helpers.kt"), "Children of collapsed util/ should be hidden")
        assertTrue(index.contains("<contains 2 items>"), "Collapsed util/ should show item count")

        // docs/ expanded → guide.md visible
        assertTrue(index.contains("docs/ [EXPANDED]"))
        assertTrue(index.contains("guide.md"))
    }

    @Test
    fun `buildIndexTree uses 2-space indentation per depth level`() {
        val overrides = mapOf(
            "ws:src/" to CollapseState.EXPANDED,
            "ws:src/util/" to CollapseState.EXPANDED
        )
        val index = WorkspaceContextFormatter.buildIndexTree(testEntries, overrides)

        // depth 0: no indent for root entries
        assertTrue(index.contains("\nsrc/ [EXPANDED]") || index.startsWith("src/ [EXPANDED]") ||
                index.lines().any { it.trimStart() == "src/ [EXPANDED]" && !it.startsWith("  ") })

        // depth 1: 2 spaces
        assertTrue(index.contains("  main.kt"), "Depth-1 entries should have 2-space indent")
        assertTrue(index.contains("  util/"), "Depth-1 dir should have 2-space indent")

        // depth 2: 4 spaces
        assertTrue(index.contains("    helpers.kt"), "Depth-2 entries should have 4-space indent")
    }

    @Test
    fun `buildIndexTree shows expanded file count in summary`() {
        val overrides = mapOf(
            "ws:src/" to CollapseState.EXPANDED,
            "ws:src/main.kt" to CollapseState.EXPANDED,
            "ws:config.yaml" to CollapseState.EXPANDED
        )
        val index = WorkspaceContextFormatter.buildIndexTree(testEntries, overrides)

        assertTrue(index.contains("2 files open"), "Should count expanded files in summary")
    }

    // =========================================================================
    // buildFilesSections — tree structure
    // =========================================================================

    /**
     * Recursively collects all keys from a PromptSection tree.
     */
    private fun collectKeys(section: PromptSection): List<String> = when (section) {
        is PromptSection.Section -> listOf(section.key)
        is PromptSection.Group -> listOf(section.key) + section.children.flatMap { collectKeys(it) }
        else -> emptyList()
    }

    /**
     * Recursively collects all content from Section nodes in a PromptSection tree.
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
     * Finds a PromptSection.Section by key in a tree.
     */
    private fun findSection(root: PromptSection, key: String): PromptSection.Section? = when (root) {
        is PromptSection.Section -> if (root.key == key) root else null
        is PromptSection.Group -> root.children.firstNotNullOfOrNull { findSection(it, key) }
        else -> null
    }

    /**
     * Finds a PromptSection.Group by key in a tree.
     */
    private fun findGroup(root: PromptSection, key: String): PromptSection.Group? = when (root) {
        is PromptSection.Group -> if (root.key == key) root else root.children.firstNotNullOfOrNull { findGroup(it, key) }
        else -> null
    }

    @Test
    fun `buildFilesSections returns Group with key WORKSPACE_FILES`() {
        val group = WorkspaceContextFormatter.buildFilesSections(
            testEntries, emptyMap(), emptyMap(), platform
        )

        assertEquals("WORKSPACE_FILES", group.key)
        assertTrue(group.isCollapsible)
    }

    @Test
    fun `buildFilesSections header contains file and directory counts`() {
        val group = WorkspaceContextFormatter.buildFilesSections(
            testEntries, emptyMap(), emptyMap(), platform
        )

        assertTrue(group.header.contains("6 file"), "Header should mention file count")
        assertTrue(group.header.contains("3 director"), "Header should mention directory count")
        assertTrue(group.header.contains("0 files open"), "Header should show 0 expanded files")
    }

    @Test
    fun `buildFilesSections builds nested Group structure for directories`() {
        val group = WorkspaceContextFormatter.buildFilesSections(
            testEntries, emptyMap(), emptyMap(), platform
        )

        val allKeys = collectKeys(group)

        // Root-level entries
        assertTrue(allKeys.contains("ws:config.yaml"))
        assertTrue(allKeys.contains("ws:README.md"))
        assertTrue(allKeys.contains("ws:src/"))
        assertTrue(allKeys.contains("ws:docs/"))

        // Nested entries
        assertTrue(allKeys.contains("ws:src/main.kt"))
        assertTrue(allKeys.contains("ws:src/util/"))
        assertTrue(allKeys.contains("ws:src/util/helpers.kt"))
        assertTrue(allKeys.contains("ws:src/util/constants.kt"))
        assertTrue(allKeys.contains("ws:docs/guide.md"))
    }

    @Test
    fun `buildFilesSections directories are Group nodes and files are Section nodes`() {
        val group = WorkspaceContextFormatter.buildFilesSections(
            testEntries, emptyMap(), emptyMap(), platform
        )

        // src/ should be a Group
        val srcGroup = findGroup(group, "ws:src/")
        assertTrue(srcGroup != null, "src/ should be a Group")
        assertTrue(srcGroup!!.isCollapsible)

        // config.yaml should be a Section
        val configSection = findSection(group, "ws:config.yaml")
        assertTrue(configSection != null, "config.yaml should be a Section")
    }

    @Test
    fun `buildFilesSections all entries default to collapsed`() {
        val group = WorkspaceContextFormatter.buildFilesSections(
            testEntries, emptyMap(), emptyMap(), platform
        )

        // Files should have defaultCollapsed = true
        val configSection = findSection(group, "ws:config.yaml")
        assertTrue(configSection!!.defaultCollapsed, "Files should default to collapsed")

        // Directories should have defaultCollapsed = true
        val srcGroup = findGroup(group, "ws:src/")
        assertTrue(srcGroup!!.defaultCollapsed, "Directories should default to collapsed")
    }

    @Test
    fun `buildFilesSections injects file content for available files`() {
        val fileContents = mapOf(
            "config.yaml" to "server:\n  port: 8080",
            "src/main.kt" to "fun main() { println(\"Hello\") }"
        )

        val group = WorkspaceContextFormatter.buildFilesSections(
            testEntries, fileContents, emptyMap(), platform
        )

        val configSection = findSection(group, "ws:config.yaml")
        assertEquals("server:\n  port: 8080", configSection!!.content)

        val mainSection = findSection(group, "ws:src/main.kt")
        assertEquals("fun main() { println(\"Hello\") }", mainSection!!.content)
    }

    @Test
    fun `buildFilesSections shows placeholder for files without content`() {
        val group = WorkspaceContextFormatter.buildFilesSections(
            testEntries, emptyMap(), emptyMap(), platform
        )

        val configSection = findSection(group, "ws:config.yaml")
        assertTrue(configSection!!.content.contains("not loaded"),
            "Files without content should show a placeholder")
        assertTrue(configSection.content.contains("CONTEXT_UNCOLLAPSE"),
            "Placeholder should mention how to open the file")
    }

    @Test
    fun `buildFilesSections collapsedSummary contains entry name`() {
        val group = WorkspaceContextFormatter.buildFilesSections(
            testEntries, emptyMap(), emptyMap(), platform
        )

        // File collapsed summary
        val configSection = findSection(group, "ws:config.yaml")
        assertTrue(configSection!!.collapsedSummary!!.contains("config.yaml"),
            "File collapsed summary should contain filename")

        // Directory collapsed summary
        val srcGroup = findGroup(group, "ws:src/")
        assertTrue(srcGroup!!.collapsedSummary!!.contains("src"),
            "Directory collapsed summary should contain dir name")
    }

    @Test
    fun `buildFilesSections directory collapsedSummary includes item count`() {
        val group = WorkspaceContextFormatter.buildFilesSections(
            testEntries, emptyMap(), emptyMap(), platform
        )

        val srcGroup = findGroup(group, "ws:src/")
        assertTrue(srcGroup!!.collapsedSummary!!.contains("4 items"),
            "Directory collapsed summary should include descendant count")
    }

    @Test
    fun `buildFilesSections header shows expanded file count`() {
        val overrides = mapOf(
            "ws:config.yaml" to CollapseState.EXPANDED,
            "ws:src/main.kt" to CollapseState.EXPANDED
        )

        val group = WorkspaceContextFormatter.buildFilesSections(
            testEntries, emptyMap(), overrides, platform
        )

        assertTrue(group.header.contains("2 files open"),
            "Header should reflect expanded file count from overrides")
    }

    @Test
    fun `buildFilesSections top-level Group collapsedSummary mentions CONTEXT_UNCOLLAPSE`() {
        val group = WorkspaceContextFormatter.buildFilesSections(
            testEntries, emptyMap(), emptyMap(), platform
        )

        assertTrue(group.collapsedSummary!!.contains("CONTEXT_UNCOLLAPSE"),
            "Top-level collapsed summary should mention how to expand")
    }

    @Test
    fun `buildFilesSections with empty entries returns Group with no children`() {
        val group = WorkspaceContextFormatter.buildFilesSections(
            emptyList(), emptyMap(), emptyMap(), platform
        )

        assertEquals("WORKSPACE_FILES", group.key)
        assertTrue(group.children.isEmpty())
        assertTrue(group.header.contains("empty"), "Header should indicate workspace is empty")
    }

    // =========================================================================
    // getExpandedFilePaths
    // =========================================================================

    @Test
    fun `getExpandedFilePaths returns expanded files`() {
        val overrides = mapOf(
            "ws:config.yaml" to CollapseState.EXPANDED,
            "ws:src/main.kt" to CollapseState.EXPANDED,
            "ws:README.md" to CollapseState.COLLAPSED
        )

        val expanded = WorkspaceContextFormatter.getExpandedFilePaths(testEntries, overrides)

        assertEquals(setOf("config.yaml", "src/main.kt"), expanded)
    }

    @Test
    fun `getExpandedFilePaths ignores expanded directories`() {
        val overrides = mapOf(
            "ws:src/" to CollapseState.EXPANDED,  // directory, not a file
            "ws:src/main.kt" to CollapseState.EXPANDED
        )

        val expanded = WorkspaceContextFormatter.getExpandedFilePaths(testEntries, overrides)

        assertEquals(setOf("src/main.kt"), expanded,
            "Directories should not appear in expanded file paths")
    }

    @Test
    fun `getExpandedFilePaths ignores stale keys not in entries`() {
        val overrides = mapOf(
            "ws:deleted-file.txt" to CollapseState.EXPANDED,  // not in listing
            "ws:config.yaml" to CollapseState.EXPANDED
        )

        val expanded = WorkspaceContextFormatter.getExpandedFilePaths(testEntries, overrides)

        assertEquals(setOf("config.yaml"), expanded,
            "Stale keys referencing non-existent files should be ignored")
    }

    @Test
    fun `getExpandedFilePaths returns empty when nothing expanded`() {
        val expanded = WorkspaceContextFormatter.getExpandedFilePaths(testEntries, emptyMap())
        assertTrue(expanded.isEmpty())
    }

    @Test
    fun `getExpandedFilePaths ignores keys without ws prefix`() {
        val overrides = mapOf(
            "hkg:some-holon" to CollapseState.EXPANDED,  // wrong prefix
            "ws:config.yaml" to CollapseState.EXPANDED
        )

        val expanded = WorkspaceContextFormatter.getExpandedFilePaths(testEntries, overrides)

        assertEquals(setOf("config.yaml"), expanded,
            "Only ws:-prefixed keys should be considered")
    }

    // =========================================================================
    // getSubtreeDirectoryPaths
    // =========================================================================

    @Test
    fun `getSubtreeDirectoryPaths returns target directory and all nested dirs`() {
        val paths = WorkspaceContextFormatter.getSubtreeDirectoryPaths("src/", testEntries)

        assertEquals(setOf("src/", "src/util/"), paths,
            "Should include the target directory and all nested sub-directories")
    }

    @Test
    fun `getSubtreeDirectoryPaths returns only target for leaf directory`() {
        val paths = WorkspaceContextFormatter.getSubtreeDirectoryPaths("docs/", testEntries)

        assertEquals(setOf("docs/"), paths,
            "Leaf directory with no sub-dirs should return only itself")
    }

    @Test
    fun `getSubtreeDirectoryPaths returns empty for nonexistent directory`() {
        val paths = WorkspaceContextFormatter.getSubtreeDirectoryPaths("nonexistent/", testEntries)

        assertTrue(paths.isEmpty())
    }

    @Test
    fun `getSubtreeDirectoryPaths normalizes missing trailing slash`() {
        val paths = WorkspaceContextFormatter.getSubtreeDirectoryPaths("src", testEntries)

        assertEquals(setOf("src/", "src/util/"), paths,
            "Should work even without trailing slash on input")
    }

    @Test
    fun `getSubtreeDirectoryPaths does not include files`() {
        val paths = WorkspaceContextFormatter.getSubtreeDirectoryPaths("src/", testEntries)

        assertFalse(paths.contains("src/main.kt"), "Should not include files")
        assertFalse(paths.contains("src/util/helpers.kt"), "Should not include nested files")
    }
}