package app.auf.util

import com.sun.jna.Platform
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.Window
import java.awt.datatransfer.StringSelection
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.swing.JFileChooser
import javax.swing.filechooser.FileSystemView

/**
 * The actual JVM implementation of the PlatformDependencies contract.
 */
actual open class PlatformDependencies actual constructor(appVersion: String) {

    private val rootDataDir = File(System.getProperty("user.home"), ".auf/$appVersion").absolutePath

    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val displayFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val logFilePath: String by lazy {
        // Correctly construct the log path from the App Zone root.
        val logDir = File(getBasePathFor(BasePath.APP_ZONE), "logs").absolutePath
        createDirectories(logDir)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        "$logDir${File.separatorChar}session-$timestamp.log"
    }


    actual open val pathSeparator: Char = File.separatorChar

    // --- File & Directory I/O ---

    actual open fun readFileContent(path: String): String = File(path).readText()

    actual open fun writeFileContent(path: String, content: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    actual open fun fileExists(path: String): Boolean = File(path).exists()

    actual open fun listDirectory(path: String): List<FileEntry> {
        val directory = File(path)
        // --- THE FIX (TSK-FS-009) ---
        // Add explicit guard clauses to prevent silent failures.
        if (!directory.exists()) {
            throw IOException("Navigation failed: Path does not exist '$path'")
        }
        if (!directory.isDirectory) {
            throw IOException("Navigation failed: Path is not a directory '$path'")
        }
        // --- END FIX ---
        return directory.listFiles()
            ?.map { FileEntry(it.absolutePath, it.isDirectory) }
            ?: emptyList()
    }

    actual open fun listDirectoryRecursive(path: String): List<FileEntry> {
        val root = File(path)
        if (!root.exists()) throw IOException("Path does not exist or is not accessible: $path")
        if (!root.isDirectory) throw IOException("Path is not a directory: $path")

        return root.walkTopDown()
            .filter { it.isFile }
            .map { FileEntry(it.absolutePath, isDirectory = false) }
            .toList()
    }

    actual open fun createDirectories(path: String) {
        File(path).mkdirs()
    }

    actual open fun copyFile(sourcePath: String, destinationPath: String) {
        Files.copy(
            File(sourcePath).toPath(),
            File(destinationPath).toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
    }

    actual open fun deleteFile(path: String) {
        File(path).delete()
    }

    actual open fun deleteDirectory(path: String) {
        File(path).deleteRecursively()
    }

    actual open fun getBasePathFor(type: BasePath): String {
        return when (type) {
            BasePath.APP_ZONE -> rootDataDir
            BasePath.USER_ZONE -> getUserHomePath()
        }
    }

    actual open fun getFileName(path: String): String = File(path).name

    actual open fun getParentDirectory(path: String): String? = File(path).parent

    actual open fun getLastModified(path: String): Long = File(path).lastModified()

    actual open fun getUserHomePath(): String = System.getProperty("user.home")


    // --- Complex Operations ---

    actual open fun createZipArchive(sourceDirectoryPath: String, destinationZipPath: String) {
        val sourceDir = File(sourceDirectoryPath)
        val zipFile = File(destinationZipPath)
        zipFile.parentFile?.mkdirs()

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            sourceDir.walkTopDown().forEach { file ->
                if (file.absolutePath == sourceDir.absolutePath) return@forEach
                var entryName = sourceDir.toURI().relativize(file.toURI()).path
                if (file.isDirectory && !entryName.endsWith('/')) {
                    entryName += "/"
                }
                val zipEntry = ZipEntry(entryName)
                zos.putNextEntry(zipEntry)
                if (file.isFile) {
                    FileInputStream(file).use { fis -> fis.copyTo(zos) }
                }
                zos.closeEntry()
            }
        }
    }

    actual open fun openFolderInExplorer(path: String) {
        val dir = File(path)
        if (Desktop.isDesktopSupported() && dir.exists()) {
            try {
                Desktop.getDesktop().open(dir)
            } catch (e: Exception) {
                log(LogLevel.ERROR, "PlatformDependencies", "Failed to open folder at path '$path'", e)
            }
        }
    }

    actual open fun selectDirectoryPath(): String? {
        val fileChooser = JFileChooser(FileSystemView.getFileSystemView().homeDirectory).apply {
            dialogTitle = "Select Directory"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        return if (fileChooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            fileChooser.selectedFile.absolutePath
        } else {
            null
        }
    }

    // --- System Utilities ---

    actual open fun getSystemTimeMillis(): Long = System.currentTimeMillis()

    actual open fun generateUUID(): String = UUID.randomUUID().toString()

    actual open fun formatIsoTimestamp(timestamp: Long): String {
        return isoFormatter.format(Date(timestamp))
    }

    actual open fun parseIsoTimestamp(timestamp: String): Long? {
        return try {
            isoFormatter.parse(timestamp)?.time
        } catch (_: Exception) {
            null
        }
    }

    actual open fun formatDisplayTimestamp(timestamp: Long): String {
        return displayFormatter.format(Date(timestamp))
    }

    actual open fun copyToClipboard(text: String) {
        val selection = StringSelection(text)
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(selection, selection)
    }

    actual open fun applyNativeWindowDecorations(window: Any) {
        if (Platform.isWindows() && window is Window) {
            WindowsDarkMode.enable(window)
        }
    }

    // --- Durable & Real-time Logging Implementation ---
    @Synchronized
    actual open fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        val fullMessage : String = when (level) {
            // For ERROR and FATAL include full stack trace, and warn if exception details are provided
            LogLevel.ERROR, LogLevel.FATAL -> {
                if (throwable != null) {
                    "$message\n${throwable.stackTraceToString()}"
                } else {
                    "$message\n(Log call is missing exception details, add 'e' to the log call if possible!)"
                }
            }
            // For WARN include only the message if present
            LogLevel.WARN -> {
                if (throwable != null) {
                    "$message\n(e: ${throwable.message})"
                }
                else {
                    message
                }
            }
            else -> message
        }

        val logLine = "[${displayFormatter.format(Date())}] [${level.name}] [$tag] $fullMessage"

        if (level >= LogLevel.ERROR) {
            System.err.println(logLine)
        }
        else println(logLine)

        try {
            File(logFilePath).appendText(logLine + "\n")
        } catch (e: Exception) {
            System.err.println("!!! FAILED TO WRITE TO LOG FILE: ${e.message} !!!")
        }
    }
}