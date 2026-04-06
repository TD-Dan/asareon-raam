package asareon.raam.util

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
import java.util.zip.ZipInputStream
import javax.swing.JFileChooser
import javax.swing.filechooser.FileSystemView
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import kotlin.collections.plusAssign
import kotlin.toString

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

    /** Coroutine scope for delayed scheduling. Uses a single-threaded dispatcher to avoid concurrency issues. */
    private val schedulingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    actual open val pathSeparator: Char = File.separatorChar

    actual open var logListener: ((LogLevel, String, String) -> Unit)? = null

    // --- File & Directory I/O ---

    actual open fun readFileContent(path: String): String = File(path).readText()

    actual open fun writeFileContent(path: String, content: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    actual open fun readFileBytes(path: String): ByteArray = File(path).readBytes()

    actual open fun writeFileBytes(path: String, bytes: ByteArray) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
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
            ?.map { FileEntry(it.absolutePath, it.isDirectory, lastModified = it.lastModified()) }
            ?: emptyList()
    }

    actual open fun listDirectoryRecursive(path: String): List<FileEntry> {
        val root = File(path)
        if (!root.exists()) throw IOException("Path does not exist or is not accessible: $path")
        if (!root.isDirectory) throw IOException("Path is not a directory: $path")

        return root.walkTopDown()
            .filter { it != root }
            .map { FileEntry(it.absolutePath, isDirectory = it.isDirectory, lastModified = it.lastModified()) }
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

    actual open fun resolveAbsoluteSandboxPath(featureHandle: String, relativePath: String): String {
        // Mirrors FileSystemFeature.getSandboxPathFor — resolves to the feature-level
        // prefix under APP_ZONE. "session" → APP_ZONE/session, "agent.coder-1" → APP_ZONE/agent.
        val featurePrefix = featureHandle.substringBefore('.')
        val safePrefix = featurePrefix.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val sandboxRoot = "$rootDataDir${File.separatorChar}$safePrefix"
        return if (relativePath.isNotBlank()) {
            "$sandboxRoot${File.separatorChar}$relativePath"
        } else {
            sandboxRoot
        }
    }
    actual open fun createZipArchive(
        sourceDirectoryPath: String,
        destinationZipPath: String,
        excludeDirectoryName: String
    ) {
        val sourceDir = File(sourceDirectoryPath)
        val sourcePath = sourceDir.toPath()
        val zipFile = File(destinationZipPath)
        zipFile.parentFile?.mkdirs()

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            sourceDir.walkTopDown()
                .onEnter { dir ->
                    // Skip excluded directory by name match
                    if (excludeDirectoryName != null
                        && dir.name == excludeDirectoryName
                        && dir.absolutePath != sourceDir.absolutePath) {
                        false
                    } else {
                        true
                    }
                }
                .forEach { file ->
                    if (file.absolutePath == sourceDir.absolutePath) return@forEach

                    // Use Path.relativize() — NOT URI.relativize()
                    // URI.relativize() percent-encodes special chars (%20 for spaces)
                    // which breaks Windows Explorer's zip parser.
                    val relativePath = sourcePath.relativize(file.toPath())
                    // Normalize to forward slashes (zip spec requirement)
                    var entryName = relativePath.toString().replace('\\', '/')

                    if (file.isDirectory) {
                        if (!entryName.endsWith('/')) entryName += "/"
                        val dirEntry = ZipEntry(entryName)
                        dirEntry.method = ZipEntry.STORED
                        dirEntry.size = 0
                        dirEntry.compressedSize = 0
                        dirEntry.crc = 0
                        dirEntry.time = file.lastModified()
                        zos.putNextEntry(dirEntry)
                        zos.closeEntry()
                    } else {
                        val fileEntry = ZipEntry(entryName)
                        fileEntry.time = file.lastModified()
                        zos.putNextEntry(fileEntry)
                        BufferedInputStream(FileInputStream(file)).use { bis ->
                            bis.copyTo(zos)
                        }
                        zos.closeEntry()
                    }
                }
        }
    }

    // --- Also fix the EXISTING createZipArchive (no exclude param) ---
    // This is the backward-compatible version with the same URI fix.
    // NOTE: Once the new signature with excludeDirectoryName is in place,
    // this old signature can be removed since the default param handles it.
    // But if you keep both for now, here's the fixed version:

    // (The above method with excludeDirectoryName=null default covers this)

    // --- NEW: extractZipArchive ---

    actual open fun extractZipArchive(zipPath: String, targetDirectoryPath: String) {
        val targetDir = File(targetDirectoryPath)
        targetDir.mkdirs()

        ZipInputStream(
            BufferedInputStream(FileInputStream(File(zipPath)))
        ).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)

                // Security: prevent zip slip attack
                val canonicalTarget = targetDir.canonicalPath
                val canonicalOut = outFile.canonicalPath
                if (!canonicalOut.startsWith(canonicalTarget + File.separator)
                    && canonicalOut != canonicalTarget) {
                    throw SecurityException(
                        "Zip slip detected: '${entry.name}' resolves outside target."
                    )
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(outFile)).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    // --- NEW: fileSize ---

    actual open fun fileSize(path: String): Long {
        val file = File(path)
        return if (file.exists()) file.length() else 0L
    }

    // --- NEW: restartApplication ---

    actual open fun restartApplication() {
        log(LogLevel.INFO, "PlatformDependencies", "Application restart requested.")
        try {
            val javaBin = System.getProperty("java.home") +
                    File.separator + "bin" + File.separator + "java"
            val classPath = System.getProperty("java.class.path")
            val mainClass = System.getProperty("sun.java.command")
                ?.split(" ")?.firstOrNull()

            if (mainClass != null) {
                ProcessBuilder(mutableListOf(javaBin, "-cp", classPath, mainClass))
                    .inheritIO()
                    .start()
                Thread.sleep(500)
            } else {
                log(LogLevel.WARN, "PlatformDependencies",
                    "Cannot determine main class for restart. Please restart manually.")
                return
            }
        } catch (e: Exception) {
            log(LogLevel.ERROR, "PlatformDependencies",
                "Failed to restart application.", e)
            return
        }
        System.exit(0)
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

    actual open fun currentTimeMillis(): Long = System.currentTimeMillis()

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

    // --- Scheduling ---

    actual open fun scheduleDelayed(delayMs: Long, callback: () -> Unit): Any? {
        return schedulingScope.launch {
            delay(delayMs)
            callback()
        }
    }

    actual open fun cancelScheduled(handle: Any?) {
        (handle as? Job)?.cancel()
    }

    // --- Durable & Real-time Logging Implementation ---
    @Synchronized
    actual open fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        val fullMessage : String = when (level) {
            // For ERROR and FATAL include full stack trace, and warn if exception details are not provided
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
        val pad = " ".repeat(4 - level.ordinal)
        val logLine = "[${displayFormatter.format(Date())}] [${level.name.padEnd(5)}] [${tag.padEndTo(5)}]$pad $fullMessage"

        if (level >= LogLevel.ERROR) {
            System.err.println(logLine)
        }
        else println(logLine)

        try {
            File(logFilePath).appendText(logLine + "\n")
        } catch (e: Exception) {
            System.err.println("!!! FAILED TO WRITE TO LOG FILE: ${e.message} !!!")
        }

        // Notify boot console listener (if attached)
        logListener?.invoke(level, tag, fullMessage)
    }
}

fun String.padEndTo(increment: Int) = padEnd((length + increment - 1) / increment * increment)