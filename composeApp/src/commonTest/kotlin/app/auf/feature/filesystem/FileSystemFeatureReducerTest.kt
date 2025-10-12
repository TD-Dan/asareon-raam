package app.auf.feature.filesystem

import app.auf.core.Action
import app.auf.core.AppState
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

class FileSystemFeatureReducerTest {

    private val platform = FakePlatformDependencies("v2-test")
    private val feature = FileSystemFeature(platform)
    private val featureName = feature.name

    private fun createAppState(fsState: FileSystemState = FileSystemState()) = AppState(
        featureStates = mapOf(featureName to fsState)
    )

    // --- Existing Tests (Verified) ---

    @Test
    fun `reducer NAVIGATION_UPDATED correctly updates path and rootItems and clears error`() {
        // Arrange
        platform.directories.add("/test")
        val initialState = createAppState(FileSystemState(error = "Old error"))
        val payload = buildJsonObject {
            put("parentPath", "/test")
            putJsonArray("children") {
                add(buildJsonObject {
                    put("path", "/test/file.txt")
                    put("isDirectory", false)
                })
            }
        }
        val action = Action("filesystem.internal.DIRECTORY_LOADED", payload)

        // Act
        // To test correctly, we must first be in the navigating state
        val navigatingState = feature.reducer(initialState, Action("filesystem.NAVIGATE", buildJsonObject { put("path", "/test")}))
        val newState = feature.reducer(navigatingState, action)
        val newFsState = newState.featureStates[featureName] as? FileSystemState

        // Assert
        assertNotNull(newFsState)
        assertEquals("/test", newFsState.currentPath)
        assertEquals(1, newFsState.rootItems.size)
        assertEquals("file.txt", newFsState.rootItems.first().name)
        assertNull(newFsState.error, "Error should be cleared on a successful navigation.")
    }

    @Test
    fun `reducer NAVIGATION_FAILED sets error message and clears rootItems`() {
        // Arrange
        val initialState = createAppState(FileSystemState(
            rootItems = listOf(FileSystemItem("/old/path", "path", true))
        ))
        val payload = buildJsonObject {
            put("path", "/bad/path")
            put("error", "Directory not found")
        }
        val action = Action("filesystem.publish.NAVIGATION_FAILED", payload)

        // Act
        val newState = feature.reducer(initialState, action)
        val newFsState = newState.featureStates[featureName] as? FileSystemState

        // Assert
        assertNotNull(newFsState)
        assertEquals("Directory not found", newFsState.error)
        assertTrue(newFsState.rootItems.isEmpty(), "rootItems should be cleared on failure.")
    }

    // --- New Tests for Recent Features ---

    @Test
    fun `reducer ADD_WHITELIST_PATH adds path to whitelist`() {
        val initialState = createAppState()
        val action = Action("filesystem.ADD_WHITELIST_PATH", buildJsonObject { put("path", "/safe/path") })
        val newState = feature.reducer(initialState, action)
        val newFsState = newState.featureStates[featureName] as FileSystemState
        assertTrue(newFsState.whitelistedPaths.contains("/safe/path"))
        assertEquals(1, newFsState.whitelistedPaths.size)
    }

    @Test
    fun `reducer REMOVE_WHITELIST_PATH removes path from whitelist`() {
        val initialState = createAppState(FileSystemState(whitelistedPaths = setOf("/safe/path", "/other")))
        val action = Action("filesystem.REMOVE_WHITELIST_PATH", buildJsonObject { put("path", "/safe/path") })
        val newState = feature.reducer(initialState, action)
        val newFsState = newState.featureStates[featureName] as FileSystemState
        assertFalse(newFsState.whitelistedPaths.contains("/safe/path"))
        assertTrue(newFsState.whitelistedPaths.contains("/other"))
        assertEquals(1, newFsState.whitelistedPaths.size)
    }

    @Test
    fun `reducer ADD_FAVORITE_PATH adds path to favorites`() {
        val initialState = createAppState()
        val action = Action("filesystem.ADD_FAVORITE_PATH", buildJsonObject { put("path", "/fav/path") })
        val newState = feature.reducer(initialState, action)
        val newFsState = newState.featureStates[featureName] as FileSystemState
        assertTrue(newFsState.favoritePaths.contains("/fav/path"))
    }

    @Test
    fun `reducer REMOVE_FAVORITE_PATH removes path from favorites`() {
        val initialState = createAppState(FileSystemState(favoritePaths = setOf("/fav/path")))
        val action = Action("filesystem.REMOVE_FAVORITE_PATH", buildJsonObject { put("path", "/fav/path") })
        val newState = feature.reducer(initialState, action)
        val newFsState = newState.featureStates[featureName] as FileSystemState
        assertFalse(newFsState.favoritePaths.contains("/fav/path"))
    }

    @Test
    fun `reducer EXPAND_ALL recursively expands known children`() {
        val initialState = createAppState(FileSystemState(rootItems = listOf(
            FileSystemItem("/a", "a", true, children = listOf(
                FileSystemItem("/a/b", "b", true, children = emptyList(), isExpanded = false)
            ), isExpanded = false)
        )))
        val action = Action("filesystem.EXPAND_ALL", buildJsonObject { put("path", "/a") })
        val newState = feature.reducer(initialState, action)
        val newFsState = newState.featureStates[featureName] as FileSystemState
        val itemA = newFsState.rootItems.first()
        val itemB = itemA.children?.first()
        assertTrue(itemA.isExpanded, "Parent should be expanded")
        assertNotNull(itemB)
        assertTrue(itemB.isExpanded, "Child should be expanded")
    }

    @Test
    fun `reducer COLLAPSE_ALL recursively collapses known children`() {
        val initialState = createAppState(FileSystemState(rootItems = listOf(
            FileSystemItem("/a", "a", true, children = listOf(
                FileSystemItem("/a/b", "b", true, children = emptyList(), isExpanded = true)
            ), isExpanded = true)
        )))
        val action = Action("filesystem.COLLAPSE_ALL", buildJsonObject { put("path", "/a") })
        val newState = feature.reducer(initialState, action)
        val newFsState = newState.featureStates[featureName] as FileSystemState
        val itemA = newFsState.rootItems.first()
        val itemB = itemA.children?.first()
        assertFalse(itemA.isExpanded, "Parent should be collapsed")
        assertNotNull(itemB)
        assertFalse(itemB.isExpanded, "Child should be collapsed")
    }

    @Test
    fun `reducer TOGGLE_ITEM_SELECTED recursively selects known children`() {
        val initialState = createAppState(FileSystemState(rootItems = listOf(
            FileSystemItem("/a", "a", true, isSelected = false, children = listOf(
                FileSystemItem("/a/file.txt", "file.txt", false, isSelected = false)
            ))
        )))
        val action = Action("filesystem.TOGGLE_ITEM_SELECTED", buildJsonObject {
            put("path", "/a")
            put("recursive", true)
        })
        val newState = feature.reducer(initialState, action)
        val newFsState = newState.featureStates[featureName] as FileSystemState
        val itemA = newFsState.rootItems.first()
        val itemFile = itemA.children?.first()
        assertTrue(itemA.isSelected, "Parent directory should be selected")
        assertNotNull(itemFile)
        assertTrue(itemFile.isSelected, "Child file should be selected")
    }

    @Test
    fun `reducer hydrates state from settings LOADED action`() {
        val initialState = createAppState()
        val payload = buildJsonObject {
            put("filesystem.whitelistedPaths", "path1,path2")
            put("filesystem.favoritePaths", "fav1")
        }
        val action = Action("settings.publish.LOADED", payload)
        val newState = feature.reducer(initialState, action)
        val newFsState = newState.featureStates[featureName] as FileSystemState
        assertEquals(setOf("path1", "path2"), newFsState.whitelistedPaths)
        assertEquals(setOf("fav1"), newFsState.favoritePaths)
    }
}