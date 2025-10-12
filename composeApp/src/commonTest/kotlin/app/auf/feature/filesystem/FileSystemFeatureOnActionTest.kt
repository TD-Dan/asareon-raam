package app.auf.feature.filesystem

import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.Feature
import app.auf.core.Store
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreFeature
import app.auf.feature.core.CoreState
import app.auf.util.BasePath
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
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
        var capturedPrivateData: Any? = null
        override fun dispatch(originator: String, action: Action) {
            val stampedAction = action.copy(originator = originator)
            dispatchedActions.add(stampedAction)
            super.dispatch(originator, action) // Pass stamped action to super
        }

        // Override to capture data for assertion
        override fun deliverPrivateData(originator: String, recipient: String, data: Any) {
            capturedPrivateData = data
            super.deliverPrivateData(originator, recipient, data)
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
        // We must dispatch from the feature itself to trigger the onAction handler
        feature.onAction(action.copy(originator = feature.name), store)

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
        feature.onAction(action.copy(originator = feature.name), store)

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
        val action = Action("filesystem.internal.DIRECTORY_LOADED", payload)

        // Act
        feature.onAction(action.copy(originator = feature.name), store)

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

        feature.onAction(action.copy(originator = feature.name), store)

        val dispatched = store.dispatchedActions.find { it.name == "settings.UPDATE" }
        assertNotNull(dispatched)
        assertEquals("filesystem.whitelistedPaths", dispatched.payload?.get("key")?.jsonPrimitive?.content)
        // Order is not guaranteed in sets, so check for presence of both items
        val value = dispatched.payload?.get("value")?.jsonPrimitive?.content ?: ""
        assertTrue(value.contains("path1"))
        assertTrue(value.contains("path2"))
    }

    @Test
    fun `SYSTEM_WRITE with encrypt flag writes encrypted content`() {
        // Arrange
        val store = createRunningStore()
        val originalContent = "secret-api-key"
        val originator = "settings"
        val action = Action("filesystem.SYSTEM_WRITE", buildJsonObject {
            put("subpath", "test.json")
            put("content", originalContent)
            put("encrypt", true) // The critical flag
        })

        // Act
        feature.onAction(action.copy(originator = originator), store)

        // Assert
        val sandboxPath = platform.getBasePathFor(BasePath.APP_ZONE) + "/$originator/test.json"
        val writtenContent = platform.readFileContent(sandboxPath)

        assertNotNull(writtenContent, "File should have been written.")
        assertNotEquals(originalContent, writtenContent, "Written content should be encrypted.")
        assertTrue(writtenContent.startsWith("[AUF_ENC_V1]"), "Written content should have the encryption prefix.")
    }

    @Test
    fun `SYSTEM_READ on encrypted file delivers decrypted content`() {
        // Arrange
        val store = createRunningStore()
        val originalContent = "secret-api-key"
        val originator = "settings"
        val subpath = "test.json"
        val sandboxPath = platform.getBasePathFor(BasePath.APP_ZONE) + "/$originator/$subpath"

        // Manually encrypt and write the file to the fake filesystem to set up the test condition.
        val encryptedContent = CryptoManager().encrypt(originalContent)
        platform.writeFileContent(sandboxPath, encryptedContent)

        val action = Action("filesystem.SYSTEM_READ", buildJsonObject {
            put("subpath", subpath)
        })

        // Act
        feature.onAction(action.copy(originator = originator), store)

        // Assert
        val privateData = store.capturedPrivateData as? JsonObject
        assertNotNull(privateData, "Private data should have been delivered.")
        assertEquals(subpath, privateData["subpath"]?.jsonPrimitive?.content)
        assertEquals(originalContent, privateData["content"]?.jsonPrimitive?.content, "Delivered content should be the decrypted original.")
    }

    @Test
    fun `SYSTEM_DELETE_DIRECTORY recursively deletes a directory and its contents`() {
        // Arrange
        val store = createRunningStore()
        val originator = "agent"
        val dirSubpath = "agent-to-delete"
        val fileSubpath = "$dirSubpath/agent.json"
        val sandboxPath = platform.getBasePathFor(BasePath.APP_ZONE) + "/$originator"
        val fullDirPath = "$sandboxPath/$dirSubpath"
        val fullFilePath = "$sandboxPath/$fileSubpath"

        // Manually create the directory and a file inside it on the fake platform.
        platform.createDirectories(fullDirPath)
        platform.writeFileContent(fullFilePath, "{}")
        assertTrue(platform.fileExists(fullDirPath), "Precondition: Directory should exist.")
        assertTrue(platform.fileExists(fullFilePath), "Precondition: File inside directory should exist.")

        val action = Action("filesystem.SYSTEM_DELETE_DIRECTORY", buildJsonObject {
            put("subpath", dirSubpath)
        })

        // Act
        feature.onAction(action.copy(originator = originator), store)

        // Assert
        // This test relies on the FakePlatformDependencies correctly implementing recursive delete.
        assertFalse(platform.fileExists(fullDirPath), "Directory should have been deleted.")
        assertFalse(platform.fileExists(fullFilePath), "File inside directory should have been deleted.")
    }
}