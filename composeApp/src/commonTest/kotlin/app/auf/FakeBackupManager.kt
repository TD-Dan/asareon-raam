package app.auf

/**
 * A fake implementation of BackupManager that does nothing.
 * This prevents tests from performing real file system I/O.
 */
object FakeBackupManager : BackupManager {
    var createBackupCalled = false

    override fun createBackup(basePath: String, type: String) {
        createBackupCalled = true
        // Do nothing.
    }
}