package app.auf

import java.io.File

/**
 * A fake implementation of BackupManager that prevents tests from performing
 * real file system I/O, while allowing us to spy on its methods.
 *
 * This class inherits from the real BackupManager to satisfy type constraints
 * in the StateManager, but overrides its core functionality to do nothing.
 */
class FakeBackupManager : BackupManager(
    // Provide dummy paths that won't be used.
    holonsBasePath = "",
    settingsDir = File("")
) {
    var createBackupCalled = false
    var createBackupTrigger: String? = null
    var openBackupFolderCalled = false

    /**
     * Overrides the real backup creation to do nothing but record the call.
     */
    override fun createBackup(trigger: String) {
        createBackupCalled = true
        createBackupTrigger = trigger
        // Do nothing to prevent file I/O during tests.
    }

    /**
     * Overrides the real folder opening to do nothing but record the call.
     */
    override fun openBackupFolder() {
        openBackupFolderCalled = true
        // Do nothing to prevent opening file explorer during tests.
    }
}