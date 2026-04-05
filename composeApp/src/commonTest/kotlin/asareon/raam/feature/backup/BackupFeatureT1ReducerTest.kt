package asareon.raam.feature.backup

import asareon.raam.core.Action
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.fakes.FakePlatformDependencies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tier 1 Unit Test for BackupFeature's reducer.
 *
 * Mandate (P-TEST-001, T1): To test the reducer as a pure function in complete isolation.
 */
class BackupFeatureT1ReducerTest {

    private val platform = FakePlatformDependencies("v2-test")
    private val feature = BackupFeature(platform)

    // ========================================================================
    // Default State
    // ========================================================================

    @Test
    fun `reducer returns default state for null input`() {
        val action = Action("unknown.ACTION")
        val newState = feature.reducer(null, action)
        assertNotNull(newState, "Reducer should return a default BackupState for null input.")
        assertTrue(newState is BackupState)
        val state = newState as BackupState
        assertTrue(state.backups.isEmpty())
        assertFalse(state.isCreating)
        assertFalse(state.isRestoring)
        assertNull(state.pendingRestoreFilename)
        assertNull(state.lastResultMessage)
        assertTrue(state.autoBackupEnabled)
        assertEquals(20, state.maxBackups)
    }

    @Test
    fun `reducer returns existing state for unknown action`() {
        val existing = BackupState(maxBackups = 42)
        val newState = feature.reducer(existing, Action("unknown.ACTION")) as BackupState
        assertEquals(42, newState.maxBackups, "State should pass through unchanged for unknown actions.")
    }

    // ========================================================================
    // SYSTEM_INITIALIZING
    // ========================================================================

    @Test
    fun `reducer SYSTEM_INITIALIZING returns state unchanged`() {
        val initial = BackupState(maxBackups = 15)
        val action = Action(ActionRegistry.Names.SYSTEM_INITIALIZING)
        val newState = feature.reducer(initial, action) as BackupState
        assertEquals(15, newState.maxBackups, "SYSTEM_INITIALIZING should not modify state.")
    }

    // ========================================================================
    // INVENTORY_UPDATED
    // ========================================================================

    @Test
    fun `reducer INVENTORY_UPDATED populates backup entries`() {
        val initial = BackupState()
        val action = Action(ActionRegistry.Names.BACKUP_INVENTORY_UPDATED, buildJsonObject {
            put("backups", buildJsonArray {
                add(buildJsonObject {
                    put("filename", "raam-backup-20260404T150000Z.zip")
                    put("sizeBytes", 1024L)
                    put("createdAt", 1000000L)
                })
                add(buildJsonObject {
                    put("filename", "raam-backup-20260403T100000Z.zip")
                    put("sizeBytes", 2048L)
                    put("createdAt", 900000L)
                })
            })
        })
        val newState = feature.reducer(initial, action) as BackupState
        assertEquals(2, newState.backups.size)
        assertEquals("raam-backup-20260404T150000Z.zip", newState.backups[0].filename)
        assertEquals(1024L, newState.backups[0].sizeBytes)
        assertEquals(1000000L, newState.backups[0].createdAt)
        assertEquals("raam-backup-20260403T100000Z.zip", newState.backups[1].filename)
    }

    @Test
    fun `reducer INVENTORY_UPDATED with missing payload returns state unchanged`() {
        val initial = BackupState(backups = listOf(BackupEntry("old.zip")))
        val action = Action(ActionRegistry.Names.BACKUP_INVENTORY_UPDATED)
        val newState = feature.reducer(initial, action) as BackupState
        assertEquals(1, newState.backups.size, "Missing payload should leave backups unchanged.")
    }

    // ========================================================================
    // CREATE
    // ========================================================================

    @Test
    fun `reducer CREATE sets isCreating to true`() {
        val initial = BackupState(isCreating = false)
        val action = Action(ActionRegistry.Names.BACKUP_CREATE)
        val newState = feature.reducer(initial, action) as BackupState
        assertTrue(newState.isCreating)
    }

    // ========================================================================
    // RESTORE
    // ========================================================================

    @Test
    fun `reducer RESTORE sets pendingRestoreFilename`() {
        val initial = BackupState()
        val action = Action(ActionRegistry.Names.BACKUP_RESTORE, buildJsonObject {
            put("filename", "raam-backup-20260404T150000Z.zip")
        })
        val newState = feature.reducer(initial, action) as BackupState
        assertEquals("raam-backup-20260404T150000Z.zip", newState.pendingRestoreFilename)
    }

    @Test
    fun `reducer RESTORE with missing filename returns state unchanged`() {
        val initial = BackupState(pendingRestoreFilename = null)
        val action = Action(ActionRegistry.Names.BACKUP_RESTORE, buildJsonObject {})
        val newState = feature.reducer(initial, action) as BackupState
        assertNull(newState.pendingRestoreFilename)
    }

    // ========================================================================
    // EXECUTE_RESTORE
    // ========================================================================

    @Test
    fun `reducer EXECUTE_RESTORE sets isRestoring to true`() {
        val initial = BackupState(isRestoring = false)
        val action = Action(ActionRegistry.Names.BACKUP_EXECUTE_RESTORE, buildJsonObject {
            put("filename", "backup.zip")
        })
        val newState = feature.reducer(initial, action) as BackupState
        assertTrue(newState.isRestoring)
    }

    // ========================================================================
    // OPERATION_RESULT
    // ========================================================================

    @Test
    fun `reducer OPERATION_RESULT clears flags and sets lastResultMessage`() {
        val initial = BackupState(
            isCreating = true,
            isRestoring = true,
            pendingRestoreFilename = "backup.zip",
            lastResultMessage = null
        )
        val action = Action(ActionRegistry.Names.BACKUP_OPERATION_RESULT, buildJsonObject {
            put("operation", "create")
            put("success", true)
            put("message", "Backup created: raam-backup-20260404.zip")
        })
        val newState = feature.reducer(initial, action) as BackupState
        assertFalse(newState.isCreating, "isCreating should be cleared.")
        assertFalse(newState.isRestoring, "isRestoring should be cleared.")
        assertNull(newState.pendingRestoreFilename, "pendingRestoreFilename should be cleared.")
        assertEquals("Backup created: raam-backup-20260404.zip", newState.lastResultMessage)
    }

    @Test
    fun `reducer OPERATION_RESULT with no message sets lastResultMessage to null`() {
        val initial = BackupState(lastResultMessage = "old message")
        val action = Action(ActionRegistry.Names.BACKUP_OPERATION_RESULT, buildJsonObject {
            put("operation", "delete")
            put("success", true)
        })
        val newState = feature.reducer(initial, action) as BackupState
        assertNull(newState.lastResultMessage)
    }

    // ========================================================================
    // SETTINGS_VALUE_CHANGED
    // ========================================================================

    @Test
    fun `reducer SETTINGS_VALUE_CHANGED updates autoBackupEnabled`() {
        val initial = BackupState(autoBackupEnabled = true)
        val action = Action(ActionRegistry.Names.SETTINGS_VALUE_CHANGED, buildJsonObject {
            put("key", "backup.autoBackupEnabled")
            put("value", "false")
        })
        val newState = feature.reducer(initial, action) as BackupState
        assertFalse(newState.autoBackupEnabled)
    }

    @Test
    fun `reducer SETTINGS_VALUE_CHANGED updates maxBackups`() {
        val initial = BackupState(maxBackups = 20)
        val action = Action(ActionRegistry.Names.SETTINGS_VALUE_CHANGED, buildJsonObject {
            put("key", "backup.maxBackups")
            put("value", "10")
        })
        val newState = feature.reducer(initial, action) as BackupState
        assertEquals(10, newState.maxBackups)
    }

    @Test
    fun `reducer SETTINGS_VALUE_CHANGED ignores unrelated keys`() {
        val initial = BackupState(autoBackupEnabled = true, maxBackups = 20)
        val action = Action(ActionRegistry.Names.SETTINGS_VALUE_CHANGED, buildJsonObject {
            put("key", "session.some_other_key")
            put("value", "irrelevant")
        })
        val newState = feature.reducer(initial, action) as BackupState
        assertTrue(newState.autoBackupEnabled, "autoBackupEnabled should be unchanged.")
        assertEquals(20, newState.maxBackups, "maxBackups should be unchanged.")
    }

    @Test
    fun `reducer SETTINGS_VALUE_CHANGED with invalid boolean defaults to true`() {
        val initial = BackupState(autoBackupEnabled = false)
        val action = Action(ActionRegistry.Names.SETTINGS_VALUE_CHANGED, buildJsonObject {
            put("key", "backup.autoBackupEnabled")
            put("value", "not-a-bool")
        })
        val newState = feature.reducer(initial, action) as BackupState
        assertTrue(newState.autoBackupEnabled, "Invalid boolean should default to true.")
    }

    @Test
    fun `reducer SETTINGS_VALUE_CHANGED with invalid int defaults to 20`() {
        val initial = BackupState(maxBackups = 5)
        val action = Action(ActionRegistry.Names.SETTINGS_VALUE_CHANGED, buildJsonObject {
            put("key", "backup.maxBackups")
            put("value", "not-a-number")
        })
        val newState = feature.reducer(initial, action) as BackupState
        assertEquals(20, newState.maxBackups, "Invalid int should default to 20.")
    }

    // ========================================================================
    // SETTINGS_LOADED
    // ========================================================================

    @Test
    fun `reducer hydrates state from SETTINGS_LOADED`() {
        val initial = BackupState()
        val action = Action(ActionRegistry.Names.SETTINGS_LOADED, buildJsonObject {
            put("backup.autoBackupEnabled", "false")
            put("backup.maxBackups", "5")
        })
        val newState = feature.reducer(initial, action) as BackupState
        assertFalse(newState.autoBackupEnabled)
        assertEquals(5, newState.maxBackups)
    }

    @Test
    fun `reducer SETTINGS_LOADED with missing keys preserves existing values`() {
        val initial = BackupState(autoBackupEnabled = false, maxBackups = 42)
        val action = Action(ActionRegistry.Names.SETTINGS_LOADED, buildJsonObject {
            // No backup-related keys
            put("unrelated.key", "value")
        })
        val newState = feature.reducer(initial, action) as BackupState
        assertFalse(newState.autoBackupEnabled, "Missing key should preserve existing value.")
        assertEquals(42, newState.maxBackups, "Missing key should preserve existing value.")
    }

    @Test
    fun `reducer SETTINGS_LOADED with null payload returns state unchanged`() {
        val initial = BackupState(maxBackups = 15)
        val action = Action(ActionRegistry.Names.SETTINGS_LOADED)
        val newState = feature.reducer(initial, action) as BackupState
        assertEquals(15, newState.maxBackups)
    }
}