package app.auf.fakes

import app.auf.util.BasePath
import app.auf.util.FileEntry
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies

private data class FakeFile(val content: String, val lastModified: Long)

/**
 * A public data class to hold a captured log message for test assertions.
 * Its visibility is now correct.
 */
data class CapturedLog(val level: LogLevel, val tag: String, val message: String)

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
    private var uuidCounter = 0
    val capturedLogs = mutableListOf<CapturedLog>() // This property is now correctly defined.

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
    }

    override fun fileExists(path: String): Boolean {
        return files.containsKey(path) || directories.contains(path)
    }

    override fun listDirectory(path: String): List<FileEntry> {
        if (!directories.contains(path)) throw java.io.IOException("Path does not exist '$path'")
        val directChildren = mutableSetOf<String>()
        val pathWithSeparator = if (path.endsWith(pathSeparator)) path else "$path$pathSeparator"
        (files.keys + directories).forEach { entryPath ->
            if (entryPath.startsWith(pathWithSeparator)) {
                val relativePath = entryPath.removePrefix(pathWithSeparator)
                val firstSegment = relativePath.split(pathSeparator).first()
                if (firstSegment.isNotEmpty()) {
                    directChildren.add(firstSegment)
                }
            }
        }
        return directChildren.map { childName ->
            val fullPath = "$pathWithSeparator$childName".removeSuffix("/")
            FileEntry(fullPath, directories.contains(fullPath))
        }
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
        directories.remove(path)
    }

    override fun getBasePathFor(type: BasePath): String {
        return "/fake/$appVersion/${type.name.lowercase()}"
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
    override fun selectDirectoryPath(): String? = "/fake/selected/directory"

    override fun getSystemTimeMillis(): Long = currentTime
    override fun generateUUID(): String {
        uuidCounter++
        return "fake-uuid-$uuidCounter"
    }
    override fun formatIsoTimestamp(timestamp: Long): String = "ISO_TIMESTAMP_$timestamp"
    override fun formatDisplayTimestamp(timestamp: Long): String = "DISPLAY_TIMESTAMP_$timestamp"
    override fun copyToClipboard(text: String) { clipboardContent = text }
    override fun applyNativeWindowDecorations(window: Any) { /* No-op */ }

    override fun log(level: LogLevel, tag: String, message: String) {
        capturedLogs.add(CapturedLog(level, tag, message))
    }
}