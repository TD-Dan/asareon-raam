package app.auf.feature.filesystem

import app.auf.core.Action
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

    /*
    // TODO: This test is disabled because it tests the non-sandboxed file access pattern, which
    // has been removed and is pending a new design for user-initiated workflows like "import".
    @Test
    fun `READ_DIRECTORY_CONTENTS delivers recursive file listing via private channel`() {
        // Arrange
        val platform = FakePlatformDependencies("test")
        val feature = FileSystemFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        val originator = "knowledgegraph"
        val dirPath = "/import/source"
        platform.createDirectories(dirPath + "/subdir")
        platform.writeFileContent(dirPath + "/file1.json", "{}")
        platform.writeFileContent(dirPath + "/subdir/file2.json", "{}")

        val action = Action(ActionNames.FILESYSTEM_READ_DIRECTORY_CONTENTS, buildJsonObject {
            put("path", dirPath)
            put("recursive", true) // CORRECTED
        })

        // Act
        harness.store.dispatch(originator, action)

        // Assert
        assertEquals(1, harness.deliveredPrivateData.size, "Exactly one private data envelope should be delivered.")
        val delivery = harness.deliveredPrivateData.first()
        assertEquals(originator, delivery.recipient)
        assertEquals(feature.name, delivery.originator)
        assertEquals(ActionNames.Envelopes.FILESYSTEM_RESPONSE_DIRECTORY_CONTENTS, delivery.envelope.type)

        val payload = delivery.envelope.payload
        assertEquals(dirPath, payload["path"]?.jsonPrimitive?.content)
        val listing = Json.decodeFromJsonElement<List<FileEntry>>(payload["listing"]!!)
        assertEquals(2, listing.size)
        assertTrue(listing.any { it.path == "$dirPath/file1.json" })
        assertTrue(listing.any { it.path == "$dirPath/subdir/file2.json" })
    }

    @Test
    fun `READ_FILES_CONTENT delivers content map via private channel`() {
        // Arrange
        val platform = FakePlatformDependencies("test")
        val feature = FileSystemFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        val originator = "knowledgegraph"
        val path1 = "/import/file1.json"
        val path2 = "/import/file2.json"
        platform.writeFileContent(path1, "{\"key\": \"value1\"}")
        platform.writeFileContent(path2, "{\"key\": \"value2\"}")

        val action = Action(ActionNames.FILESYSTEM_READ_FILES_CONTENT, buildJsonObject {
            putJsonArray("paths") {
                add(JsonPrimitive(path1))
                add(JsonPrimitive(path2))
            }
        })

        // Act
        harness.store.dispatch(originator, action)

        // Assert
        assertEquals(1, harness.deliveredPrivateData.size)
        val delivery = harness.deliveredPrivateData.first()
        assertEquals(originator, delivery.recipient)
        assertEquals(ActionNames.Envelopes.FILESYSTEM_RESPONSE_FILES_CONTENT, delivery.envelope.type)

        val payload = delivery.envelope.payload
        val contents = payload["contents"]!!.jsonObject
        assertEquals(2, contents.size)
        assertEquals("{\"key\": \"value1\"}", contents[path1]?.jsonPrimitive?.content)
        assertEquals("{\"key\": \"value2\"}", contents[path2]?.jsonPrimitive?.content)
    }
    */
}