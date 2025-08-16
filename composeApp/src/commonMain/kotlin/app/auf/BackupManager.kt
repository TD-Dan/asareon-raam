package app.auf

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * ---
 * ## Mandate
 * Provides a platform-agnostic service for managing all backup-related operations.
 * This class contains only business logic. It orchestrates backup creation and folder
 * management by delegating all file system and OS interactions to the injected
 * `PlatformDependencies` instance. This class has no knowledge of the underlying
 * file system implementation (e.g., java.io.File).
 *
 * ---
 * ## Dependencies
 * - `app.auf.PlatformDependencies`: The contract for all platform-specific I/O.
 *
 * @version 2.0
 * @since 2025-08-15
 */
open class BackupManager(
    private val platform: PlatformDependencies // The ONLY dependency
) {
    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    /**
     * Creates a zip archive of the entire 'holons' directory.
     * This is a non-blocking operation launched in a background coroutine.
     * @param trigger A string to name the backup source (e.g., "on-launch", "pre-export")
     */
    open fun createBackup(trigger: String) {
        coroutineScope.launch {
            try {
                val holonsPath = platform.getBasePathFor(BasePath.HOLONS)
                val backupsPath = platform.getBasePathFor(BasePath.BACKUPS)
                platform.createDirectories(backupsPath)

                if (!platform.fileExists(holonsPath)) {
                    println("Backup failed: Holons directory not found at '$holonsPath'")
                    return@launch
                }

                // Construct a timestamped filename, e.g., "auf-backup-20250815-153000-on-launch.zip"
                val timestamp = platform.formatIsoTimestamp(platform.getSystemTimeMillis())
                    .replace(":", "")
                    .replace("-", "")
                    .replace("T", "-")
                    .removeSuffix("Z")

                val zipFileName = "auf-backup-$timestamp-$trigger.zip"
                val zipFilePath = backupsPath + platform.pathSeparator + zipFileName

                platform.createZipArchive(holonsPath, zipFilePath)
                println("Backup created successfully at $zipFilePath")
            } catch (e: Exception) {
                println("Automatic backup failed: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * Opens the user's backup folder in the default system file explorer.
     */
    open fun openBackupFolder() {
        platform.openFolderInExplorer(platform.getBasePathFor(BasePath.BACKUPS))
    }
}