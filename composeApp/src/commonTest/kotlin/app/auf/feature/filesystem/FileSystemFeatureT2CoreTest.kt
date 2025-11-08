package app.auf.feature.filesystem

import app.auf.core.Action
import app.auf.core.PrivateDataEnvelope
import app.auf.fakes.FakePlatformDependencies
import app.auf.core.generated.ActionNames
import app.auf.test.TestEnvironment
import app.auf.util.BasePath
import app.auf.util.FileEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tier 2 Core Test for FileSystemFeature.
 *
 * Mandate (P-TEST-001, T2): To test the feature's reducer and onAction handlers working
 * together within a realistic TestEnvironment that includes the real Store.
 */
class FileSystemFeatureT2CoreTest {

    @Test
    fun `onAction EXPAND_ALL dispatches LOAD_CHILDREN for unloaded directories`() {
        val platform = FakePlatformDependencies("test")
        val feature = FileSystemFeature(platform)
        val initialState = FileSystemState(rootItems = listOf(
            FileSystemItem("/a", "a", true, isExpanded = false, children = listOf(
                FileSystemItem("/a/b", "b", true, isExpanded = false, children = null)
            ))
        ))
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState(feature.name, initialState)
            .build(platform = platform)
        val action = Action(ActionNames.FILESYSTEM_EXPAND_ALL, buildJsonObject { put("path", "/a") })

        harness.store.dispatch(feature.name, action)

        val dispatchedAction = harness.processedActions.find { it.name == ActionNames.FILESYSTEM_LOAD_CHILDREN }
        assertNotNull(dispatchedAction, "LOAD_CHILDREN should have been dispatched.")
        assertEquals("/a/b", dispatchedAction.payload?.get("path")?.jsonPrimitive?.content)
    }

    @Test
    fun `onAction DIRECTORY_LOADED propagates selection to new children if parent is selected`() {
        val platform = FakePlatformDependencies("test")
        val feature = FileSystemFeature(platform)
        val initialState = FileSystemState(rootItems = listOf(
            FileSystemItem("/a", "a", true, isSelected = true)
        ))
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState(feature.name, initialState)
            .build(platform = platform)
        // FIX: The `children` payload must be a correctly serialized JSON array of FileEntry objects.
        val payload = buildJsonObject {
            put("parentPath", "/a")
            put("children", Json.encodeToJsonElement(listOf(
                FileEntry("/a/file.txt", false)
            )))
        }
        val action = Action(ActionNames.FILESYSTEM_INTERNAL_DIRECTORY_LOADED, payload)

        harness.store.dispatch(feature.name, action)

        val dispatchedAction = harness.processedActions.find { it.name == ActionNames.FILESYSTEM_TOGGLE_ITEM_SELECTED }
        assertNotNull(dispatchedAction, "TOGGLE_ITEM_SELECTED should have been dispatched for the new child.")
        assertEquals("/a/file.txt", dispatchedAction.payload?.get("path")?.jsonPrimitive?.content)
    }

    @Test
    fun `SYSTEM_WRITE with encrypt flag writes encrypted content`() {
        val platform = FakePlatformDependencies("test")
        val feature = FileSystemFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        val originalContent = "secret-api-key"
        val originator = "settings"
        val action = Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
            put("subpath", "test.json")
            put("content", originalContent)
            put("encrypt", true)
        })

        harness.store.dispatch(originator, action)

        val sandboxPath = platform.getBasePathFor(BasePath.APP_ZONE) + "/$originator/test.json"
        val writtenContent = platform.readFileContent(sandboxPath)

        assertNotNull(writtenContent, "File should have been written.")
        assertNotEquals(originalContent, writtenContent, "Written content should be encrypted.")
        assertTrue(writtenContent.startsWith("[AUF_ENC_V1]"), "Written content should have the encryption prefix.")
    }

    @Test
    fun `SYSTEM_READ on encrypted file delivers decrypted content`() {
        val platform = FakePlatformDependencies("test")
        val feature = FileSystemFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        val originalContent = "secret-api-key"
        val originator = "settings"
        val subpath = "test.json"
        val sandboxPath = platform.getBasePathFor(BasePath.APP_ZONE) + "/$originator/$subpath"
        val encryptedContent = CryptoManager().encrypt(originalContent)
        platform.writeFileContent(sandboxPath, encryptedContent)
        val action = Action(ActionNames.FILESYSTEM_SYSTEM_READ, buildJsonObject {
            put("subpath", subpath)
        })

        harness.store.dispatch(originator, action)

        val privateData = harness.deliveredPrivateData.firstOrNull()
        assertNotNull(privateData, "Private data should have been delivered.")
        assertEquals(ActionNames.Envelopes.FILESYSTEM_RESPONSE_READ, privateData.envelope.type)
        val payload = privateData.envelope.payload
        assertEquals(subpath, payload["subpath"]?.jsonPrimitive?.content)
        assertEquals(originalContent, payload["content"]?.jsonPrimitive?.content, "Delivered content should be the decrypted original.")
    }

    @Test
    fun `SYSTEM_DELETE_DIRECTORY recursively deletes a directory and its contents`() {
        val platform = FakePlatformDependencies("test")
        val feature = FileSystemFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
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

        harness.store.dispatch(originator, action)

        assertFalse(platform.fileExists(fullDirPath), "Directory should have been deleted.")
        assertFalse(platform.fileExists(fullFilePath), "File inside directory should have been deleted.")
    }

    @Test
    fun `REQUEST_SCOPED_READ_UI waits for confirmation and then executes file dialog`() {
        // --- 1. ARRANGE ---
        val platform = FakePlatformDependencies("test")
        val feature = FileSystemFeature(platform)
        // The harness includes the real Store and CoreFeature, which is necessary for this test.
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .build(platform = platform)
        val originator = "knowledgegraph"
        val requestAction = Action(ActionNames.FILESYSTEM_REQUEST_SCOPED_READ_UI, buildJsonObject {
            put("recursive", true)
        })

        // --- 2. ACT 1: Dispatch the initial request ---
        harness.store.dispatch(originator, requestAction)

        // --- 3. ASSERT 1: The feature correctly asks for confirmation ---
        // Verify the OS dialog was NOT called yet.
        assertEquals(null, platform.selectedDirectoryPathToReturn, "The OS dialog should not be shown before confirmation.")

        // Verify a confirmation dialog was requested.
        val confirmationRequestAction = harness.processedActions.find { it.name == ActionNames.CORE_SHOW_CONFIRMATION_DIALOG }
        assertNotNull(confirmationRequestAction, "A confirmation dialog should have been requested.")
        val requestId = confirmationRequestAction.payload?.get("requestId")?.jsonPrimitive?.content
        assertNotNull(requestId, "The confirmation request must have a requestId.")

        // Verify the original request is now pending in the state.
        val stateAfterRequest = harness.store.state.value.featureStates[feature.name] as FileSystemState
        assertNotNull(stateAfterRequest.pendingScopedRead, "The original request should be stored as pending.")
        assertEquals(requestId, stateAfterRequest.pendingScopedRead?.requestId)

        // --- 4. ACT 2: Simulate the user confirming the dialog ---
        platform.selectedDirectoryPathToReturn = "/fake/selected/path" // Pre-configure the fake dialog's return value.
        val confirmationResponse = PrivateDataEnvelope(
            type = ActionNames.Envelopes.CORE_RESPONSE_CONFIRMATION,
            payload = buildJsonObject {
                put("requestId", requestId)
                put("confirmed", true)
            }
        )
        harness.store.deliverPrivateData("core", feature.name, confirmationResponse)

        // --- 5. ASSERT 2: The feature correctly resumes the workflow ---
        val executeAction = harness.processedActions.find { it.name == ActionNames.FILESYSTEM_INTERNAL_EXECUTE_SCOPED_READ }
        assertNotNull(executeAction, "EXECUTE_SCOPED_READ should be dispatched after confirmation.")
        assertEquals("/fake/selected/path", executeAction.payload?.get("path")?.jsonPrimitive?.content)

        // Verify the pending request is cleared from the state.
        val finalState = harness.store.state.value.featureStates[feature.name] as FileSystemState
        assertNull(finalState.pendingScopedRead, "The pending request should be cleared after being handled.")
    }
}