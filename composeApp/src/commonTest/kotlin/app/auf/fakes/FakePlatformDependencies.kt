package app.auf.fakes

import app.auf.util.BasePath
import app.auf.util.FileEntry
import app.auf.util.PlatformDependencies

/**
 * A fake, in-memory implementation of the PlatformDependencies contract for use in unit tests.
 *
 * --- FIX: Added `override` to all members and implemented missing functions from the contract. ---
 *
 * @version 1.2
 * @since 2025-08-23
 */
class FakePlatformDependencies : PlatformDependencies() {
    val files = mutableMapOf<String, String>()
    val directories = mutableSetOf<String>()
    val copiedFiles = mutableMapOf<String, String>()
    var lastOpenedFolder: String? = null
    var lastCopiedToClipboard: String? = null
    var zipArchiveCreated: Pair<String, String>? = null

    override val pathSeparator: Char = '/'

    override fun readFileContent(path: String): String {
        return files[path] ?: throw Exception("File not found in fake file system: $path")
    }

    override fun writeFileContent(path: String, content: String) {
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
        copiedFiles[sourcePath] = destinationPath
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
    override fun createZipArchive(sourceDirectoryPath: String, destinationZipPath: String) {
        zipArchiveCreated = sourceDirectoryPath to destinationZipPath
    }

    override fun openFolderInExplorer(path: String) {
        lastOpenedFolder = path
    }

    // --- NEWLY IMPLEMENTED TO MATCH CONTRACT ---
    override fun selectDirectoryPath(): String? {
        return "/fake/selected/directory"
    }

    override fun getSystemTimeMillis(): Long = 123456789L
    override fun formatIsoTimestamp(timestamp: Long): String = "1973-11-29T21:33:09Z"

    override fun formatDisplayTimestamp(timestamp: Long): String {
        return "21:33:09"
    }

    override fun copyToClipboard(text: String) {
        lastCopiedToClipboard = text
    }



    override fun applyNativeWindowDecorations(window: Any) {
        // No-op in fake
    }

    // Helper for test assertions
    fun clear() {
        files.clear()
        directories.clear()
        copiedFiles.clear()
        lastOpenedFolder = null
        zipArchiveCreated = null
        lastCopiedToClipboard = null
    }
}