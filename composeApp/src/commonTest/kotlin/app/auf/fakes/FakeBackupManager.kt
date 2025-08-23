package app.auf.fakes

import app.auf.service.BackupManager
import app.auf.util.PlatformDependencies

class FakeBackupManager(platform: PlatformDependencies) : BackupManager(platform) {
    var createBackupCalledWith: String? = null
    var openBackupFolderCalled = false

    override fun createBackup(trigger: String) {
        createBackupCalledWith = trigger
    }

    override fun openBackupFolder() {
        openBackupFolderCalled = true
    }
}