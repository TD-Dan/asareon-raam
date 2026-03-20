package app.auf.feature.filesystem

import app.auf.core.Action
import app.auf.core.generated.ActionRegistry
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import app.auf.util.BasePath
import app.auf.util.FileEntry
import app.auf.util.LogLevel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tier 2 Contract Tests for FileSystemFeature's domain actions.
 *
 * These tests verify cross-cutting obligations that every domain action must satisfy:
 *   1. Publish a success ACTION_RESULT on the happy path.
 *   2. Publish a failure ACTION_RESULT when an error occurs.
 *   3. Log at ERROR level when an error occurs.
 *
 * Action-specific behaviour (payload shape, sandbox resolution, crypto, etc.)
 * is tested in FileSystemFeatureT2CoreTest.
 */
class FileSystemFeatureT2ContractTest {

    // ====================================================================
    // Test-case descriptors
    // ====================================================================

    /**
     * A happy-path test case. [setup] receives a vanilla FakePlatformDependencies
     * and should create whatever files/directories the action needs to succeed.
     */
    private data class HappyCase(
        val label: String,
        val actionName: String,
        val originator: String,
        val payload: JsonObject,
        val setup: (FakePlatformDependencies) -> Unit
    )

    /**
     * A failure-path test case. [buildPlatform] returns a (possibly subclassed)
     * FakePlatformDependencies wired to fail at the right moment.
     */
    private data class FailureCase(
        val label: String,
        val actionName: String,
        val originator: String,
        val payload: JsonObject,
        val buildPlatform: () -> FakePlatformDependencies
    )

    // -- Happy cases -------------------------------------------------------

    private val happyCases = listOf(
        HappyCase(
            label = "LIST",
            actionName = ActionRegistry.Names.FILESYSTEM_LIST,
            originator = "session",
            payload = buildJsonObject { put("path", "") },
            setup = { platform ->
                val sandbox = platform.getBasePathFor(BasePath.APP_ZONE) + "/session"
                platform.createDirectories(sandbox)
                platform.writeFileContent("$sandbox/file.txt", "x")
            }
        ),
        HappyCase(
            label = "READ",
            actionName = ActionRegistry.Names.FILESYSTEM_READ,
            originator = "session",
            payload = buildJsonObject { put("path", "test.json") },
            setup = { platform ->
                val sandbox = platform.getBasePathFor(BasePath.APP_ZONE) + "/session"
                platform.writeFileContent("$sandbox/test.json", "{}")
            }
        ),
        HappyCase(
            label = "WRITE",
            actionName = ActionRegistry.Names.FILESYSTEM_WRITE,
            originator = "session",
            payload = buildJsonObject { put("path", "test.json"); put("content", "data") },
            setup = { } // writeFileContent auto-creates parents
        ),
        HappyCase(
            label = "DELETE_FILE",
            actionName = ActionRegistry.Names.FILESYSTEM_DELETE_FILE,
            originator = "session",
            payload = buildJsonObject { put("path", "test.json") },
            setup = { platform ->
                val sandbox = platform.getBasePathFor(BasePath.APP_ZONE) + "/session"
                platform.writeFileContent("$sandbox/test.json", "{}")
            }
        ),
        HappyCase(
            label = "DELETE_DIRECTORY",
            actionName = ActionRegistry.Names.FILESYSTEM_DELETE_DIRECTORY,
            originator = "session",
            payload = buildJsonObject { put("path", "subdir") },
            setup = { platform ->
                val sandbox = platform.getBasePathFor(BasePath.APP_ZONE) + "/session"
                platform.createDirectories("$sandbox/subdir")
            }
        ),
        HappyCase(
            label = "OPEN_SYSTEM_FOLDER",
            actionName = ActionRegistry.Names.FILESYSTEM_OPEN_SYSTEM_FOLDER,
            originator = "core",
            payload = buildJsonObject { put("path", "app:logs") },
            setup = { platform ->
                val appZone = platform.getBasePathFor(BasePath.APP_ZONE)
                platform.createDirectories("$appZone/logs")
            }
        )
    )

    // -- Failure cases -----------------------------------------------------

    private val failureCases = listOf(
        FailureCase(
            label = "LIST",
            actionName = ActionRegistry.Names.FILESYSTEM_LIST,
            originator = "session",
            payload = buildJsonObject { put("path", "") },
            buildPlatform = {
                object : FakePlatformDependencies("test") {
                    override fun listDirectory(path: String): List<FileEntry> =
                        throw Exception("Simulated listDirectory failure")
                    override fun listDirectoryRecursive(path: String): List<FileEntry> =
                        throw Exception("Simulated listDirectoryRecursive failure")
                }
            }
        ),
        FailureCase(
            label = "READ",
            actionName = ActionRegistry.Names.FILESYSTEM_READ,
            originator = "session",
            payload = buildJsonObject { put("path", "missing.json") },
            buildPlatform = { FakePlatformDependencies("test") } // file doesn't exist → readFileContent throws
        ),
        FailureCase(
            label = "WRITE",
            actionName = ActionRegistry.Names.FILESYSTEM_WRITE,
            originator = "session",
            payload = buildJsonObject { put("path", "test.json"); put("content", "data") },
            buildPlatform = {
                object : FakePlatformDependencies("test") {
                    override fun writeFileContent(path: String, content: String) =
                        throw Exception("Simulated writeFileContent failure")
                }
            }
        ),
        FailureCase(
            label = "DELETE_FILE",
            actionName = ActionRegistry.Names.FILESYSTEM_DELETE_FILE,
            originator = "session",
            payload = buildJsonObject { put("path", "test.json") },
            buildPlatform = {
                object : FakePlatformDependencies("test") {
                    override fun deleteFile(path: String) =
                        throw Exception("Simulated deleteFile failure")
                }.also { p ->
                    val sandbox = p.getBasePathFor(BasePath.APP_ZONE) + "/session"
                    p.writeFileContent("$sandbox/test.json", "{}")
                }
            }
        ),
        FailureCase(
            label = "DELETE_DIRECTORY",
            actionName = ActionRegistry.Names.FILESYSTEM_DELETE_DIRECTORY,
            originator = "session",
            payload = buildJsonObject { put("path", "subdir") },
            buildPlatform = {
                object : FakePlatformDependencies("test") {
                    override fun deleteDirectory(path: String) =
                        throw Exception("Simulated deleteDirectory failure")
                }.also { p ->
                    val sandbox = p.getBasePathFor(BasePath.APP_ZONE) + "/session"
                    p.createDirectories("$sandbox/subdir")
                }
            }
        ),
        FailureCase(
            label = "OPEN_SYSTEM_FOLDER",
            actionName = ActionRegistry.Names.FILESYSTEM_OPEN_SYSTEM_FOLDER,
            originator = "core",
            payload = buildJsonObject { put("path", "app:nonexistent") },
            buildPlatform = { FakePlatformDependencies("test") } // directory doesn't exist
        )
    )

    // ====================================================================
    // Contract: ACTION_RESULT on success
    // ====================================================================

    @Test
    fun `all domain actions publish success ACTION_RESULT on happy path`() {
        happyCases.forEach { case ->
            val platform = FakePlatformDependencies("test")
            case.setup(platform)
            val feature = FileSystemFeature(platform)
            val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)

            harness.runAndLogOnFailure {
                harness.store.dispatch(case.originator, Action(case.actionName, case.payload))

                val result = harness.processedActions.find {
                    it.name == ActionRegistry.Names.FILESYSTEM_ACTION_RESULT
                }
                assertNotNull(result,
                    "[${case.label}] should publish FILESYSTEM_ACTION_RESULT on success.")
                assertEquals(true, result.payload?.get("success")?.jsonPrimitive?.boolean,
                    "[${case.label}] ACTION_RESULT should have success=true.")
                assertEquals(case.actionName, result.payload?.get("requestAction")?.jsonPrimitive?.content,
                    "[${case.label}] ACTION_RESULT.requestAction should match the dispatched action.")
            }
        }
    }

    // ====================================================================
    // Contract: ACTION_RESULT on failure
    // ====================================================================

    @Test
    fun `all domain actions publish failure ACTION_RESULT on error`() {
        failureCases.forEach { case ->
            val platform = case.buildPlatform()
            val feature = FileSystemFeature(platform)
            val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)

            harness.runAndLogOnFailure {
                harness.store.dispatch(case.originator, Action(case.actionName, case.payload))

                val result = harness.processedActions.find {
                    it.name == ActionRegistry.Names.FILESYSTEM_ACTION_RESULT
                }
                assertNotNull(result,
                    "[${case.label}] should publish FILESYSTEM_ACTION_RESULT on failure.")
                assertEquals(false, result.payload?.get("success")?.jsonPrimitive?.boolean,
                    "[${case.label}] ACTION_RESULT should have success=false.")
                assertEquals(case.actionName, result.payload?.get("requestAction")?.jsonPrimitive?.content,
                    "[${case.label}] ACTION_RESULT.requestAction should match the dispatched action.")
                assertNotNull(result.payload?.get("error")?.jsonPrimitive?.content,
                    "[${case.label}] ACTION_RESULT should contain a non-null error field.")
            }
        }
    }

    // ====================================================================
    // Contract: ERROR-level log on failure
    // ====================================================================

    @Test
    fun `all domain actions log at ERROR level on error`() {
        failureCases.forEach { case ->
            val platform = case.buildPlatform()
            val feature = FileSystemFeature(platform)
            val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)

            harness.runAndLogOnFailure {
                harness.store.dispatch(case.originator, Action(case.actionName, case.payload))

                assertTrue(
                    platform.capturedLogs.any { it.level == LogLevel.ERROR },
                    "[${case.label}] should log at ERROR level on failure."
                )
            }
        }
    }
}