package app.auf.feature.filesystem

import app.auf.core.Action
import app.auf.core.generated.ActionRegistry
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import app.auf.util.BasePath
import app.auf.util.FileEntry
import app.auf.util.LogLevel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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

    private val featureHandle = "filesystem"

    // ====================================================================
    // Test-case descriptors
    // ====================================================================

    /**
     * A happy-path test case. [setup] receives a vanilla FakePlatformDependencies
     * and should create whatever files/directories the action needs to succeed.
     * [initialState] optionally seeds the feature state (needed for UI-driven actions).
     */
    private data class HappyCase(
        val label: String,
        val actionName: String,
        val originator: String,
        val payload: JsonObject,
        val setup: (FakePlatformDependencies) -> Unit,
        val initialState: FileSystemState? = null
    )

    /**
     * A failure-path test case. [buildPlatform] returns a (possibly subclassed)
     * FakePlatformDependencies wired to fail at the right moment.
     * [initialState] optionally seeds the feature state.
     */
    private data class FailureCase(
        val label: String,
        val actionName: String,
        val originator: String,
        val payload: JsonObject,
        val buildPlatform: () -> FakePlatformDependencies,
        val initialState: FileSystemState? = null
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
            label = "READ_MULTIPLE",
            actionName = ActionRegistry.Names.FILESYSTEM_READ_MULTIPLE,
            originator = "session",
            payload = buildJsonObject {
                putJsonArray("paths") {
                    add(JsonPrimitive("a.txt"))
                    add(JsonPrimitive("b.txt"))
                }
            },
            setup = { platform ->
                val sandbox = platform.getBasePathFor(BasePath.APP_ZONE) + "/session"
                platform.writeFileContent("$sandbox/a.txt", "aaa")
                platform.writeFileContent("$sandbox/b.txt", "bbb")
            }
        ),
        HappyCase(
            label = "WRITE",
            actionName = ActionRegistry.Names.FILESYSTEM_WRITE,
            originator = "session",
            payload = buildJsonObject { put("path", "test.json"); put("content", "data") },
            setup = { }
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
            label = "OPEN_WORKSPACE_FOLDER",
            actionName = ActionRegistry.Names.FILESYSTEM_OPEN_WORKSPACE_FOLDER,
            originator = "agent",
            payload = buildJsonObject { put("path", "workspace") },
            setup = { platform ->
                val sandbox = platform.getBasePathFor(BasePath.APP_ZONE) + "/agent/workspace"
                platform.createDirectories(sandbox)
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
            buildPlatform = { FakePlatformDependencies("test") }
        ),
        FailureCase(
            label = "READ_MULTIPLE",
            actionName = ActionRegistry.Names.FILESYSTEM_READ_MULTIPLE,
            originator = "session",
            payload = buildJsonObject {
                putJsonArray("paths") {
                    add(JsonPrimitive("missing.txt"))
                }
            },
            buildPlatform = { FakePlatformDependencies("test") }
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
            label = "OPEN_WORKSPACE_FOLDER",
            actionName = ActionRegistry.Names.FILESYSTEM_OPEN_WORKSPACE_FOLDER,
            originator = "agent",
            payload = buildJsonObject { put("path", "workspace") },
            buildPlatform = {
                FakePlatformDependencies("test").also { it.openFolderShouldThrow = true }
            }
        ),
        FailureCase(
            label = "OPEN_SYSTEM_FOLDER",
            actionName = ActionRegistry.Names.FILESYSTEM_OPEN_SYSTEM_FOLDER,
            originator = "core",
            payload = buildJsonObject { put("path", "app:nonexistent") },
            buildPlatform = { FakePlatformDependencies("test") }
        ),
        FailureCase(
            label = "COPY_SELECTION_TO_CLIPBOARD",
            actionName = ActionRegistry.Names.FILESYSTEM_COPY_SELECTION_TO_CLIPBOARD,
            originator = "filesystem",
            payload = buildJsonObject { },
            buildPlatform = { FakePlatformDependencies("test") },
            initialState = FileSystemState() // empty rootItems → no files selected
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
            val builder = TestEnvironment.create().withFeature(feature)
            case.initialState?.let { builder.withInitialState(featureHandle, it) }
            val harness = builder.build(platform = platform)

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
            val builder = TestEnvironment.create().withFeature(feature)
            case.initialState?.let { builder.withInitialState(featureHandle, it) }
            val harness = builder.build(platform = platform)

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
            val builder = TestEnvironment.create().withFeature(feature)
            case.initialState?.let { builder.withInitialState(featureHandle, it) }
            val harness = builder.build(platform = platform)

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