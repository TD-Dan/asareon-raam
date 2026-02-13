package app.auf.feature.filesystem

import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.FeatureState
import app.auf.core.generated.ActionNames
import app.auf.fakes.FakePlatformDependencies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Tier 1 Unit Test for FileSystemFeature's reducer.
 *
 * Mandate (P-TEST-001, T1): To test the reducer as a pure function in complete isolation.
 */
class FileSystemFeatureT1ReducerTest {

    private val platform = FakePlatformDependencies("v2-test")
    private val feature = FileSystemFeature(platform)
    private val featureName = feature.identity.handle

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
    fun `reducer NAVIGATION_FAILED sets error message and clears rootItems`() {
        val initialState = createAppState(FileSystemState(rootItems = listOf(FileSystemItem("/old/path", "path", true))))
        val payload = buildJsonObject {
            put("path", "/bad/path")
            put("error", "Directory not found")
        }
        val action = Action(ActionRegistry.Names.FILESYSTEM_PUBLISH_NAVIGATION_FAILED, payload)

        val newState = feature.reducer(initialState, action)
        val newFsState = newState.featureStates[featureName] as? FileSystemState

        assertNotNull(newFsState)
        assertEquals("Directory not found", newFsState.error)
        assertTrue(newFsState.rootItems.isEmpty(), "rootItems should be cleared on failure.")
    }
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
        val action = Action(ActionRegistry.Names.SETTINGS_PUBLISH_LOADED, payload)
        val newState = feature.reducer(initialState, action) as FileSystemState
        assertEquals(setOf("path1", "path2"), newState.whitelistedPaths)
        assertEquals(setOf("fav1"), newState.favoritePaths)
    }
}