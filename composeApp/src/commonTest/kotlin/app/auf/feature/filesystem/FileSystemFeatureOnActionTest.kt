package app.auf.feature.filesystem

import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.Feature
import app.auf.core.Store
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreFeature
import app.auf.feature.core.CoreState
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
    private val coreFeature = CoreFeature(platform)

    /** A high-fidelity store that includes the CoreFeature to manage lifecycle state. */
    private class TestStore(
        initialState: AppState,
        private val features: List<Feature>,
        platformDependencies: FakePlatformDependencies
    ) : Store(initialState, features, platformDependencies) {
        val dispatchedActions = mutableListOf<Action>()
        override fun dispatch(originator: String, action: Action) {
            val stampedAction = action.copy(originator = originator)
            dispatchedActions.add(stampedAction)
            super.dispatch(originator, stampedAction) // Pass stamped action to super
        }
    }

    /** Helper to create a store that is already in the RUNNING state. */
    private fun createRunningStore(fsState: FileSystemState = FileSystemState()): TestStore {
        val initialState = AppState(
            featureStates = mapOf(
                feature.name to fsState,
                coreFeature.name to CoreState(lifecycle = AppLifecycle.RUNNING)
            )
        )
        return TestStore(initialState, listOf(feature, coreFeature), platform)
    }

    @Test
    fun `onAction EXPAND_ALL dispatches LOAD_CHILDREN for unloaded directories`() {
        // Arrange
        val state = FileSystemState(rootItems = listOf(
            FileSystemItem("/a", "a", true, isExpanded = false, children = listOf(
                FileSystemItem("/a/b", "b", true, isExpanded = false, children = null) // Unloaded
            ))
        ))
        val store = createRunningStore(state)
        val action = Action("filesystem.EXPAND_ALL", buildJsonObject { put("path", JsonPrimitive("/a")) })

        // Act
        store.dispatch(feature.name, action)

        // Assert
        val dispatchedAction = store.dispatchedActions.find { it.name == "filesystem.LOAD_CHILDREN" }
        assertNotNull(dispatchedAction, "LOAD_CHILDREN should have been dispatched.")
        assertEquals("/a/b", dispatchedAction.payload?.get("path")?.jsonPrimitive?.content)
    }

    @Test
    fun `onAction TOGGLE_ITEM_SELECTED recursive dispatches LOAD_CHILDREN for unloaded directories`() {
        // Arrange
        val state = FileSystemState(rootItems = listOf(
            FileSystemItem("/a", "a", true, isSelected = false, children = null) // Unloaded
        ))
        val store = createRunningStore(state)
        val action = Action("filesystem.TOGGLE_ITEM_SELECTED", buildJsonObject {
            put("path", JsonPrimitive("/a"))
            put("recursive", JsonPrimitive(true))
        })

        // Act
        store.dispatch(feature.name, action)

        // Assert
        val dispatchedAction = store.dispatchedActions.find { it.name == "filesystem.LOAD_CHILDREN" }
        assertNotNull(dispatchedAction, "LOAD_CHILDREN should have been dispatched.")
        assertEquals("/a", dispatchedAction.payload?.get("path")?.jsonPrimitive?.content)
    }

    @Test
    fun `onAction DIRECTORY_LOADED propagates selection to new children if parent is selected`() {
        // Arrange
        val state = FileSystemState(rootItems = listOf(
            FileSystemItem("/a", "a", true, isSelected = true) // Parent is selected
        ))
        val store = createRunningStore(state)
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
        store.dispatch(feature.name, action)

        // Assert
        val dispatchedAction = store.dispatchedActions.find { it.name == "filesystem.TOGGLE_ITEM_SELECTED" }
        assertNotNull(dispatchedAction, "TOGGLE_ITEM_SELECTED should have been dispatched for the new child.")
        assertEquals("/a/file.txt", dispatchedAction.payload?.get("path")?.jsonPrimitive?.content)
    }

    @Test
    fun `onAction ADD_WHITELIST_PATH dispatches settings UPDATE`() {
        val state = FileSystemState(whitelistedPaths = setOf("path1"))
        val store = createRunningStore(state)
        val action = Action("filesystem.ADD_WHITELIST_PATH", buildJsonObject { put("path", JsonPrimitive("path2")) })

        store.dispatch(feature.name, action)

        val dispatched = store.dispatchedActions.find { it.name == "settings.UPDATE" }
        assertNotNull(dispatched)
        assertEquals("filesystem.whitelistedPaths", dispatched.payload?.get("key")?.jsonPrimitive?.content)
        // Order is not guaranteed in sets, so check for presence of both items
        val value = dispatched.payload?.get("value")?.jsonPrimitive?.content ?: ""
        assertTrue(value.contains("path1"))
        assertTrue(value.contains("path2"))
    }
}