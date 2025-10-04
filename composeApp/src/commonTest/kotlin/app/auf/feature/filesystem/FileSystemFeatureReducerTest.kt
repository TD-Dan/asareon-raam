package app.auf.feature.filesystem

import app.auf.core.Action
import app.auf.core.AppState
import app.auf.fakes.FakePlatformDependencies
import app.auf.util.FileEntry
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FileSystemFeatureReducerTest {

    private val testAppVersion = "2.0.0-test"
    private val platform = FakePlatformDependencies(testAppVersion)
    private val feature = FileSystemFeature(platform)
    private val featureName = feature.name

    private fun createAppState(fsState: FileSystemState) = AppState(
        featureStates = mapOf(featureName to fsState)
    )

    @Test
    fun `reducer NAVIGATION_UPDATED correctly updates path and listing and clears error`() {
        // Arrange
        val initialState = createAppState(FileSystemState(error = "Old error"))
        val fileListing = listOf(FileEntry("/test/file.txt", false))
        val payload = buildJsonObject {
            put("path", "/test")
            putJsonArray("listing") {
                add(buildJsonObject {
                    put("path", "/test/file.txt")
                    put("isDirectory", false)
                })
            }
        }
        val action = Action("filesystem.NAVIGATION_UPDATED", payload)

        // Act
        val newState = feature.reducer(initialState, action)
        val newFsState = newState.featureStates[featureName] as? FileSystemState

        // Assert
        assertNotNull(newFsState)
        assertEquals("/test", newFsState.currentPath)
        assertEquals(fileListing, newFsState.currentDirectoryListing)
        assertNull(newFsState.error, "Error should be cleared on a successful navigation.")
    }

    @Test
    fun `reducer NAVIGATION_FAILED sets error message and clears listing`() {
        // Arrange
        val initialState = createAppState(FileSystemState(
            currentDirectoryListing = listOf(FileEntry("/old/path", true))
        ))
        val payload = buildJsonObject {
            put("path", "/bad/path")
            put("error", "Directory not found")
        }
        val action = Action("filesystem.NAVIGATION_FAILED", payload)

        // Act
        val newState = feature.reducer(initialState, action)
        val newFsState = newState.featureStates[featureName] as? FileSystemState

        // Assert
        assertNotNull(newFsState)
        assertEquals("Directory not found", newFsState.error)
        assertEquals(emptyList(), newFsState.currentDirectoryListing, "Listing should be cleared on failure.")
    }

    @Test
    fun `reducer STAGE_CREATE adds a create operation to the staged list`() {
        // Arrange
        val initialState = createAppState(FileSystemState())
        val payload = buildJsonObject {
            put("path", "/new/file.txt")
            put("content", "Hello")
        }
        val action = Action("filesystem.STAGE_CREATE", payload)

        // Act
        val newState = feature.reducer(initialState, action)
        val newFsState = newState.featureStates[featureName] as? FileSystemState

        // Assert
        assertNotNull(newFsState)
        assertEquals(1, newFsState.stagedOperations.size)
        val operation = newFsState.stagedOperations.first() as FileOperation.Create
        assertEquals("/new/file.txt", operation.path)
        assertEquals("Hello", operation.content)
    }

    @Test
    fun `reducer STAGE_DELETE adds a delete operation to the staged list`() {
        // Arrange
        val initialState = createAppState(FileSystemState(stagedOperations = listOf(
            FileOperation.Create("/other/file.txt", "content")
        )))
        val payload = buildJsonObject { put("path", "/to/delete.txt") }
        val action = Action("filesystem.STAGE_DELETE", payload)

        // Act
        val newState = feature.reducer(initialState, action)
        val newFsState = newState.featureStates[featureName] as? FileSystemState

        // Assert
        assertNotNull(newFsState)
        assertEquals(2, newFsState.stagedOperations.size)
        val operation = newFsState.stagedOperations.last() as FileOperation.Delete
        assertEquals("/to/delete.txt", operation.path)
    }

    @Test
    fun `reducer DISCARD clears all staged operations`() {
        // Arrange
        val initialState = createAppState(FileSystemState(stagedOperations = listOf(
            FileOperation.Delete("/file1.txt"),
            FileOperation.Create("/file2.txt", "content")
        )))
        val action = Action("filesystem.DISCARD")

        // Act
        val newState = feature.reducer(initialState, action)
        val newFsState = newState.featureStates[featureName] as? FileSystemState

        // Assert
        assertNotNull(newFsState)
        assertEquals(0, newFsState.stagedOperations.size, "Staged operations should be empty after discard.")
    }

    @Test
    fun `reducer ignores unknown actions`() {
        // Arrange
        val initialState = createAppState(FileSystemState())
        val action = Action("some.other.feature.ACTION")

        // Act
        val newState = feature.reducer(initialState, action)

        // Assert
        assertEquals(initialState, newState, "State should not change for an unknown action.")
    }
}