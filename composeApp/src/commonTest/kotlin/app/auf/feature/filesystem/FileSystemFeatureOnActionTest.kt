package app.auf.feature.filesystem

import app.auf.core.Action
import app.auf.core.AppState
import app.auf.fakes.FakePlatformDependencies
import app.auf.fakes.FakeStore
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileSystemFeatureOnActionTest {

    private val platform = FakePlatformDependencies("v2-test")
    private val feature = FileSystemFeature(platform)
    private val featureName = feature.name

    private fun createAppState(fsState: FileSystemState = FileSystemState()) = AppState(
        featureStates = mapOf(featureName to fsState)
    )

    @Test
    fun `onAction EXPAND_ALL dispatches LOAD_CHILDREN for unloaded directories`() {
        // Arrange
        val state = createAppState(FileSystemState(rootItems = listOf(
            FileSystemItem("/a", "a", true, isExpanded = false, children = listOf(
                FileSystemItem("/a/b", "b", true, isExpanded = false, children = null) // Unloaded
            ))
        )))
        val fakeStore = FakeStore(state, platform)
        val action = Action("filesystem.EXPAND_ALL", buildJsonObject { put("path", JsonPrimitive("/a")) })

        // Act
        feature.onAction(action, fakeStore)

        // Assert
        val dispatchedAction = fakeStore.dispatchedActions.find { it.name == "filesystem.LOAD_CHILDREN" }
        assertNotNull(dispatchedAction, "LOAD_CHILDREN should have been dispatched.")
        assertEquals("/a/b", dispatchedAction.payload?.get("path")?.jsonPrimitive?.content)
    }

    @Test
    fun `onAction TOGGLE_ITEM_SELECTED recursive dispatches LOAD_CHILDREN for unloaded directories`() {
        // Arrange
        val state = createAppState(FileSystemState(rootItems = listOf(
            FileSystemItem("/a", "a", true, isSelected = false, children = null) // Unloaded
        )))
        val fakeStore = FakeStore(state, platform)
        val action = Action("filesystem.TOGGLE_ITEM_SELECTED", buildJsonObject {
            put("path", JsonPrimitive("/a"))
            put("recursive", JsonPrimitive(true))
        })

        // Act
        feature.onAction(action, fakeStore)

        // Assert
        val dispatchedAction = fakeStore.dispatchedActions.find { it.name == "filesystem.LOAD_CHILDREN" }
        assertNotNull(dispatchedAction, "LOAD_CHILDREN should have been dispatched.")
        assertEquals("/a", dispatchedAction.payload?.get("path")?.jsonPrimitive?.content)
    }

    @Test
    fun `onAction DIRECTORY_LOADED propagates selection to new children if parent is selected`() {
        // Arrange
        val state = createAppState(FileSystemState(rootItems = listOf(
            FileSystemItem("/a", "a", true, isSelected = true) // Parent is selected
        )))
        val fakeStore = FakeStore(state, platform)
        val payload = buildJsonObject {
            put("parentPath", JsonPrimitive("/a"))
            putJsonArray("children") {
                add(buildJsonObject {
                    put("path", JsonPrimitive("/a/file.txt"))
                    put("isDirectory", JsonPrimitive(false))
                })
            }
        }
        val action = Action("filesystem.DIRECTORY_LOADED", payload)

        // Act
        feature.onAction(action, fakeStore)

        // Assert
        val dispatchedAction = fakeStore.dispatchedActions.find { it.name == "filesystem.TOGGLE_ITEM_SELECTED" }
        assertNotNull(dispatchedAction, "TOGGLE_ITEM_SELECTED should have been dispatched for the new child.")
        assertEquals("/a/file.txt", dispatchedAction.payload?.get("path")?.jsonPrimitive?.content)
    }

    @Test
    fun `onAction ADD_WHITELIST_PATH dispatches settings UPDATE`() {
        val state = createAppState(FileSystemState(whitelistedPaths = setOf("path1")))
        val fakeStore = FakeStore(state, platform)
        val action = Action("filesystem.ADD_WHITELIST_PATH", buildJsonObject { put("path", JsonPrimitive("path2")) })

        feature.onAction(action, fakeStore)

        val dispatched = fakeStore.dispatchedActions.find { it.name == "settings.UPDATE" }
        assertNotNull(dispatched)
        assertEquals("filesystem.whitelistedPaths", dispatched.payload?.get("key")?.jsonPrimitive?.content)
        // Order is not guaranteed in sets, so check for presence of both items
        val value = dispatched.payload?.get("value")?.jsonPrimitive?.content ?: ""
        assertTrue(value.contains("path1"))
        assertTrue(value.contains("path2"))
    }
}