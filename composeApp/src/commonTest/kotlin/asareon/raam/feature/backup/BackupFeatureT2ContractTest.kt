package asareon.raam.feature.backup

import asareon.raam.core.Action
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.fakes.FakePlatformDependencies
import asareon.raam.test.TestEnvironment
import asareon.raam.util.BasePath
import asareon.raam.util.LogLevel
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
 * Tier 2 Contract Tests for BackupFeature's domain actions.
 *
 * These tests verify cross-cutting obligations that every public domain action must satisfy:
 *   1. Publish a success ACTION_RESULT on the happy path.
 *   2. Publish a failure ACTION_RESULT when an error occurs.
 *   3. Log at ERROR level when an error occurs.
 *
 * Action-specific behaviour (preInit, inventory scanning, pruning, etc.)
 * is tested in BackupFeatureT2CoreTest.
 */
class BackupFeatureT2ContractTest {

    private val featureHandle = "backup"

    // ====================================================================
    // Test-case descriptors
    // ====================================================================

    /**
     * A happy-path test case. [setup] receives a vanilla FakePlatformDependencies
     * and should create whatever files/directories the action needs to succeed.
     * [initialState] optionally seeds the feature state.
     */
    private data class HappyCase(
        val label: String,
        val actionName: String,
        val originator: String,
        val payload: JsonObject,
        val setup: (FakePlatformDependencies) -> Unit,
        val initialState: BackupState? = null
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
        val initialState: BackupState? = null
    )

    // -- Happy cases -------------------------------------------------------

    private val happyCases = listOf(
        HappyCase(
            label = "CREATE",
            actionName = ActionRegistry.Names.BACKUP_CREATE,
            originator = "backup",
            payload = buildJsonObject { put("label", "manual-test") },
            setup = { platform ->
                val backupsDir = platform.getBasePathFor(BasePath.APP_ZONE) + "/_backups"
                platform.createDirectories(backupsDir)
            }
        ),
        HappyCase(
            label = "DELETE",
            actionName = ActionRegistry.Names.BACKUP_DELETE,
            originator = "backup",
            payload = buildJsonObject { put("filename", "raam-backup-test.zip") },
            setup = { platform ->
                val backupsDir = platform.getBasePathFor(BasePath.APP_ZONE) + "/_backups"
                platform.createDirectories(backupsDir)
                platform.writeFileContent("$backupsDir/raam-backup-test.zip", "ZIP_CONTENT")
            }
        ),
        HappyCase(
            label = "PRUNE",
            actionName = ActionRegistry.Names.BACKUP_PRUNE,
            originator = "backup",
            payload = buildJsonObject {},
            setup = { platform ->
                val backupsDir = platform.getBasePathFor(BasePath.APP_ZONE) + "/_backups"
                platform.createDirectories(backupsDir)
            }
        ),
        HappyCase(
            label = "OPEN_FOLDER",
            actionName = ActionRegistry.Names.BACKUP_OPEN_FOLDER,
            originator = "backup",
            payload = buildJsonObject {},
            setup = { platform ->
                val backupsDir = platform.getBasePathFor(BasePath.APP_ZONE) + "/_backups"
                platform.createDirectories(backupsDir)
            }
        ),
        HappyCase(
            label = "RESTORE",
            actionName = ActionRegistry.Names.BACKUP_RESTORE,
            originator = "backup",
            payload = buildJsonObject { put("filename", "raam-backup-restore-test.zip") },
            setup = { platform ->
                val backupsDir = platform.getBasePathFor(BasePath.APP_ZONE) + "/_backups"
                platform.createDirectories(backupsDir)
            }
        )
    )

    // -- Failure cases -----------------------------------------------------

    private val failureCases = listOf(
        FailureCase(
            label = "CREATE",
            actionName = ActionRegistry.Names.BACKUP_CREATE,
            originator = "backup",
            payload = buildJsonObject { put("label", "will-fail") },
            buildPlatform = {
                object : FakePlatformDependencies("test") {
                    override fun createZipArchive(
                        sourceDirectoryPath: String,
                        destinationZipPath: String,
                        excludeDirectoryName: String,
                        onProgress: ((bytesProcessed: Long, totalBytes: Long) -> Unit)?
                    ) = throw Exception("Simulated createZipArchive failure")
                }.also { p ->
                    val backupsDir = p.getBasePathFor(BasePath.APP_ZONE) + "/_backups"
                    p.createDirectories(backupsDir)
                }
            }
        ),
        FailureCase(
            label = "DELETE",
            actionName = ActionRegistry.Names.BACKUP_DELETE,
            originator = "backup",
            payload = buildJsonObject { put("filename", "target-backup.zip") },
            buildPlatform = {
                object : FakePlatformDependencies("test") {
                    override fun deleteFile(path: String) {
                        if (path.endsWith("target-backup.zip")) {
                            throw Exception("Simulated deleteFile failure")
                        }
                        super.deleteFile(path)
                    }
                }.also { p ->
                    val backupsDir = p.getBasePathFor(BasePath.APP_ZONE) + "/_backups"
                    p.createDirectories(backupsDir)
                    p.writeFileContent("$backupsDir/target-backup.zip", "ZIP_CONTENT")
                }
            }
        ),
        FailureCase(
            label = "OPEN_FOLDER",
            actionName = ActionRegistry.Names.BACKUP_OPEN_FOLDER,
            originator = "backup",
            payload = buildJsonObject {},
            buildPlatform = {
                FakePlatformDependencies("test").also { it.openFolderShouldThrow = true }
            }
        ),
        FailureCase(
            label = "EXECUTE_RESTORE",
            actionName = ActionRegistry.Names.BACKUP_EXECUTE_RESTORE,
            originator = "backup",
            payload = buildJsonObject { put("filename", "target-restore.zip") },
            buildPlatform = {
                object : FakePlatformDependencies("test") {
                    override fun createZipArchive(
                        sourceDirectoryPath: String,
                        destinationZipPath: String,
                        excludeDirectoryName: String,
                        onProgress: ((bytesProcessed: Long, totalBytes: Long) -> Unit)?
                    ) {
                        // Let the preInit backup succeed but fail the safety backup during restore
                        if (destinationZipPath.contains("pre-restore")) {
                            throw Exception("Simulated safety backup failure during restore")
                        }
                        super.createZipArchive(sourceDirectoryPath, destinationZipPath, excludeDirectoryName, onProgress)
                    }
                }.also { p ->
                    val backupsDir = p.getBasePathFor(BasePath.APP_ZONE) + "/_backups"
                    p.createDirectories(backupsDir)
                    p.writeFileContent("$backupsDir/target-restore.zip", "ZIP_CONTENT")
                }
            }
        )
    )

    // ====================================================================
    // Failure-collecting helper
    // ====================================================================

    /**
     * Runs [block] for each item in the list, catching assertion failures.
     * After all items have been evaluated, throws a single [AssertionError]
     * listing every failure — or passes silently if all succeeded.
     */
    private fun <T> assertAllCases(cases: List<T>, block: (T) -> Unit) {
        val failures = mutableListOf<String>()
        for (case in cases) {
            try {
                block(case)
            } catch (e: Throwable) {
                failures.add(e.message ?: e.toString())
            }
        }
        if (failures.isNotEmpty()) {
            throw AssertionError(
                "${failures.size} of ${cases.size} cases failed:\n\n" +
                        failures.joinToString("\n\n") { "  • $it" }
            )
        }
    }

    // ====================================================================
    // Contract: ACTION_RESULT on success
    // ====================================================================

    @Test
    fun `all domain actions publish success ACTION_RESULT on happy path`() {
        assertAllCases(happyCases) { case ->
            val platform = FakePlatformDependencies("test")
            case.setup(platform)
            val feature = BackupFeature(platform)
            val builder = TestEnvironment.create().withFeature(feature)
            case.initialState?.let { builder.withInitialState(featureHandle, it) }
            val harness = builder.build(platform = platform)

            harness.store.dispatch(case.originator, Action(case.actionName, case.payload))

            val result = harness.processedActions.find {
                it.name == ActionRegistry.Names.BACKUP_ACTION_RESULT
            }
            assertNotNull(result,
                "[${case.label}] should publish BACKUP_ACTION_RESULT on success.")
            assertEquals(true, result.payload?.get("success")?.jsonPrimitive?.boolean,
                "[${case.label}] ACTION_RESULT should have success=true.")
            assertEquals(case.actionName, result.payload?.get("requestAction")?.jsonPrimitive?.content,
                "[${case.label}] ACTION_RESULT.requestAction should match the dispatched action.")
        }
    }

    // ====================================================================
    // Contract: ACTION_RESULT on failure
    // ====================================================================

    @Test
    fun `all domain actions publish failure ACTION_RESULT on error`() {
        assertAllCases(failureCases) { case ->
            val platform = case.buildPlatform()
            val feature = BackupFeature(platform)
            val builder = TestEnvironment.create().withFeature(feature)
            case.initialState?.let { builder.withInitialState(featureHandle, it) }
            val harness = builder.build(platform = platform)

            harness.store.dispatch(case.originator, Action(case.actionName, case.payload))

            val result = harness.processedActions.find {
                it.name == ActionRegistry.Names.BACKUP_ACTION_RESULT
            }
            assertNotNull(result,
                "[${case.label}] should publish BACKUP_ACTION_RESULT on failure.")
            assertEquals(false, result.payload?.get("success")?.jsonPrimitive?.boolean,
                "[${case.label}] ACTION_RESULT should have success=false.")
            assertEquals(case.actionName, result.payload?.get("requestAction")?.jsonPrimitive?.content,
                "[${case.label}] ACTION_RESULT.requestAction should match the dispatched action.")
            assertNotNull(result.payload?.get("error")?.jsonPrimitive?.content,
                "[${case.label}] ACTION_RESULT should contain a non-null error field.")
        }
    }

    // ====================================================================
    // Contract: ERROR-level log on failure
    // ====================================================================

    @Test
    fun `all domain actions log at ERROR level on error`() {
        assertAllCases(failureCases) { case ->
            val platform = case.buildPlatform()
            val feature = BackupFeature(platform)
            val builder = TestEnvironment.create().withFeature(feature)
            case.initialState?.let { builder.withInitialState(featureHandle, it) }
            val harness = builder.build(platform = platform)

            harness.store.dispatch(case.originator, Action(case.actionName, case.payload))

            assertTrue(
                platform.capturedLogs.any { it.level == LogLevel.ERROR },
                "[${case.label}] should log at ERROR level on failure."
            )
        }
    }

    // ====================================================================
    // Discovery Audit: every public action must have contract coverage
    // ====================================================================

    @Test
    fun `all public backup actions are covered by contract test cases`() {
        val featureDescriptor = ActionRegistry.features[featureHandle]
            ?: throw AssertionError("Feature '$featureHandle' not found in ActionRegistry.")

        val publicActions = featureDescriptor.actions.values
            .filter { it.public }
            .map { it.fullName }
            .toSet()

        val testedActions = (happyCases.map { it.actionName } + failureCases.map { it.actionName }).toSet()
        val untested = publicActions - testedActions

        assertTrue(
            untested.isEmpty(),
            "The following public '$featureHandle' actions have no contract test coverage:\n" +
                    untested.sorted().joinToString("\n") { "  • $it" } +
                    "\n\nAdd HappyCase and/or FailureCase entries for each."
        )
    }
}