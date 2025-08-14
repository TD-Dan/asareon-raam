package app.auf

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Manages all backup-related operations for the AUF application.
 * This class is responsible for creating and locating backup archives.
 */
open class BackupManager(
    private val holonsBasePath: String,
    private val settingsDir: File
) {
    private val coroutineScope = CoroutineScope(Dispatchers.Default)
    private val backupsDir = File(settingsDir, "backups")

    init {
        // Ensure the backups directory exists.
        backupsDir.mkdirs()
    }

    /**
     * Creates a zip archive of the entire 'holons' directory.
     * This is a non-blocking operation launched in a background coroutine.
     * @param trigger A string to name the backup source (e.g., "on-launch", "pre-export")
     */
    open fun createBackup(trigger: String) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val sourceDir = File(holonsBasePath)
                if (!sourceDir.exists() || !sourceDir.isDirectory) {
                    println("Backup failed: Holons directory not found at '${sourceDir.absolutePath}'")
                    return@launch
                }

                val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
                val zipFile = File(backupsDir, "auf-backup-$timestamp-$trigger.zip")

                ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                    sourceDir.walkTopDown().forEach { file ->
                        if (file.isFile) {
                            val zipEntry = ZipEntry(sourceDir.toURI().relativize(file.toURI()).path)
                            zos.putNextEntry(zipEntry)
                            FileInputStream(file).use { fis ->
                                fis.copyTo(zos)
                            }
                            zos.closeEntry()
                        }
                    }
                }
                println("Backup created successfully at ${zipFile.absolutePath}")
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
        if (Desktop.isDesktopSupported() && backupsDir.exists()) {
            try {
                Desktop.getDesktop().open(backupsDir)
            } catch (e: Exception) {
                println("Failed to open backup folder: ${e.message}")
                // In a real app, this could update state to show an error in the UI
            }
        } else {
            println("Cannot open backup folder. Desktop support: ${Desktop.isDesktopSupported()}, Folder exists: ${backupsDir.exists()}")
        }
    }
}