package app.auf

import java.awt.Desktop
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The actual JVM implementation of the PlatformDependencies contract.
 *
 * @version 2.3
 * @since 2025-08-15
 */
actual open class PlatformDependencies {

    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("UTC")
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

    // --- System Utilities ---

    actual open fun getSystemTimeMillis(): Long = System.currentTimeMillis()

    actual open fun formatIsoTimestamp(timestamp: Long): String {
        return isoFormatter.format(Date(timestamp))
    }
}