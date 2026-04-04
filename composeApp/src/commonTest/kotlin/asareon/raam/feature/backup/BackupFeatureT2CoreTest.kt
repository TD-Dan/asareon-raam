package asareon.raam.feature.backup

import asareon.raam.core.Action
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.fakes.FakePlatformDependencies
import asareon.raam.test.TestEnvironment
import asareon.raam.util.BasePath
import asareon.raam.util.LogLevel
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tier 2 Core Test for BackupFeature.
 *
 * Mandate (P-TEST-001, T2): To test the feature's reducer and handleSideEffects handlers
 * working together within a realistic TestEnvironment that includes the real Store.
 */
class BackupFeatureT2CoreTest {

    private val featureHandle = "backup"

    // ========================================================================
    // preInit Tests
    // ========================================================================

    @Test
    fun `preInit creates startup backup when auto-backup is enabled`() {
        val platform = FakePlatformDependencies("test")
        val backupsDir = platform.getBasePathFor(BasePath.APP_ZONE) + "/_backups"
        platform.createDirectories(backupsDir)
        val feature = BackupFeature(platform)

        feature.preInit()

        val infoLogs = platform.capturedLogs.filter {
            it.level == LogLevel.INFO && it.message.contains("Startup backup created successfully")
        }
        assertTrue(infoLogs.isNotEmpty(), "Should log successful startup backup creation.")
    }

    @Test
    fun `preInit skips backup when skip marker exists`() {
        val platform = FakePlatformDependencies("test")
        val backupsDir = platform.getBasePathFor(BasePath.APP_ZONE) + "/_backups"
        platform.createDirectories(backupsDir)
        val skipMarkerPath = "$backupsDir/.skip-next-backup"
        platform.writeFileContent(skipMarkerPath, "skip")
        val feature = BackupFeature(platform)

        feature.preInit()

        val skipLog = platform.capturedLogs.find {
            it.message.contains("Skip marker found")
        }
        assertNotNull(skipLog, "Should log that skip marker was found.")
        assertFalse(platform.fileExists(skipMarkerPath), "Skip marker should be deleted.")
    }

    @Test
    fun `preInit skips backup when auto-backup is disabled via hint file`() {
        val platform = FakePlatformDependencies("test")
        val backupsDir = platform.getBasePathFor(BasePath.APP_ZONE) + "/_backups"
        platform.createDirectories(backupsDir)
        val configPath = "$backupsDir/.backup-config"
        platform.writeFileContent(configPath, """{"autoBackupEnabled": false}""")
        val feature = BackupFeature(platform)

        feature.preInit()

        val skipLog = platform.capturedLogs.find {
            it.message.contains("Auto-backup disabled via hint file")
        }
        assertNotNull(skipLog, "Should log that auto-backup is disabled.")
    }

    @Test
    fun `preInit continues when hint file is malformed`() {
        val platform = FakePlatformDependencies("test")
        val backupsDir = platform.getBasePathFor(BasePath.APP_ZONE) + "/_backups"
        platform.createDirectories(backupsDir)
        val configPath = "$backupsDir/.backup-config"
        platform.writeFileContent(configPath, "NOT VALID JSON")
        val feature = BackupFeature(platform)

        feature.preInit()

        val warnLog = platform.capturedLogs.find {
            it.level == LogLevel.WARN && it.message.contains("Failed to read .backup-config")
        }
        assertNotNull(warnLog, "Should log a warning for malformed config.")
        val successLog = platform.capturedLogs.find {
            it.message.contains("Startup backup created successfully")
        }
        assertNotNull(successLog, "Backup should still be created despite malformed config.")
    }

    @Test
    fun `preInit never blocks startup even on failure`() {
        val platform = object : FakePlatformDependencies("test") {
            override fun createZipArchive(
                sourceDirectoryPath: String,
                destinationZipPath: String,
                excludeDirectoryName: String
            ) = throw Exception("Simulated zip failure")
        }
        val backupsDir = platform.getBasePathFor(BasePath.APP_ZONE) + "/_backups"
        platform.createDirectories(backupsDir)
        val feature = BackupFeature(platform)

        // Should not throw
        feature.preInit()

        val errorLog = platform.capturedLogs.find {
            it.level == LogLevel.ERROR && it.message.contains("Failed to create startup backup")
        }
        assertNotNull(errorLog, "Should log error but not throw.")
    }

    // ========================================================================
    // SYSTEM_INITIALIZING Side Effects
    // ========================================================================

    @Test
    fun `SYSTEM_INITIALIZING registers settings and scans inventory`() {
        val platform = FakePlatformDependencies("test")
        val backupsDir = platform.getBasePathFor(BasePath.APP_ZONE) + "/_backups"
        platform.createDirectories(backupsDir)
        platform.writeFileContent("$backupsDir/raam-backup-test.zip", "ZIP")
        val feature = BackupFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch(featureHandle, Action(ActionRegistry.Names.SYSTEM_INITIALIZING))

            // Should register settings
            val settingsActions = harness.processedActions.filter {
                it.name == ActionRegistry.Names.SETTINGS_ADD
            }
            assertTrue(settingsActions.size >= 2,
                "At least 2 SETTINGS_ADD actions should be dispatched (autoBackupEnabled, maxBackups).")

            // Should dispatch INVENTORY_UPDATED
            val inventoryAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.BACKUP_INVENTORY_UPDATED
            }
            assertNotNull(inventoryAction, "INVENTORY_UPDATED should be dispatched after scanning.")
            val backups = inventoryAction.payload?.get("backups")?.jsonArray
            assertNotNull(backups)
            assertTrue(backups.size >= 1, "Inventory should contain the test backup.")
        }
    }

    // ========================================================================
    // CREATE Side Effects
    // ========================================================================

    @Test
    fun `CREATE happy path creates zip and dispatches OPERATION_RESULT and ACTION_RESULT`() {
        val platform = FakePlatformDependencies("test")
        val backupsDir = platform.getBasePathFor(BasePath.APP_ZONE) + "/_backups"
        platform.createDirectories(backupsDir)
        val feature = BackupFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        val action = Action(ActionRegistry.Names.BACKUP_CREATE, buildJsonObject {
            put("label", "my-snapshot")
        })

        harness.runAndLogOnFailure {
            harness.store.dispatch(featureHandle, action)

            // OPERATION_RESULT (internal UI update)
            val opResult = harness.processedActions.find {
                it.name == ActionRegistry.Names.BACKUP_OPERATION_RESULT
            }
            assertNotNull(opResult, "OPERATION_RESULT should be dispatched.")
            assertEquals(true, opResult.payload?.get("success")?.jsonPrimitive?.boolean)

            // ACTION_RESULT (broadcast contract)
            val actionResult = harness.processedActions.find {
                it.name == ActionRegistry.Names.BACKUP_ACTION_RESULT
            }
            assertNotNull(actionResult, "ACTION_RESULT should be dispatched.")
            assertEquals(true, actionResult.payload?.get("success")?.jsonPrimitive?.boolean)
            assertEquals(ActionRegistry.Names.BACKUP_CREATE,
                actionResult.payload?.get("requestAction")?.jsonPrimitive?.content)

            // INVENTORY_UPDATED should follow
            val inventoryAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.BACKUP_INVENTORY_UPDATED
            }
            assertNotNull(inventoryAction, "INVENTORY_UPDATED should be dispatched after create.")
        }
    }

    @Test
    fun `CREATE with label includes label in filename`() {
        val platform = FakePlatformDependencies("test")
        val backupsDir = platform.getBasePathFor(BasePath.APP_ZONE) + "/_backups"
        platform.createDirectories(backupsDir)
        val feature = BackupFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch(featureHandle, Action(ActionRegistry.Names.BACKUP_CREATE, buildJsonObject {
                put("label", "before-migration")
            }))

            val result = harness.processedActions.find {
                it.name == ActionRegistry.Names.BACKUP_OPERATION_RESULT
            }
            assertNotNull(result)
            val message = result.payload?.get("message")?.jsonPrimitive?.content ?: ""
            assertTrue(message.contains("before-migration"),
                "Result message should include the label in the filename.")
        }
    }

    // ========================================================================
    // DELETE Side Effects
    // ========================================================================

    @Test
    fun `DELETE happy path removes file and dispatches results`() {
        val platform = FakePlatformDependencies("test")
        val backupsDir = platform.getBasePathFor(BasePath.APP_ZONE) + "/_backups"
        platform.createDirectories(backupsDir)
        val filename = "raam-backup-to-delete.zip"
        platform.writeFileContent("$backupsDir/$filename", "ZIP_CONTENT")
        val feature = BackupFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch(featureHandle, Action(ActionRegistry.Names.BACKUP_DELETE, buildJsonObject {
                put("filename", filename)
            }))

            assertFalse(platform.fileExists("$backupsDir/$filename"),
                "File should have been deleted.")

            val actionResult = harness.processedActions.find {
                it.name == ActionRegistry.Names.BACKUP_ACTION_RESULT
            }
            assertNotNull(actionResult)
            assertEquals(true, actionResult.payload?.get("success")?.jsonPrimitive?.boolean)
        }
    }

    @Test
    fun `DELETE for missing file dispatches failure result`() {
        val platform = FakePlatformDependencies("test")
        val backupsDir = platform.getBasePathFor(BasePath.APP_ZONE) + "/_backups"
        platform.createDirectories(backupsDir)
        val feature = BackupFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch(featureHandle, Action(ActionRegistry.Names.BACKUP_DELETE, buildJsonObject {
                put("filename", "nonexistent.zip")
            }))

            val actionResult = harness.processedActions.find {
                it.name == ActionRegistry.Names.BACKUP_ACTION_RESULT
            }
            assertNotNull(actionResult)
            assertEquals(false, actionResult.payload?.get("success")?.jsonPrimitive?.boolean)
            assertTrue(
                actionResult.payload?.get("error")?.jsonPrimitive?.content?.contains("File not found") == true
            )
        }
    }

    // ========================================================================
    // RESTORE Side Effects
    // ========================================================================

    @Test
    fun `RESTORE dispatches confirmation dialog`() {
        val platform = FakePlatformDependencies("test")
        val feature = BackupFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch(featureHandle, Action(ActionRegistry.Names.BACKUP_RESTORE, buildJsonObject {
                put("filename", "raam-backup-test.zip")
            }))

            val confirmAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.CORE_SHOW_CONFIRMATION_DIALOG
            }
            assertNotNull(confirmAction, "RESTORE should dispatch a confirmation dialog.")
            val message = confirmAction.payload?.get("message")?.jsonPrimitive?.content ?: ""
            assertTrue(message.contains("raam-backup-test.zip"),
                "Confirmation message should include the backup filename.")
        }
    }

    @Test
    fun `EXECUTE_RESTORE on missing backup dispatches failure`() {
        val platform = FakePlatformDependencies("test")
        val backupsDir = platform.getBasePathFor(BasePath.APP_ZONE) + "/_backups"
        platform.createDirectories(backupsDir)
        val feature = BackupFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch(featureHandle, Action(ActionRegistry.Names.BACKUP_EXECUTE_RESTORE, buildJsonObject {
                put("filename", "nonexistent.zip")
            }))

            val opResult = harness.processedActions.find {
                it.name == ActionRegistry.Names.BACKUP_OPERATION_RESULT
            }
            assertNotNull(opResult)
            assertEquals(false, opResult.payload?.get("success")?.jsonPrimitive?.boolean)

            val actionResult = harness.processedActions.find {
                it.name == ActionRegistry.Names.BACKUP_ACTION_RESULT
            }
            assertNotNull(actionResult)
            assertEquals(false, actionResult.payload?.get("success")?.jsonPrimitive?.boolean)
        }
    }

    // ========================================================================
    // OPEN_FOLDER Side Effects
    // ========================================================================

    @Test
    fun `OPEN_FOLDER opens backups directory in explorer`() {
        val platform = FakePlatformDependencies("test")
        val backupsDir = platform.getBasePathFor(BasePath.APP_ZONE) + "/_backups"
        platform.createDirectories(backupsDir)
        val feature = BackupFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch(featureHandle, Action(ActionRegistry.Names.BACKUP_OPEN_FOLDER))

            assertTrue(platform.openedFolderPaths.any { it == backupsDir },
                "openFolderInExplorer should be called with the backups directory.")
        }
    }

    @Test
    fun `OPEN_FOLDER dispatches failure ACTION_RESULT on exception`() {
        val platform = FakePlatformDependencies("test")
        platform.openFolderShouldThrow = true
        val feature = BackupFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch(featureHandle, Action(ActionRegistry.Names.BACKUP_OPEN_FOLDER))

            val actionResult = harness.processedActions.find {
                it.name == ActionRegistry.Names.BACKUP_ACTION_RESULT
            }
            assertNotNull(actionResult)
            assertEquals(false, actionResult.payload?.get("success")?.jsonPrimitive?.boolean)
        }
    }

    // ========================================================================
    // PRUNE Side Effects
    // ========================================================================

    @Test
    fun `PRUNE deletes oldest backups exceeding maxBackups threshold`() {
        val platform = FakePlatformDependencies("test")
        val backupsDir = platform.getBasePathFor(BasePath.APP_ZONE) + "/_backups"
        platform.createDirectories(backupsDir)

        // Create 5 backups with increasing timestamps
        for (i in 1..5) {
            platform.currentTime = 1_000_000_000_000L + (i * 1000L)
            platform.writeFileContent("$backupsDir/raam-backup-$i.zip", "ZIP_$i")
        }

        val feature = BackupFeature(platform)
        val initialState = BackupState(maxBackups = 3) // Only keep 3
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState(featureHandle, initialState)
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch(featureHandle, Action(ActionRegistry.Names.BACKUP_PRUNE))

            // The 2 oldest should be pruned
            val pruneLogs = platform.capturedLogs.filter {
                it.message.contains("Pruned old backup")
            }
            assertEquals(2, pruneLogs.size, "Should prune 2 oldest backups (5 - 3 = 2).")
        }
    }

    @Test
    fun `PRUNE does nothing when within threshold`() {
        val platform = FakePlatformDependencies("test")
        val backupsDir = platform.getBasePathFor(BasePath.APP_ZONE) + "/_backups"
        platform.createDirectories(backupsDir)
        platform.writeFileContent("$backupsDir/raam-backup-1.zip", "ZIP_1")

        val feature = BackupFeature(platform)
        val initialState = BackupState(maxBackups = 20)
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState(featureHandle, initialState)
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch(featureHandle, Action(ActionRegistry.Names.BACKUP_PRUNE))

            val pruneLogs = platform.capturedLogs.filter {
                it.message.contains("Pruned old backup")
            }
            assertTrue(pruneLogs.isEmpty(), "No backups should be pruned when within threshold.")
        }
    }

    // ========================================================================
    // SETTINGS Side Effects
    // ========================================================================

    @Test
    fun `SETTINGS_VALUE_CHANGED for maxBackups writes hint file and triggers prune`() {
        val platform = FakePlatformDependencies("test")
        val backupsDir = platform.getBasePathFor(BasePath.APP_ZONE) + "/_backups"
        platform.createDirectories(backupsDir)
        val feature = BackupFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch(featureHandle, Action(ActionRegistry.Names.SETTINGS_VALUE_CHANGED, buildJsonObject {
                put("key", "backup.maxBackups")
                put("value", "5")
            }))

            val configPath = "$backupsDir/.backup-config"
            assertTrue(platform.fileExists(configPath),
                "Hint file should be written when maxBackups changes.")
        }
    }

    @Test
    fun `SETTINGS_VALUE_CHANGED for autoBackupEnabled writes hint file`() {
        val platform = FakePlatformDependencies("test")
        val backupsDir = platform.getBasePathFor(BasePath.APP_ZONE) + "/_backups"
        platform.createDirectories(backupsDir)
        val feature = BackupFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch(featureHandle, Action(ActionRegistry.Names.SETTINGS_VALUE_CHANGED, buildJsonObject {
                put("key", "backup.autoBackupEnabled")
                put("value", "false")
            }))

            val configPath = "$backupsDir/.backup-config"
            assertTrue(platform.fileExists(configPath),
                "Hint file should be written when autoBackupEnabled changes.")
            val content = platform.readFileContent(configPath)
            assertTrue(content.contains("false"),
                "Hint file should reflect the new autoBackupEnabled value.")
        }
    }

    @Test
    fun `SETTINGS_VALUE_CHANGED for unrelated key does not write hint file`() {
        val platform = FakePlatformDependencies("test")
        val backupsDir = platform.getBasePathFor(BasePath.APP_ZONE) + "/_backups"
        platform.createDirectories(backupsDir)
        val feature = BackupFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        platform.writtenFiles.clear() // Clear any setup writes

        harness.runAndLogOnFailure {
            harness.store.dispatch(featureHandle, Action(ActionRegistry.Names.SETTINGS_VALUE_CHANGED, buildJsonObject {
                put("key", "session.unrelated_key")
                put("value", "whatever")
            }))

            val configPath = "$backupsDir/.backup-config"
            assertFalse(platform.writtenFiles.containsKey(configPath),
                "Hint file should NOT be written for unrelated settings keys.")
        }
    }
}