package app.auf.feature.filesystem

import app.auf.core.*
import app.auf.core.generated.ActionNames
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

    private val testActionRegistry = setOf(
        ActionNames.FILESYSTEM_EXPAND_ALL, ActionNames.FILESYSTEM_LOAD_CHILDREN,
        ActionNames.FILESYSTEM_TOGGLE_ITEM_SELECTED, ActionNames.FILESYSTEM_INTERNAL_DIRECTORY_LOADED,
        ActionNames.FILESYSTEM_ADD_WHITELIST_PATH, ActionNames.SETTINGS_UPDATE,
        ActionNames.FILESYSTEM_SYSTEM_WRITE, ActionNames.FILESYSTEM_SYSTEM_READ, ActionNames.FILESYSTEM_SYSTEM_DELETE_DIRECTORY
    )

    private data class CapturedPrivateData(val originator: String, val recipient: String, val envelope: PrivateDataEnvelope)

    private class TestStore(
        initialState: AppState,
        private val features: List<Feature>,
        platformDependencies: FakePlatformDependencies,
        validActionNames: Set<String>
    ) : Store(initialState, features, platformDependencies, validActionNames) {
        val dispatchedActions = mutableListOf<Action>()
        var capturedPrivateData: CapturedPrivateData? = null
        override fun dispatch(originator: String, action: Action) {
            val stampedAction = action.copy(originator = originator)
            dispatchedActions.add(stampedAction)
            super.dispatch(originator, action)
        }

        override fun deliverPrivateData(originator: String, recipient: String, envelope: PrivateDataEnvelope) {
            capturedPrivateData = CapturedPrivateData(originator, recipient, envelope)
            super.deliverPrivateData(originator, recipient, envelope)
        }
    }

    private fun createRunningStore(fsState: FileSystemState = FileSystemState()): TestStore {
        val initialState = AppState(
            featureStates = mapOf(
                feature.name to fsState,
                coreFeature.name to CoreState(lifecycle = AppLifecycle.RUNNING)
            )
        )
        return TestStore(initialState, listOf(feature, coreFeature), platform, testActionRegistry)
    }

    @Test
    fun `onAction EXPAND_ALL dispatches LOAD_CHILDREN for unloaded directories`() {
        val state = FileSystemState(rootItems = listOf(
            FileSystemItem("/a", "a", true, isExpanded = false, children = listOf(
                FileSystemItem("/a/b", "b", true, isExpanded = false, children = null)
            ))
        ))
        val store = createRunningStore(state)
        val action = Action(ActionNames.FILESYSTEM_EXPAND_ALL, buildJsonObject { put("path", JsonPrimitive("/a")) })

        feature.onAction(action.copy(originator = feature.name), store)

        val dispatchedAction = store.dispatchedActions.find { it.name == ActionNames.FILESYSTEM_LOAD_CHILDREN }
        assertNotNull(dispatchedAction, "LOAD_CHILDREN should have been dispatched.")
        assertEquals("/a/b", dispatchedAction.payload?.get("path")?.jsonPrimitive?.content)
    }

    @Test
    fun `onAction TOGGLE_ITEM_SELECTED recursive dispatches LOAD_CHILDREN for unloaded directories`() {
        val state = FileSystemState(rootItems = listOf(
            FileSystemItem("/a", "a", true, isSelected = false, children = null)
        ))
        val store = createRunningStore(state)
        val action = Action(ActionNames.FILESYSTEM_TOGGLE_ITEM_SELECTED, buildJsonObject {
            put("path", JsonPrimitive("/a"))
            put("recursive", JsonPrimitive(true))
        })

        feature.onAction(action.copy(originator = feature.name), store)

        val dispatchedAction = store.dispatchedActions.find { it.name == ActionNames.FILESYSTEM_LOAD_CHILDREN }
        assertNotNull(dispatchedAction, "LOAD_CHILDREN should have been dispatched.")
        assertEquals("/a", dispatchedAction.payload?.get("path")?.jsonPrimitive?.content)
    }

    @Test
    fun `onAction DIRECTORY_LOADED propagates selection to new children if parent is selected`() {
        val state = FileSystemState(rootItems = listOf(
            FileSystemItem("/a", "a", true, isSelected = true)
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
        val action = Action(ActionNames.FILESYSTEM_INTERNAL_DIRECTORY_LOADED, payload)

        feature.onAction(action.copy(originator = feature.name), store)

        val dispatchedAction = store.dispatchedActions.find { it.name == ActionNames.FILESYSTEM_TOGGLE_ITEM_SELECTED }
        assertNotNull(dispatchedAction, "TOGGLE_ITEM_SELECTED should have been dispatched for the new child.")
        assertEquals("/a/file.txt", dispatchedAction.payload?.get("path")?.jsonPrimitive?.content)
    }

    @Test
    fun `onAction ADD_WHITELIST_PATH dispatches settings UPDATE`() {
        val state = FileSystemState(whitelistedPaths = setOf("path1"))
        val store = createRunningStore(state)
        val action = Action(ActionNames.FILESYSTEM_ADD_WHITELIST_PATH, buildJsonObject { put("path", JsonPrimitive("path2")) })

        feature.onAction(action.copy(originator = feature.name), store)

        val dispatched = store.dispatchedActions.find { it.name == ActionNames.SETTINGS_UPDATE }
        assertNotNull(dispatched)
        assertEquals("filesystem.whitelistedPaths", dispatched.payload?.get("key")?.jsonPrimitive?.content)
        val value = dispatched.payload?.get("value")?.jsonPrimitive?.content ?: ""
        assertTrue(value.contains("path1"))
        assertTrue(value.contains("path2"))
    }

    @Test
    fun `SYSTEM_WRITE with encrypt flag writes encrypted content`() {
        val store = createRunningStore()
        val originalContent = "secret-api-key"
        val originator = "settings"
        val action = Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
            put("subpath", "test.json")
            put("content", originalContent)
            put("encrypt", true)
        })

        feature.onAction(action.copy(originator = originator), store)

        val sandboxPath = platform.getBasePathFor(BasePath.APP_ZONE) + "/$originator/test.json"
        val writtenContent = platform.readFileContent(sandboxPath)

        assertNotNull(writtenContent, "File should have been written.")
        assertNotEquals(originalContent, writtenContent, "Written content should be encrypted.")
        assertTrue(writtenContent.startsWith("[AUF_ENC_V1]"), "Written content should have the encryption prefix.")
    }

    @Test
    fun `SYSTEM_READ on encrypted file delivers decrypted content`() {
        val store = createRunningStore()
        val originalContent = "secret-api-key"
        val originator = "settings"
        val subpath = "test.json"
        val sandboxPath = platform.getBasePathFor(BasePath.APP_ZONE) + "/$originator/$subpath"

        val encryptedContent = CryptoManager().encrypt(originalContent)
        platform.writeFileContent(sandboxPath, encryptedContent)

        val action = Action(ActionNames.FILESYSTEM_SYSTEM_READ, buildJsonObject {
            put("subpath", subpath)
        })

        feature.onAction(action.copy(originator = originator), store)

        val privateData = store.capturedPrivateData
        assertNotNull(privateData, "Private data should have been delivered.")
        assertEquals("filesystem.response.read.v1", privateData.envelope.type)
        val payload = privateData.envelope.payload
        assertEquals(subpath, payload["subpath"]?.jsonPrimitive?.content)
        assertEquals(originalContent, payload["content"]?.jsonPrimitive?.content, "Delivered content should be the decrypted original.")
    }

    @Test
    fun `SYSTEM_DELETE_DIRECTORY recursively deletes a directory and its contents`() {
        val store = createRunningStore()
        val originator = "agent"
        val dirSubpath = "agent-to-delete"
        val fileSubpath = "$dirSubpath/agent.json"
        val sandboxPath = platform.getBasePathFor(BasePath.APP_ZONE) + "/$originator"
        val fullDirPath = "$sandboxPath/$dirSubpath"
        val fullFilePath = "$sandboxPath/$fileSubpath"

        platform.createDirectories(fullDirPath)
        platform.writeFileContent(fullFilePath, "{}")
        assertTrue(platform.fileExists(fullDirPath), "Precondition: Directory should exist.")
        assertTrue(platform.fileExists(fullFilePath), "Precondition: File inside directory should exist.")

        val action = Action(ActionNames.FILESYSTEM_SYSTEM_DELETE_DIRECTORY, buildJsonObject {
            put("subpath", dirSubpath)
        })

        feature.onAction(action.copy(originator = originator), store)

        assertFalse(platform.fileExists(fullDirPath), "Directory should have been deleted.")
        assertFalse(platform.fileExists(fullFilePath), "File inside directory should have been deleted.")
    }
}