package app.auf.util

import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.Window
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID // ADDED IMPORT
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.swing.JFileChooser
import javax.swing.filechooser.FileSystemView
import com.sun.jna.Platform

/**
 * The actual JVM implementation of the PlatformDependencies contract.
 *
 * @version 2.7
 * @since 2025-08-17
 */
actual open class PlatformDependencies {

    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val displayFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())


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
        return File(path).listFiles()
            ?.map { FileEntry(it.absolutePath, it.isDirectory) }
            ?: emptyList()
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

    actual open fun getBasePathFor(type: BasePath): String {
        return when (type) {
            BasePath.SETTINGS -> File(System.getProperty("user.home"), ".auf").absolutePath
            BasePath.BACKUPS -> File(getBasePathFor(BasePath.SETTINGS), "backups").absolutePath
            BasePath.HOLONS -> File("holons").absolutePath
            BasePath.FRAMEWORK -> File("framework").absolutePath
            BasePath.SESSIONS -> File( "sessions").absolutePath
        }
    }

    actual open fun getFileName(path: String): String = File(path).name

    actual open fun getParentDirectory(path: String): String? = File(path).parent

    actual open fun getLastModified(path: String): Long = File(path).lastModified()


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
                println("Failed to open folder: ${e.message}")
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

    actual open fun generateUUID(): String = UUID.randomUUID().toString() // ADDED THIS FUNCTION

    actual open fun formatIsoTimestamp(timestamp: Long): String {
        return isoFormatter.format(Date(timestamp))
    }

    actual open fun formatDisplayTimestamp(timestamp: Long): String {
        return displayFormatter.format(Date(timestamp))
    }

    actual open fun copyToClipboard(text: String) {
        val selection = StringSelection(text)
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(selection, selection)
    }
    /**
     * On the JVM, this function checks if the OS is Windows and if the passed
     * object is a valid AWT Window. If so, it invokes our JNA utility to
     * enable the dark mode title bar. On other OSes (macOS, Linux), it does nothing.
     */
    actual open fun applyNativeWindowDecorations(window: Any) {
        if (Platform.isWindows() && window is Window) {
            WindowsDarkMode.enable(window)
        }
    }
}