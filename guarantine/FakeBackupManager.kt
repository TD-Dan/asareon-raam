package app.auf

import app.auf.service.BackupManager
import app.auf.util.PlatformDependencies

/**
 * A fake implementation of BackupManager that prevents tests from performing
 * real file system I/O, while allowing us to spy on its methods.
 *
 * This class inherits from the real BackupManager to satisfy type constraints
 * in the StateManager, but overrides its core functionality.
 *
 * @version 2.0
 * @since 2025-08-15
 */
class FakeBackupManager(
    // The fake now accepts the same dependency as its parent.
    platform: PlatformDependencies
) : BackupManager(platform) { // The parent constructor is now correctly called.

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

    /**
     * Resets the spy properties to ensure test isolation.
     */
    fun reset() {
        createBackupCalled = false
        createBackupTrigger = null
        openBackupFolderCalled = false
    }
}