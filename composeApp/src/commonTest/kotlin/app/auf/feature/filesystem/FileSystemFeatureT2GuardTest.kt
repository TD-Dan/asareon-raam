package app.auf.feature.filesystem

import app.auf.core.Action
import app.auf.core.generated.ActionRegistry
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import app.auf.util.BasePath
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tier 2 Core Test for the FileSystemFeature's security and path validation guards.
 *
 * Mandate (P-TEST-001, T2): To test that the feature's guards correctly intercept
 * invalid requests *before* they can trigger side effects.
 *
 * Phase 3 FIX: Replaced vacuously-true `deliveredPrivateData.size == 0` assertions
 * with meaningful checks on processedActions. Since production code now dispatches
 * targeted Actions (not PrivateDataEnvelopes), the old assertion was always true
 * regardless of whether the guard fired.
 */
class FileSystemFeatureT2GuardTest {
    private val originator = "test-feature"

    @Test
    fun `filenameGuard rejects blank paths for write operations`() {
        // Arrange
        val platform = FakePlatformDependencies("test")
        val feature = FileSystemFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        val action = Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
            put("path", "  ") // Blank path
            put("content", "some content")
        })

        harness.runAndLogOnFailure {
            // Act
            harness.store.dispatch(originator, action)
            // Assert
            assertTrue(harness.platform.writtenFiles.isEmpty(), "No file should have been written.")
            val log = harness.platform.capturedLogs.find { it.message.contains("Refused to write a blank filename") }
            assertNotNull(log, "A specific error should be logged for blank filenames.")
        }
    }

    @Test
    fun `filenameGuard rejects paths with directory traversal for read operations`() {
        val platform = FakePlatformDependencies("test")
        val feature = FileSystemFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        val action = Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_READ, buildJsonObject {
            put("path", "../secrets.json")
        })

        harness.runAndLogOnFailure {
            harness.store.dispatch(originator, action)

            // Phase 3 FIX: Assert that no RETURN_READ targeted action was dispatched.
            // The old check (deliveredPrivateData.size == 0) was vacuously true post-migration.
            val responseAction = harness.processedActions.none { it.name == ActionRegistry.Names.FILESYSTEM_RETURN_READ }
            assertTrue(responseAction, "No RETURN_READ action should be dispatched for a rejected read.")
            val log = harness.platform.capturedLogs.find { it.message.contains("SECURITY: Refused path with directory traversal") }
            assertNotNull(log, "A specific security error should be logged for '..' characters.")
        }
    }

    @Test
    fun `filenameGuard rejects paths without a file extension for delete operations`() {
        val platform = FakePlatformDependencies("test")
        val feature = FileSystemFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        val path = "some-folder/a-file-with-no-extension"
        val action = Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_DELETE, buildJsonObject {
            put("path", path)
        })
        val fullPath = "${platform.getBasePathFor(BasePath.APP_ZONE)}/$originator/$path"
        platform.writeFileContent(fullPath, "content")


        harness.runAndLogOnFailure {
            harness.store.dispatch(originator, action)

            assertTrue(platform.fileExists(fullPath), "The file should NOT have been deleted.")
            val log = harness.platform.capturedLogs.find { it.message.contains("Refused filename without a file extension") }
            assertNotNull(log, "A specific error should be logged for missing file extensions.")
        }
    }

    @Test
    fun `filepathGuard rejects paths with directory traversal for list operations`() {
        val platform = FakePlatformDependencies("test")
        val feature = FileSystemFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        val action = Action(ActionRegistry.Names.FILESYSTEM_LIST, buildJsonObject {
            put("path", "some/../../other/path")
        })

        harness.runAndLogOnFailure {
            harness.store.dispatch(originator, action)

            // Phase 3 FIX: Assert that no RETURN_LIST targeted action was dispatched.
            val responseAction = harness.processedActions.none { it.name == ActionRegistry.Names.FILESYSTEM_RETURN_LIST }
            assertTrue(responseAction, "No RETURN_LIST action should be dispatched for a rejected path.")
            val log =
                harness.platform.capturedLogs.find { it.message.contains("SECURITY: Refused path with directory traversal") }
            assertNotNull(log, "A specific security error should be logged for '..' characters in directory operations.")
        }
    }

    @Test
    fun `filepathGuard rejects directory traversal for delete directory operations`() {
        val platform = FakePlatformDependencies("test")
        val feature = FileSystemFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        val path = "legit/../../../etc"
        val action = Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_DELETE_DIRECTORY, buildJsonObject {
            put("path", path)
        })

        harness.runAndLogOnFailure {
            harness.store.dispatch(originator, action)

            val log = harness.platform.capturedLogs.find { it.message.contains("SECURITY: Refused path with directory traversal") }
            assertNotNull(log, "A specific security error should be logged for '..' in delete directory.")
        }
    }

    @Test
    fun `filenameGuard rejects directory traversal in write operations`() {
        val platform = FakePlatformDependencies("test")
        val feature = FileSystemFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        val action = Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
            put("path", "../../etc/passwd.txt")
            put("content", "pwned")
        })

        harness.runAndLogOnFailure {
            harness.store.dispatch(originator, action)

            assertTrue(harness.platform.writtenFiles.isEmpty(), "No file should have been written.")
            val log = harness.platform.capturedLogs.find { it.message.contains("SECURITY: Refused path with directory traversal") }
            assertNotNull(log, "A specific security error should be logged for '..' in write operations.")
        }
    }
}