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
 * It uses java.io, java.nio, java.awt, and java.text to fulfill the requirements.
 * This class is the single point of contact with the JVM's file system and OS services.
 *
 * @version 2.0
 * @since 2025-08-15
 */
actual class PlatformDependencies {

    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    actual val pathSeparator: Char = File.separatorChar

    // --- File & Directory I/O ---

    actual fun readFileContent(path: String): String = File(path).readText()

    actual fun writeFileContent(path: String, content: String) {
        val file = File(path)
        // Ensure parent directories exist before writing.
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    actual fun fileExists(path: String): Boolean = File(path).exists()

    actual fun listDirectory(path: String): List<FileEntry> {
        return File(path).listFiles()
            ?.map { FileEntry(it.absolutePath, it.isDirectory) }
            ?: emptyList()
    }

    actual fun createDirectories(path: String) {
        File(path).mkdirs()
    }

    actual fun copyFile(sourcePath: String, destinationPath: String) {
        Files.copy(
            File(sourcePath).toPath(),
            File(destinationPath).toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
    }

    actual fun deleteFile(path: String) {
        File(path).delete()
    }

    actual fun getBasePathFor(type: String): String {
        return when (type) {
            "settings" -> File(System.getProperty("user.home"), ".auf").absolutePath
            "backups" -> File(getBasePathFor("settings"), "backups").absolutePath
            // Assumes root-level folders like "holons", "framework" for default cases.
            else -> File(type).absolutePath
        }
    }

    actual fun getFileName(path: String): String = File(path).name

    actual fun getParentDirectory(path: String): String? = File(path).parent

    actual fun getLastModified(path: String): Long = File(path).lastModified()


    // --- Complex Operations ---

    actual fun createZipArchive(sourceDirectoryPath: String, destinationZipPath: String) {
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

    actual fun openFolderInExplorer(path: String) {
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

    actual fun getSystemTimeMillis(): Long = System.currentTimeMillis()

    actual fun formatIsoTimestamp(timestamp: Long): String {
        return isoFormatter.format(Date(timestamp))
    }
}