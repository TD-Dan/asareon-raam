package app.auf.feature.filesystem

import app.auf.core.Action
import app.auf.core.generated.ActionRegistry
import app.auf.fakes.FakePlatformDependencies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tier 1 Unit Test for FileSystemFeature's reducer.
 *
 * Mandate (P-TEST-001, T1): To test the reducer as a pure function in complete isolation.
 */
class FileSystemFeatureT1ReducerTest {

    private val platform = FakePlatformDependencies("v2-test")
    private val feature = FileSystemFeature(platform)
    private val featureName = feature.identity.handle

    // ========================================================================
    // Existing Tests
    // ========================================================================

    @Test
    fun `reducer DIRECTORY_LOADED correctly updates path and rootItems and clears error`() {
        platform.directories.add("/test")
        val initialState = FileSystemState(error = "Old error")
        val payload = buildJsonObject {
            put("parentPath", "/test")
            putJsonArray("children") {
                add(buildJsonObject {
                    put("path", "/test/file.txt")
                    put("isDirectory", false)
                })
            }
        }
        val action = Action(ActionRegistry.Names.FILESYSTEM_DIRECTORY_LOADED, payload)

        val navigatingState = feature.reducer(initialState, Action(ActionRegistry.Names.FILESYSTEM_NAVIGATE, buildJsonObject { put("path", "/test") }))
        val newState = feature.reducer(navigatingState, action) as? FileSystemState

        assertNotNull(newState)
        assertEquals("/test", newState.currentPath)
        assertEquals(1, newState.rootItems.size)
        assertEquals("file.txt", newState.rootItems.first().name)
        assertNull(newState.error, "Error should be cleared on a successful navigation.")
    }

    /*
    // TODO: This test is disabled because the NAVIGATION_FAILED action was removed during the
    // sandboxing refactor. A new error handling mechanism for the UI needs to be designed.
    @Test
    fun `reducer NAVIGATION_FAILED sets error message and clears rootItems`() { ... }
    */

    @Test
    fun `reducer ADD_WHITELIST_PATH adds path to whitelist`() {
        val initialState = FileSystemState()
        val action = Action(ActionRegistry.Names.FILESYSTEM_ADD_WHITELIST_PATH, buildJsonObject { put("path", "/safe/path") })
        val newState = feature.reducer(initialState, action) as FileSystemState
        assertTrue(newState.whitelistedPaths.contains("/safe/path"))
        assertEquals(1, newState.whitelistedPaths.size)
    }

    @Test
    fun `reducer REMOVE_WHITELIST_PATH removes path from whitelist`() {
        val initialState = FileSystemState(whitelistedPaths = setOf("/safe/path", "/other"))
        val action = Action(ActionRegistry.Names.FILESYSTEM_REMOVE_WHITELIST_PATH, buildJsonObject { put("path", "/safe/path") })
        val newState = feature.reducer(initialState, action) as FileSystemState
        assertFalse(newState.whitelistedPaths.contains("/safe/path"))
        assertTrue(newState.whitelistedPaths.contains("/other"))
        assertEquals(1, newState.whitelistedPaths.size)
    }

    @Test
    fun `reducer EXPAND_ALL recursively expands known children`() {
        val initialState = FileSystemState(rootItems = listOf(
            FileSystemItem("/a", "a", true, children = listOf(
                FileSystemItem("/a/b", "b", true, children = emptyList(), isExpanded = false)
            ), isExpanded = false)
        ))
        val action = Action(ActionRegistry.Names.FILESYSTEM_EXPAND_ALL, buildJsonObject { put("path", "/a") })
        val newState = feature.reducer(initialState, action) as FileSystemState
        val itemA = newState.rootItems.first()
        val itemB = itemA.children?.first()
        assertTrue(itemA.isExpanded, "Parent should be expanded")
        assertNotNull(itemB)
        assertTrue(itemB.isExpanded, "Child should be expanded")
    }

    @Test
    fun `reducer TOGGLE_ITEM_SELECTED recursively selects known children`() {
        val initialState = FileSystemState(rootItems = listOf(
            FileSystemItem("/a", "a", true, isSelected = false, children = listOf(
                FileSystemItem("/a/file.txt", "file.txt", false, isSelected = false)
            ))
        ))
        val action = Action(ActionRegistry.Names.FILESYSTEM_TOGGLE_ITEM_SELECTED, buildJsonObject {
            put("path", "/a")
            put("recursive", true)
        })
        val newState = feature.reducer(initialState, action) as FileSystemState
        val itemA = newState.rootItems.first()
        val itemFile = itemA.children?.first()
        assertTrue(itemA.isSelected, "Parent directory should be selected")
        assertNotNull(itemFile)
        assertTrue(itemFile.isSelected, "Child file should be selected")
    }

    @Test
    fun `reducer hydrates state from settings LOADED action`() {
        val initialState = FileSystemState()
        val payload = buildJsonObject {
            put("filesystem.whitelistedPaths", "path1,path2")
            put("filesystem.favoritePaths", "fav1")
        }
        val action = Action(ActionRegistry.Names.SETTINGS_LOADED, payload)
        val newState = feature.reducer(initialState, action) as FileSystemState
        assertEquals(setOf("path1", "path2"), newState.whitelistedPaths)
        assertEquals(setOf("fav1"), newState.favoritePaths)
    }

    // ========================================================================
    // New Tests: Uncovered Reducer Branches (P4)
    // ========================================================================

    @Test
    fun `reducer COLLAPSE_ALL recursively collapses children`() {
        val initialState = FileSystemState(rootItems = listOf(
            FileSystemItem("/a", "a", true, isExpanded = true, children = listOf(
                FileSystemItem("/a/b", "b", true, isExpanded = true, children = listOf(
                    FileSystemItem("/a/b/c.txt", "c.txt", false)
                ))
            ))
        ))
        val action = Action(ActionRegistry.Names.FILESYSTEM_COLLAPSE_ALL, buildJsonObject { put("path", "/a") })
        val newState = feature.reducer(initialState, action) as FileSystemState
        val itemA = newState.rootItems.first()
        val itemB = itemA.children?.first()
        assertFalse(itemA.isExpanded, "Parent should be collapsed")
        assertNotNull(itemB)
        assertFalse(itemB.isExpanded, "Child directory should be collapsed")
    }

    @Test
    fun `reducer TOGGLE_ITEM_EXPANDED toggles expansion state`() {
        val initialState = FileSystemState(rootItems = listOf(
            FileSystemItem("/a", "a", true, isExpanded = false)
        ))
        val action = Action(ActionRegistry.Names.FILESYSTEM_TOGGLE_ITEM_EXPANDED, buildJsonObject { put("path", "/a") })

        val expanded = feature.reducer(initialState, action) as FileSystemState
        assertTrue(expanded.rootItems.first().isExpanded, "First toggle should expand")

        val collapsed = feature.reducer(expanded, action) as FileSystemState
        assertFalse(collapsed.rootItems.first().isExpanded, "Second toggle should collapse")
    }

    @Test
    fun `reducer ADD_FAVORITE_PATH adds path to favorites`() {
        val initialState = FileSystemState()
        val action = Action(ActionRegistry.Names.FILESYSTEM_ADD_FAVORITE_PATH, buildJsonObject { put("path", "/my/project") })
        val newState = feature.reducer(initialState, action) as FileSystemState
        assertTrue(newState.favoritePaths.contains("/my/project"))
        assertEquals(1, newState.favoritePaths.size)
    }

    @Test
    fun `reducer REMOVE_FAVORITE_PATH removes path from favorites`() {
        val initialState = FileSystemState(favoritePaths = setOf("/my/project", "/other"))
        val action = Action(ActionRegistry.Names.FILESYSTEM_REMOVE_FAVORITE_PATH, buildJsonObject { put("path", "/my/project") })
        val newState = feature.reducer(initialState, action) as FileSystemState
        assertFalse(newState.favoritePaths.contains("/my/project"))
        assertTrue(newState.favoritePaths.contains("/other"))
        assertEquals(1, newState.favoritePaths.size)
    }

    @Test
    fun `reducer SETTINGS_VALUE_CHANGED updates whitelistedPaths at runtime`() {
        val initialState = FileSystemState(whitelistedPaths = setOf("old"))
        val action = Action(ActionRegistry.Names.SETTINGS_VALUE_CHANGED, buildJsonObject {
            put("key", "filesystem.whitelistedPaths")
            put("value", "new1,new2")
        })
        val newState = feature.reducer(initialState, action) as FileSystemState
        assertEquals(setOf("new1", "new2"), newState.whitelistedPaths)
    }

    @Test
    fun `reducer SETTINGS_VALUE_CHANGED updates favoritePaths at runtime`() {
        val initialState = FileSystemState(favoritePaths = setOf("old"))
        val action = Action(ActionRegistry.Names.SETTINGS_VALUE_CHANGED, buildJsonObject {
            put("key", "filesystem.favoritePaths")
            put("value", "fav1,fav2,fav3")
        })
        val newState = feature.reducer(initialState, action) as FileSystemState
        assertEquals(setOf("fav1", "fav2", "fav3"), newState.favoritePaths)
    }

    @Test
    fun `reducer SETTINGS_VALUE_CHANGED ignores unrelated keys`() {
        val initialState = FileSystemState(whitelistedPaths = setOf("existing"), favoritePaths = setOf("fav"))
        val action = Action(ActionRegistry.Names.SETTINGS_VALUE_CHANGED, buildJsonObject {
            put("key", "session.some_other_setting")
            put("value", "irrelevant")
        })
        val newState = feature.reducer(initialState, action) as FileSystemState
        assertEquals(setOf("existing"), newState.whitelistedPaths, "Whitelist should be unchanged.")
        assertEquals(setOf("fav"), newState.favoritePaths, "Favorites should be unchanged.")
    }

    @Test
    fun `reducer NAVIGATE updates currentPath`() {
        val initialState = FileSystemState(currentPath = "/old/path")
        val action = Action(ActionRegistry.Names.FILESYSTEM_NAVIGATE, buildJsonObject { put("path", "/new/path") })
        val newState = feature.reducer(initialState, action) as FileSystemState
        assertEquals("/new/path", newState.currentPath)
    }

    @Test
    fun `reducer TOGGLE_ITEM_SELECTED non-recursive toggles single item`() {
        val initialState = FileSystemState(rootItems = listOf(
            FileSystemItem("/a", "a", true, isSelected = false, children = listOf(
                FileSystemItem("/a/file.txt", "file.txt", false, isSelected = false)
            ))
        ))
        val action = Action(ActionRegistry.Names.FILESYSTEM_TOGGLE_ITEM_SELECTED, buildJsonObject {
            put("path", "/a/file.txt")
            // recursive defaults to false
        })
        val newState = feature.reducer(initialState, action) as FileSystemState
        val parent = newState.rootItems.first()
        val child = parent.children?.first()
        assertNotNull(child)
        assertTrue(child.isSelected, "The targeted file should be selected.")
        assertFalse(parent.isSelected, "The parent should NOT be affected by a non-recursive toggle.")
    }

    @Test
    fun `reducer returns default state for null input`() {
        val action = Action("unknown.ACTION")
        val newState = feature.reducer(null, action)
        assertNotNull(newState, "Reducer should return a default FileSystemState for null input.")
        assertTrue(newState is FileSystemState)
        assertNull((newState as FileSystemState).currentPath)
        assertTrue(newState.rootItems.isEmpty())
    }

    // Helper extension for JsonObjectBuilder
    private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonArray(
        name: String,
        builderAction: kotlinx.serialization.json.JsonArrayBuilder.() -> Unit
    ) {
        put(name, kotlinx.serialization.json.buildJsonArray(builderAction))
    }
}