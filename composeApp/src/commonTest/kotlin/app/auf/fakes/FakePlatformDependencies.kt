package app.auf.fakes

import app.auf.util.BasePath
import app.auf.util.FileEntry
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies

private data class FakeFile(val content: String, val lastModified: Long)

/**
 * A public data class to hold a captured log message for test assertions.
 * [REFACTOR] Now includes an optional throwable to enable more precise test assertions.
 */
data class CapturedLog(val level: LogLevel, val tag: String, val message: String, val throwable: Throwable? = null)

/**
 * A "Fake" implementation of the PlatformDependencies contract for use in unit tests.
 *
 * @property capturedLogs A public list to store all log messages for test assertions.
 */
open class FakePlatformDependencies(
    private val appVersion: String
) : PlatformDependencies(appVersion) {
    private val files = mutableMapOf<String, FakeFile>()
    val directories = mutableSetOf<String>()
    var clipboardContent: String? = null
    var currentTime = 1_000_000_000_000L
    var uuidCounter = 0
    val capturedLogs = mutableListOf<CapturedLog>()
    val writtenFiles = mutableMapOf<String, String>()
    var selectedDirectoryPathToReturn: String? = null

    /** Tracks all scheduled callbacks for test assertions and manual firing. */
    data class ScheduledCallback(val delayMs: Long, val callback: () -> Unit, var cancelled: Boolean = false)
    val scheduledCallbacks = mutableListOf<ScheduledCallback>()

    override val pathSeparator: Char = '/'

    override fun readFileContent(path: String): String {
        return files[path]?.content ?: throw Exception("Fake file not found at path: $path")
    }

    override fun writeFileContent(path: String, content: String) {
        val parent = getParentDirectory(path)
        if (parent != null) {
            createDirectories(parent)
        }
        files[path] = FakeFile(content, currentTime)
        writtenFiles[path] = content
    }

    override fun fileExists(path: String): Boolean {
        return files.containsKey(path) || directories.contains(path)
    }

    override fun listDirectory(path: String): List<FileEntry> {
        if (!directories.contains(path) && path != "/") throw Exception("Path does not exist '$path'")
        val directChildren = mutableSetOf<String>()
        val pathWithSeparator = if (path.endsWith(pathSeparator) || path == "/") path else "$path$pathSeparator"
        (files.keys + directories).forEach { entryPath ->
            if (entryPath.startsWith(pathWithSeparator) && entryPath != pathWithSeparator) {
                val relativePath = entryPath.removePrefix(pathWithSeparator)
                val firstSegment = relativePath.split(pathSeparator).first()
                if (firstSegment.isNotEmpty()) {
                    directChildren.add(firstSegment)
                }
            }
        }
        return directChildren.map { childName ->
            val fullPath = if (path == "/") "/$childName" else "$pathWithSeparator$childName".removeSuffix("/")
            val isDir = directories.contains(fullPath)
            val lastMod = if (isDir) null else files[fullPath]?.lastModified
            FileEntry(fullPath, isDir, lastModified = lastMod)
        }
    }

    override fun listDirectoryRecursive(path: String): List<FileEntry> {
        if (!directories.contains(path)) throw Exception("Path does not exist '$path'")
        val pathWithSeparator = if (path.endsWith(pathSeparator)) path else "$path$pathSeparator"
        return files.keys
            .filter { it.startsWith(pathWithSeparator) }
            .map { FileEntry(it, isDirectory = false, lastModified = files[it]?.lastModified) }
    }

    override fun createDirectories(path: String) {
        var currentPath = ""
        val parts = path.split(pathSeparator).filter { it.isNotEmpty() }
        if (path.startsWith(pathSeparator)) {
            currentPath = pathSeparator.toString()
            directories.add(currentPath)
        }
        for (part in parts) {
            currentPath = if (currentPath.endsWith(pathSeparator)) {
                "$currentPath$part"
            } else if (currentPath.isEmpty()){
                part
            } else {
                "$currentPath$pathSeparator$part"
            }
            directories.add(currentPath)
        }
    }

    override fun copyFile(sourcePath: String, destinationPath: String) {
        val sourceFile = files[sourcePath] ?: throw Exception("Fake source file not found: $sourcePath")
        files[destinationPath] = sourceFile.copy(lastModified = currentTime)
    }

    override fun deleteFile(path: String) {
        files.remove(path)
        writtenFiles.remove(path)
    }

    override fun deleteDirectory(path: String) {
        val pathWithSeparator = if (path.endsWith(pathSeparator)) path else "$path$pathSeparator"
        files.keys.removeAll { it.startsWith(pathWithSeparator) }
        writtenFiles.keys.removeAll { it.startsWith(pathWithSeparator) }
        directories.removeAll { it.startsWith(pathWithSeparator) }
        directories.remove(path)
    }

    override fun getBasePathFor(type: BasePath): String {
        return when (type) {
            BasePath.APP_ZONE -> "/fake/.auf/$appVersion"
            BasePath.USER_ZONE -> getUserHomePath()
        }
    }

    override fun getFileName(path: String): String {
        return path.substringAfterLast(pathSeparator)
    }

    override fun getParentDirectory(path: String): String? {
        if (!path.contains(pathSeparator) || path == "/") return null
        return path.substringBeforeLast(pathSeparator).ifEmpty { "/" }
    }

    override fun getLastModified(path: String): Long {
        return files[path]?.lastModified ?: directories.find { it == path }?.let { currentTime } ?: 0L
    }

    override fun getUserHomePath(): String = "/fake/user/home"

    override fun createZipArchive(sourceDirectoryPath: String, destinationZipPath: String) { /* No-op */ }
    override fun openFolderInExplorer(path: String) { /* No-op */ }
    override fun selectDirectoryPath(): String? = selectedDirectoryPathToReturn

    override fun getSystemTimeMillis(): Long = currentTime
    override fun generateUUID(): String {
        uuidCounter++
        return "fake-uuid-$uuidCounter"
    }
    override fun formatIsoTimestamp(timestamp: Long): String = "ISO_TIMESTAMP_$timestamp"
    override fun parseIsoTimestamp(timestamp: String): Long? {
        // Support the fake format produced by formatIsoTimestamp
        return if (timestamp.startsWith("ISO_TIMESTAMP_")) {
            timestamp.removePrefix("ISO_TIMESTAMP_").toLongOrNull()
        } else {
            null
        }
    }
    override fun formatDisplayTimestamp(timestamp: Long): String = "DISPLAY_TIMESTAMP_$timestamp"
    override fun copyToClipboard(text: String) { clipboardContent = text }
    override fun applyNativeWindowDecorations(window: Any) { /* No-op */ }

    // --- Scheduling ---

    override fun scheduleDelayed(delayMs: Long, callback: () -> Unit): Any? {
        val entry = ScheduledCallback(delayMs, callback)
        scheduledCallbacks.add(entry)
        // Return the index as a handle for cancellation
        return scheduledCallbacks.size - 1
    }

    override fun cancelScheduled(handle: Any?) {
        val index = handle as? Int ?: return
        if (index in scheduledCallbacks.indices) {
            scheduledCallbacks[index] = scheduledCallbacks[index].copy(cancelled = true)
        }
    }

    /**
     * Test utility: fires all pending (non-cancelled) scheduled callbacks immediately.
     * Useful for simulating timeout scenarios in tests.
     */
    fun fireAllScheduledCallbacks() {
        scheduledCallbacks.filter { !it.cancelled }.forEach { it.callback() }
    }

    /**
     * Test utility: fires only scheduled callbacks with the specified delay.
     */
    fun fireScheduledCallbacks(delayMs: Long) {
        scheduledCallbacks.filter { !it.cancelled && it.delayMs == delayMs }.forEach { it.callback() }
    }

    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        capturedLogs.add(CapturedLog(level, tag, message, throwable))
    }
}