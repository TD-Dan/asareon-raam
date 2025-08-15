@file:OptIn(ExperimentalTime::class)

package app.auf

import java.io.File
import java.sql.Timestamp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.swing.JFileChooser
import javax.swing.filechooser.FileSystemView
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The actual JVM implementation of the PlatformDependencies contract.
 * It uses java.io, java.text, and javax.swing to fulfill the requirements.
 *
 * ---
 * ## Mandate
 * This class provides the concrete implementations for platform-specific operations on the JVM.
 * It is responsible for all direct interactions with Java libraries for file system access
 * and user interface dialogs like the folder picker.
 *
 * ---
 * ## Dependencies
 * - `java.io.File`: For file I/O.
 * - `java.text.SimpleDateFormat`: For timestamp formatting.
 * - `javax.swing.JFileChooser`: For the native folder picker dialog.
 *
 * @version 1.1
 * @since 2025-08-15
 */
actual class PlatformDependencies {
    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    actual val user_home: String = System.getProperty("user.home")
    actual val data_dir: String = System.getProperty("user.dir")

    actual fun settingsDirPath(): String {
        return System.getProperty( File(user_home, ".auf/" + DefaultPaths.SETTINGS_FILE).absolutePath)
    }

    actual fun holonsDirPath(): String {
        return System.getProperty(File(data_dir, DefaultPaths.HOLONS_DIR).absolutePath)
    }

    actual fun backupsDirPath(): String {
        return System.getProperty(File(data_dir, DefaultPaths.BACKUPS_DIR).absolutePath)
    }

    actual fun readFileContent(filePath: String): String {
        return try {
            File(filePath).readText()
        } catch (e: Exception) {
            "Error reading file: $filePath. Details: ${e.message}"
        }
    }

    actual fun getTimeMilliseconds(): Long {
        return Clock.System.now().toEpochMilliseconds()
    }

    actual fun formatIsoTimestamp(timeMilliseconds : Long): String {
        return isoFormatter.format(timeMilliseconds)
    }

    actual fun showFolderPicker(): String? {
        val fileChooser = JFileChooser(FileSystemView.getFileSystemView().homeDirectory).apply {
            dialogTitle = "Select Folder"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        return if (fileChooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            fileChooser.selectedFile.absolutePath
        } else {
            null
        }
    }


}