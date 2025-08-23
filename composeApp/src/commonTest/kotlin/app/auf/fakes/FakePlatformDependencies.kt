// --- FILE: composeApp/src/commonTest/kotlin/app/auf/fakes/FakePlatformDependencies.kt ---
package app.auf.fakes

import app.auf.util.BasePath
import app.auf.util.FileEntry
import app.auf.util.PlatformDependencies

class FakePlatformDependencies : PlatformDependencies() {
    val files = mutableMapOf<String, String>()
    val directories = mutableSetOf<String>()
    var lastCopiedToClipboard: String? = null

    // --- NEW: Testing infrastructure for simulating failures ---
    private var failOnWriteForPath: String? = null
    fun setFailOnWriteForPath(path: String) {
        failOnWriteForPath = path
    }
    // --- END NEW ---

    override val pathSeparator: Char = '/'

    override fun readFileContent(path: String): String {
        return files[path] ?: throw Exception("File not found in fake file system: $path")
    }

    override fun writeFileContent(path: String, content: String) {
        // --- NEW: Check for simulated failure ---
        if (path == failOnWriteForPath) {
            failOnWriteForPath = null // Consume the failure flag
            throw Exception("Simulated write failure for path: $path")
        }
        // --- END NEW ---
        val parent = getParentDirectory(path)
        if (parent != null) {
            createDirectories(parent)
        }
        files[path] = content
    }

    override fun fileExists(path: String): Boolean = files.containsKey(path) || directories.contains(path)

    override fun listDirectory(path: String): List<FileEntry> {
        val normalizedPath = if (path.endsWith(pathSeparator)) path else "$path$pathSeparator"
        return files.keys.filter { it.startsWith(normalizedPath) && !it.substring(normalizedPath.length).contains(pathSeparator) }
            .map { FileEntry(it, isDirectory = false) } +
                directories.filter { it.startsWith(normalizedPath) && !it.substring(normalizedPath.length).contains(pathSeparator) }
                    .map { FileEntry(it, isDirectory = true) }
    }

    override fun createDirectories(path: String) {
        var current = ""
        path.split(pathSeparator).forEach { part ->
            if (part.isNotEmpty()) {
                current += part + pathSeparator
                directories.add(current.removeSuffix(pathSeparator.toString()))
            }
        }
    }

    override fun copyFile(sourcePath: String, destinationPath: String) {
        if (!files.containsKey(sourcePath)) throw Exception("Source file not found: $sourcePath")
        files[destinationPath] = files[sourcePath]!!
    }

    override fun deleteFile(path: String) {
        files.remove(path)
    }

    override fun getBasePathFor(type: BasePath): String {
        return when (type) {
            BasePath.SETTINGS -> "/user/home/.auf"
            BasePath.BACKUPS -> "/user/home/.auf/backups"
            BasePath.HOLONS -> "holons"
            BasePath.FRAMEWORK -> "framework"
        }
    }

    override fun getFileName(path: String): String = path.substringAfterLast(pathSeparator)
    override fun getParentDirectory(path: String): String? = if (path.contains(pathSeparator)) path.substringBeforeLast(pathSeparator) else null
    override fun getLastModified(path: String): Long = 123456789L
    override fun createZipArchive(sourceDirectoryPath: String, destinationZipPath: String) {}
    override fun openFolderInExplorer(path: String) {}
    override fun selectDirectoryPath(): String? = "/fake/selected/directory"
    override fun getSystemTimeMillis(): Long = 123456789L
    override fun formatIsoTimestamp(timestamp: Long): String = "1973-11-29T21:33:09Z"
    override fun formatDisplayTimestamp(timestamp: Long): String = "21:33:09"
    override fun copyToClipboard(text: String) {
        lastCopiedToClipboard = text
    }
    override fun applyNativeWindowDecorations(window: Any) {}
}