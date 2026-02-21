package app.auf.feature.filesystem

import app.auf.core.Action
import app.auf.fakes.FakePlatformDependencies
import app.auf.core.generated.ActionRegistry
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
 * Mandate (P-TEST-001, T2): To test the feature's reducer and handleSideEffects handlers
 * working together within a realistic TestEnvironment that includes the real Store.
 *
 * Phase 3 migration: All assertions migrated from deliveredPrivateData/PrivateDataEnvelope
 * to targeted Action assertions on processedActions.
 */
class FileSystemFeatureT2CoreTest {

    // ========================================================================
    // Existing Tests (Fixed for Phase 3)
    // ========================================================================

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
            .withInitialState(feature.identity.handle, initialState)
            .build(platform = platform)
        val action = Action(ActionRegistry.Names.FILESYSTEM_EXPAND_ALL, buildJsonObject { put("path", "/a") })

        harness.runAndLogOnFailure {
            harness.store.dispatch(feature.identity.handle, action)

            val dispatchedAction = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_LOAD_CHILDREN }
            assertNotNull(dispatchedAction, "LOAD_CHILDREN should have been dispatched.")
            assertEquals("/a/b", dispatchedAction.payload?.get("path")?.jsonPrimitive?.content)
        }
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
            .withInitialState(feature.identity.handle, initialState)
            .build(platform = platform)
        val payload = buildJsonObject {
            put("parentPath", "/a")
            put("children", Json.encodeToJsonElement(listOf(
                FileEntry("/a/file.txt", false)
            )))
        }
        val action = Action(ActionRegistry.Names.FILESYSTEM_DIRECTORY_LOADED, payload)

        harness.runAndLogOnFailure {
            harness.store.dispatch(feature.identity.handle, action)

            val dispatchedAction = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_TOGGLE_ITEM_SELECTED }
            assertNotNull(dispatchedAction, "TOGGLE_ITEM_SELECTED should have been dispatched for the new child.")
            assertEquals("/a/file.txt", dispatchedAction.payload?.get("path")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `SYSTEM_WRITE with encrypt flag writes encrypted content`() {
        val platform = FakePlatformDependencies("test")
        val feature = FileSystemFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        val originalContent = "secret-api-key"
        val originator = "settings"
        val action = Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
            put("path", "test.json")
            put("content", originalContent)
            put("encrypt", true)
        })

        harness.runAndLogOnFailure {
            harness.store.dispatch(originator, action)

            val sandboxPath = platform.getBasePathFor(BasePath.APP_ZONE) + "/$originator/test.json"
            val writtenContent = platform.readFileContent(sandboxPath)

            assertNotNull(writtenContent, "File should have been written.")
            assertNotEquals(originalContent, writtenContent, "Written content should be encrypted.")
            assertTrue(writtenContent.startsWith("[AUF_ENC_V1]"), "Written content should have the encryption prefix.")
        }
    }

    /**
     * Phase 3 FIX: Migrated from deliveredPrivateData/PrivateDataEnvelope assertions
     * to targeted Action assertions on processedActions.
     *
     * The production code now dispatches FILESYSTEM_RETURN_READ as a targeted Action
     * with targetRecipient = originator, instead of using deliverPrivateData.
     */
    @Test
    fun `SYSTEM_READ on encrypted file delivers decrypted content via targeted action`() {
        val platform = FakePlatformDependencies("test")
        val feature = FileSystemFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        val originalContent = "secret-api-key"
        val originator = "settings"
        val path = "test.json"
        val sandboxPath = platform.getBasePathFor(BasePath.APP_ZONE) + "/$originator/$path"
        val encryptedContent = CryptoManager(platform).encrypt(originalContent)
        platform.writeFileContent(sandboxPath, encryptedContent)
        val action = Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_READ, buildJsonObject {
            put("path", path)
        })

        harness.runAndLogOnFailure {
            harness.store.dispatch(originator, action)

            val responseAction = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_RETURN_READ }
            assertNotNull(responseAction, "A targeted RETURN_READ action should have been dispatched.")
            assertEquals(originator, responseAction.targetRecipient, "Response should be targeted to the originator.")
            val responsePayload = responseAction.payload
            assertNotNull(responsePayload)
            assertEquals(path, responsePayload["path"]?.jsonPrimitive?.content)
            assertEquals(originalContent, responsePayload["content"]?.jsonPrimitive?.content, "Delivered content should be the decrypted original.")
        }
    }

    @Test
    fun `SYSTEM_DELETE_DIRECTORY recursively deletes a directory and its contents`() {
        val platform = FakePlatformDependencies("test")
        val feature = FileSystemFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        val originator = "agent"
        val dirPath = "agent-to-delete"
        val filePath = "$dirPath/agent.json"
        val sandboxPath = platform.getBasePathFor(BasePath.APP_ZONE) + "/$originator"
        val fullDirPath = "$sandboxPath/$dirPath"
        val fullFilePath = "$sandboxPath/$filePath"
        platform.createDirectories(fullDirPath)
        platform.writeFileContent(fullFilePath, "{}")
        assertTrue(platform.fileExists(fullDirPath), "Precondition: Directory should exist.")
        assertTrue(platform.fileExists(fullFilePath), "Precondition: File inside directory should exist.")
        val action = Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_DELETE_DIRECTORY, buildJsonObject {
            put("path", dirPath)
        })

        harness.runAndLogOnFailure {
            harness.store.dispatch(originator, action)

            assertFalse(platform.fileExists(fullDirPath), "Directory should have been deleted.")
            assertFalse(platform.fileExists(fullFilePath), "File inside directory should have been deleted.")
        }
    }

    /**
     * Phase 3 FIX: Migrated from PrivateDataEnvelope/deliverPrivateData to targeted Action.
     *
     * The confirmation response is now dispatched by CoreFeature as a targeted Action
     * with name CORE_RETURN_CONFIRMATION and targetRecipient = "filesystem".
     */
    @Test
    fun `REQUEST_SCOPED_READ_UI waits for confirmation and then executes file dialog`() {
        val platform = FakePlatformDependencies("test")
        val feature = FileSystemFeature(platform)
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .build(platform = platform)
        val originator = "knowledgegraph"
        val requestAction = Action(ActionRegistry.Names.FILESYSTEM_REQUEST_SCOPED_READ_UI, buildJsonObject {
            put("recursive", true)
        })

        harness.runAndLogOnFailure {
            // --- ACT 1: Dispatch the initial request ---
            harness.store.dispatch(originator, requestAction)

            // --- ASSERT 1: The feature correctly stages the request and asks for confirmation ---
            val stageAction = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_STAGE_SCOPED_READ }
            assertNotNull(stageAction, "An internal action to stage the request should have been dispatched.")
            val requestId = stageAction.payload?.get("requestId")?.jsonPrimitive?.content
            assertNotNull(requestId, "The staging action must contain a requestId.")
            val stateAfterRequest = harness.store.state.value.featureStates[feature.identity.handle] as FileSystemState
            assertNotNull(stateAfterRequest.pendingScopedRead, "The original request should be stored as pending.")
            assertEquals(requestId, stateAfterRequest.pendingScopedRead?.requestId)
            assertEquals(originator, stateAfterRequest.pendingScopedRead?.originator)

            val confirmationRequestAction = harness.processedActions.find { it.name == ActionRegistry.Names.CORE_SHOW_CONFIRMATION_DIALOG }
            assertNotNull(confirmationRequestAction, "A confirmation dialog should have been requested.")

            // --- ACT 2: Simulate the user confirming the dialog via a targeted Action ---
            platform.selectedDirectoryPathToReturn = "/fake/selected/path"
            val confirmationAction = Action(
                name = ActionRegistry.Names.CORE_RETURN_CONFIRMATION,
                payload = buildJsonObject {
                    put("requestId", requestId)
                    put("confirmed", true)
                },
                targetRecipient = feature.identity.handle
            )
            harness.store.dispatch("core", confirmationAction)

            // --- ASSERT 2: The feature correctly resumes the workflow ---
            val executeAction = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_EXECUTE_SCOPED_READ }
            assertNotNull(executeAction, "EXECUTE_SCOPED_READ should be dispatched after confirmation.")

            // Verify the action was dispatched by the feature itself (security).
            assertEquals(feature.identity.handle, executeAction.originator, "The internal execute action must originate from the filesystem feature.")
            // Verify the original client's identity is preserved in the payload (causality).
            val clientOriginator = executeAction.payload?.get("clientOriginator")?.jsonPrimitive?.content
            assertEquals(originator, clientOriginator, "The original client's identity must be preserved in the payload.")

            // Verify the cleanup action was also dispatched.
            val finalizeAction = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_FINALIZE_SCOPED_READ }
            assertNotNull(finalizeAction, "FINALIZE_SCOPED_READ should be dispatched to clean up state.")

            // Verify the pending request is cleared from the state.
            val finalState = harness.store.state.value.featureStates[feature.identity.handle] as FileSystemState
            assertNull(finalState.pendingScopedRead, "The pending request should be cleared after being handled.")
        }
    }

    // ========================================================================
    // New Tests: Sandboxed Service Action Happy Paths (P2)
    // ========================================================================

    @Test
    fun `LIST happy path returns relative listing via targeted action`() {
        val platform = FakePlatformDependencies("test")
        val feature = FileSystemFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        val originator = "session"
        val sandboxPath = platform.getBasePathFor(BasePath.APP_ZONE) + "/$originator"

        // Pre-populate the sandbox with files
        platform.createDirectories(sandboxPath)
        platform.writeFileContent("$sandboxPath/config.json", "{}")
        platform.writeFileContent("$sandboxPath/data.txt", "hello")

        val action = Action(ActionRegistry.Names.FILESYSTEM_LIST, buildJsonObject {
            put("path", "")
        })

        harness.runAndLogOnFailure {
            harness.store.dispatch(originator, action)

            val responseAction = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_RETURN_LIST }
            assertNotNull(responseAction, "A targeted RETURN_LIST action should have been dispatched.")
            assertEquals(originator, responseAction.targetRecipient, "Response should be targeted to the originator.")

            val listing = responseAction.payload?.get("listing")
            assertNotNull(listing, "Response payload must contain a 'listing' field.")
            val entries = Json.decodeFromJsonElement<List<FileEntry>>(listing)
            assertEquals(2, entries.size, "Listing should contain the 2 files.")
            // Paths must be relative to the sandbox root
            assertTrue(entries.all { !it.path.startsWith("/") || !it.path.contains(sandboxPath) },
                "All paths in the listing must be relative to the sandbox.")
        }
    }

    @Test
    fun `LIST with correlationId passes it through to the response`() {
        val platform = FakePlatformDependencies("test")
        val feature = FileSystemFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        val originator = "agent"
        val sandboxPath = platform.getBasePathFor(BasePath.APP_ZONE) + "/$originator"
        platform.createDirectories(sandboxPath)

        val action = Action(ActionRegistry.Names.FILESYSTEM_LIST, buildJsonObject {
            put("path", "")
            put("correlationId", "corr-123")
        })

        harness.runAndLogOnFailure {
            harness.store.dispatch(originator, action)

            val responseAction = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_RETURN_LIST }
            assertNotNull(responseAction)
            assertEquals("corr-123", responseAction.payload?.get("correlationId")?.jsonPrimitive?.content,
                "Correlation ID must be passed through to the response.")
        }
    }

    @Test
    fun `SYSTEM_DELETE happy path removes the file from the sandbox`() {
        val platform = FakePlatformDependencies("test")
        val feature = FileSystemFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        val originator = "settings"
        val path = "old-config.json"
        val sandboxPath = platform.getBasePathFor(BasePath.APP_ZONE) + "/$originator"
        val fullPath = "$sandboxPath/$path"
        platform.writeFileContent(fullPath, "{}")
        assertTrue(platform.fileExists(fullPath), "Precondition: File should exist.")

        val action = Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_DELETE, buildJsonObject {
            put("path", path)
        })

        harness.runAndLogOnFailure {
            harness.store.dispatch(originator, action)
            assertFalse(platform.fileExists(fullPath), "File should have been deleted from the sandbox.")
        }
    }

    @Test
    fun `READ_FILES_CONTENT happy path returns content for multiple files via targeted action`() {
        val platform = FakePlatformDependencies("test")
        val feature = FileSystemFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        val originator = "agent"
        val sandboxPath = platform.getBasePathFor(BasePath.APP_ZONE) + "/$originator"
        platform.writeFileContent("$sandboxPath/file1.txt", "content-one")
        platform.writeFileContent("$sandboxPath/file2.md", "content-two")

        val action = Action(ActionRegistry.Names.FILESYSTEM_READ_FILES_CONTENT, buildJsonObject {
            putJsonArray("paths") {
                add(JsonPrimitive("file1.txt"))
                add(JsonPrimitive("file2.md"))
            }
        })

        harness.runAndLogOnFailure {
            harness.store.dispatch(originator, action)

            val responseAction = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_RETURN_FILES_CONTENT }
            assertNotNull(responseAction, "A targeted RETURN_FILES_CONTENT action should have been dispatched.")
            assertEquals(originator, responseAction.targetRecipient, "Response should be targeted to the originator.")

            val contents = responseAction.payload?.get("contents")?.jsonObject
            assertNotNull(contents, "Response payload must contain a 'contents' map.")
            assertEquals("content-one", contents["file1.txt"]?.jsonPrimitive?.content)
            assertEquals("content-two", contents["file2.md"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `SYSTEM_READ on unencrypted file delivers plaintext content`() {
        val platform = FakePlatformDependencies("test")
        val feature = FileSystemFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        val originator = "session"
        val path = "config.json"
        val sandboxPath = platform.getBasePathFor(BasePath.APP_ZONE) + "/$originator/$path"
        val originalContent = """{"key": "value"}"""
        platform.writeFileContent(sandboxPath, originalContent)

        val action = Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_READ, buildJsonObject {
            put("path", path)
        })

        harness.runAndLogOnFailure {
            harness.store.dispatch(originator, action)

            val responseAction = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_RETURN_READ }
            assertNotNull(responseAction, "A targeted RETURN_READ action should have been dispatched.")
            assertEquals(originator, responseAction.targetRecipient)
            assertEquals(originalContent, responseAction.payload?.get("content")?.jsonPrimitive?.content,
                "Unencrypted content should be returned as-is.")
        }
    }

    @Test
    fun `SYSTEM_WRITE without encrypt flag writes plaintext content`() {
        val platform = FakePlatformDependencies("test")
        val feature = FileSystemFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        val originator = "session"
        val originalContent = """{"sessions": []}"""
        val action = Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
            put("path", "sessions.json")
            put("content", originalContent)
        })

        harness.runAndLogOnFailure {
            harness.store.dispatch(originator, action)

            val sandboxPath = platform.getBasePathFor(BasePath.APP_ZONE) + "/$originator/sessions.json"
            val writtenContent = platform.readFileContent(sandboxPath)
            assertEquals(originalContent, writtenContent, "Written content should be plaintext when encrypt is not set.")
        }
    }

    // ========================================================================
    // New Tests: Side Effects (P3)
    // ========================================================================

    @Test
    fun `NAVIGATE side effect dispatches LOAD_CHILDREN for the target path`() {
        val platform = FakePlatformDependencies("test")
        platform.directories.add("/test/dir")
        val feature = FileSystemFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        val action = Action(ActionRegistry.Names.FILESYSTEM_NAVIGATE, buildJsonObject { put("path", "/test/dir") })

        harness.runAndLogOnFailure {
            harness.store.dispatch(feature.identity.handle, action)

            val loadAction = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_LOAD_CHILDREN }
            assertNotNull(loadAction, "NAVIGATE should dispatch LOAD_CHILDREN to populate the directory listing.")
            assertEquals("/test/dir", loadAction.payload?.get("path")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `LOAD_CHILDREN reads directory and dispatches DIRECTORY_LOADED`() {
        val platform = FakePlatformDependencies("test")
        platform.createDirectories("/test/dir")
        platform.writeFileContent("/test/dir/readme.md", "# Hello")
        val feature = FileSystemFeature(platform)
        val initialState = FileSystemState(currentPath = "/test/dir")
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState(feature.identity.handle, initialState)
            .build(platform = platform)
        val action = Action(ActionRegistry.Names.FILESYSTEM_LOAD_CHILDREN, buildJsonObject { put("path", "/test/dir") })

        harness.runAndLogOnFailure {
            harness.store.dispatch(feature.identity.handle, action)

            val loadedAction = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_DIRECTORY_LOADED }
            assertNotNull(loadedAction, "LOAD_CHILDREN should dispatch DIRECTORY_LOADED with the directory contents.")
            assertEquals("/test/dir", loadedAction.payload?.get("parentPath")?.jsonPrimitive?.content)

            // Verify the state was updated
            val finalState = harness.store.state.value.featureStates[feature.identity.handle] as FileSystemState
            assertTrue(finalState.rootItems.any { it.name == "readme.md" }, "The file should appear in rootItems.")
        }
    }

    @Test
    fun `COPY_SELECTION_TO_CLIPBOARD reads selected files and dispatches CORE_COPY_TO_CLIPBOARD`() {
        val platform = FakePlatformDependencies("test")
        platform.createDirectories("/docs")
        platform.writeFileContent("/docs/a.txt", "content-a")
        platform.writeFileContent("/docs/b.txt", "content-b")
        val feature = FileSystemFeature(platform)
        val initialState = FileSystemState(
            currentPath = "/docs",
            rootItems = listOf(
                FileSystemItem("/docs/a.txt", "a.txt", false, isSelected = true),
                FileSystemItem("/docs/b.txt", "b.txt", false, isSelected = true),
                FileSystemItem("/docs/c.txt", "c.txt", false, isSelected = false)
            )
        )
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState(feature.identity.handle, initialState)
            .build(platform = platform)
        val action = Action(ActionRegistry.Names.FILESYSTEM_COPY_SELECTION_TO_CLIPBOARD)

        harness.runAndLogOnFailure {
            harness.store.dispatch(feature.identity.handle, action)

            val clipboardAction = harness.processedActions.find { it.name == ActionRegistry.Names.CORE_COPY_TO_CLIPBOARD }
            assertNotNull(clipboardAction, "CORE_COPY_TO_CLIPBOARD should have been dispatched.")
            val text = clipboardAction.payload?.get("text")?.jsonPrimitive?.content
            assertNotNull(text)
            assertTrue(text.contains("content-a"), "Clipboard text should contain the first file's content.")
            assertTrue(text.contains("content-b"), "Clipboard text should contain the second file's content.")
            assertFalse(text.contains("content-c"), "Clipboard text should NOT contain unselected file's content.")
        }
    }

    @Test
    fun `COPY_SELECTION_TO_CLIPBOARD with no selection shows toast`() {
        val platform = FakePlatformDependencies("test")
        val feature = FileSystemFeature(platform)
        val initialState = FileSystemState(
            currentPath = "/docs",
            rootItems = listOf(
                FileSystemItem("/docs/a.txt", "a.txt", false, isSelected = false)
            )
        )
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState(feature.identity.handle, initialState)
            .build(platform = platform)
        val action = Action(ActionRegistry.Names.FILESYSTEM_COPY_SELECTION_TO_CLIPBOARD)

        harness.runAndLogOnFailure {
            harness.store.dispatch(feature.identity.handle, action)

            val toastAction = harness.processedActions.find { it.name == ActionRegistry.Names.CORE_SHOW_TOAST }
            assertNotNull(toastAction, "A toast should be shown when no files are selected.")
            assertTrue(toastAction.payload?.get("message")?.jsonPrimitive?.content?.contains("No files selected") == true)
        }
    }

    @Test
    fun `TOGGLE_ITEM_EXPANDED side effect dispatches LOAD_CHILDREN for unloaded directory`() {
        val platform = FakePlatformDependencies("test")
        val feature = FileSystemFeature(platform)
        val initialState = FileSystemState(rootItems = listOf(
            FileSystemItem("/dir", "dir", true, isExpanded = false, children = null)
        ))
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState(feature.identity.handle, initialState)
            .build(platform = platform)
        val action = Action(ActionRegistry.Names.FILESYSTEM_TOGGLE_ITEM_EXPANDED, buildJsonObject { put("path", "/dir") })

        harness.runAndLogOnFailure {
            harness.store.dispatch(feature.identity.handle, action)

            val loadAction = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_LOAD_CHILDREN }
            assertNotNull(loadAction, "LOAD_CHILDREN should be dispatched when expanding a directory with null children.")
            assertEquals("/dir", loadAction.payload?.get("path")?.jsonPrimitive?.content)
        }
    }

    // ========================================================================
    // New Tests: Settings Persistence Side Effects (P3)
    // ========================================================================

    @Test
    fun `ADD_WHITELIST_PATH side effect dispatches SETTINGS_UPDATE for persistence`() {
        val platform = FakePlatformDependencies("test")
        val feature = FileSystemFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        val action = Action(ActionRegistry.Names.FILESYSTEM_ADD_WHITELIST_PATH, buildJsonObject { put("path", "/safe/dir") })

        harness.runAndLogOnFailure {
            harness.store.dispatch(feature.identity.handle, action)

            val settingsAction = harness.processedActions.find { it.name == ActionRegistry.Names.SETTINGS_UPDATE }
            assertNotNull(settingsAction, "SETTINGS_UPDATE should be dispatched to persist the whitelist change.")
            assertEquals("filesystem.whitelistedPaths", settingsAction.payload?.get("key")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `ADD_FAVORITE_PATH side effect dispatches SETTINGS_UPDATE for persistence`() {
        val platform = FakePlatformDependencies("test")
        val feature = FileSystemFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        val action = Action(ActionRegistry.Names.FILESYSTEM_ADD_FAVORITE_PATH, buildJsonObject { put("path", "/fav/dir") })

        harness.runAndLogOnFailure {
            harness.store.dispatch(feature.identity.handle, action)

            val settingsAction = harness.processedActions.find { it.name == ActionRegistry.Names.SETTINGS_UPDATE }
            assertNotNull(settingsAction, "SETTINGS_UPDATE should be dispatched to persist the favorites change.")
            assertEquals("filesystem.favoritePaths", settingsAction.payload?.get("key")?.jsonPrimitive?.content)
        }
    }

    // ========================================================================
    // New Tests: Error Paths (P6)
    // ========================================================================

    @Test
    fun `SYSTEM_READ on missing file delivers null content via targeted action`() {
        val platform = FakePlatformDependencies("test")
        val feature = FileSystemFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        val originator = "settings"
        val path = "nonexistent.json"
        val action = Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_READ, buildJsonObject {
            put("path", path)
        })

        harness.runAndLogOnFailure {
            harness.store.dispatch(originator, action)

            val responseAction = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_RETURN_READ }
            assertNotNull(responseAction, "A targeted RETURN_READ should still be dispatched on error.")
            assertEquals(originator, responseAction.targetRecipient)
            assertEquals(path, responseAction.payload?.get("path")?.jsonPrimitive?.content)
            assertEquals("null", responseAction.payload?.get("content")?.toString(),
                "Content should be null for a missing file.")
        }
    }

    @Test
    fun `LOAD_CHILDREN on invalid directory shows toast and does not crash`() {
        val platform = FakePlatformDependencies("test")
        val feature = FileSystemFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        // Don't create the directory — listDirectory will throw
        val action = Action(ActionRegistry.Names.FILESYSTEM_LOAD_CHILDREN, buildJsonObject { put("path", "/nonexistent") })

        harness.runAndLogOnFailure {
            harness.store.dispatch(feature.identity.handle, action)

            val toastAction = harness.processedActions.find { it.name == ActionRegistry.Names.CORE_SHOW_TOAST }
            assertNotNull(toastAction, "A toast should be shown when LOAD_CHILDREN fails.")
            val noLoadedAction = harness.processedActions.none { it.name == ActionRegistry.Names.FILESYSTEM_DIRECTORY_LOADED }
            assertTrue(noLoadedAction, "DIRECTORY_LOADED should NOT be dispatched on failure.")
        }
    }

    @Test
    fun `READ_FILES_CONTENT skips files that fail filenameGuard`() {
        val platform = FakePlatformDependencies("test")
        val feature = FileSystemFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        val originator = "agent"
        val sandboxPath = platform.getBasePathFor(BasePath.APP_ZONE) + "/$originator"
        platform.writeFileContent("$sandboxPath/good.txt", "good-content")

        val action = Action(ActionRegistry.Names.FILESYSTEM_READ_FILES_CONTENT, buildJsonObject {
            putJsonArray("paths") {
                add(JsonPrimitive("good.txt"))
                add(JsonPrimitive("../escape.txt"))   // traversal — should be rejected
                add(JsonPrimitive("no-extension"))     // no extension — should be rejected
            }
        })

        harness.runAndLogOnFailure {
            harness.store.dispatch(originator, action)

            val responseAction = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_RETURN_FILES_CONTENT }
            assertNotNull(responseAction)
            val contents = responseAction.payload?.get("contents")?.jsonObject
            assertNotNull(contents)
            assertEquals(1, contents.size, "Only the valid file should be in the response.")
            assertEquals("good-content", contents["good.txt"]?.jsonPrimitive?.content)
        }
    }
}