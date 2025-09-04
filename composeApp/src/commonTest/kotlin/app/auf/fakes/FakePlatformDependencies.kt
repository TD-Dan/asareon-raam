package app.auf.util.fakes

import app.auf.util.BasePath
import app.auf.util.FileEntry
import app.auf.util.PlatformDependencies

/**
 * A "Fake" implementation of the PlatformDependencies contract for use in unit tests.
 * This class simulates platform interactions in a predictable, in-memory way, removing
 * dependencies on the actual file system, system clock, or UI.
 *
 * @property files A mutable map to simulate a file system where the key is the path
 *                 and the value is the file's content.
 * @property directories A mutable set to keep track of created directory paths.
 * @property clipboardContent A string to simulate the system clipboard.
 * @property currentTime A controllable value for the system time in milliseconds.
 */
open class FakePlatformDependencies : PlatformDependencies() {

    val files = mutableMapOf<String, String>()
    val directories = mutableSetOf<String>()
    var clipboardContent: String? = null
    var currentTime = 1_000_000_000_000L // A fixed starting time for predictability

    override val pathSeparator: Char = '/'

    // --- File & Directory I/O ---

    override fun readFileContent(path: String): String {
        return files[path] ?: throw Exception("Fake file not found at path: $path")
    }

    override fun writeFileContent(path: String, content: String) {
        val parent = getParentDirectory(path)
        if (parent != null) {
            createDirectories(parent)
        }
        files[path] = content
    }

    override fun fileExists(path: String): Boolean {
        return files.containsKey(path) || directories.contains(path)
    }

    override fun listDirectory(path: String): List<FileEntry> {
        if (!directories.contains(path)) return emptyList()

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
        // --- FIX: Correctly handle absolute paths and build the hierarchy ---
        var currentPath = ""
        val parts = path.split(pathSeparator)
        // Handle the root directory for absolute paths
        if (path.startsWith(pathSeparator)) {
            currentPath = pathSeparator.toString()
        }

        for (part in parts) {
            if (part.isNotEmpty()) {
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
    }

    override fun copyFile(sourcePath: String, destinationPath: String) {
        files[destinationPath] = files[sourcePath] ?: throw Exception("Fake source file not found: $sourcePath")
    }

    override fun deleteFile(path: String) {
        files.remove(path)
        directories.remove(path)
    }

    override fun getBasePathFor(type: BasePath): String {
        return "/fake/${type.name.lowercase()}"
    }

    override fun getFileName(path: String): String {
        return path.substringAfterLast(pathSeparator)
    }

    override fun getParentDirectory(path: String): String? {
        return if (path.contains(pathSeparator)) path.substringBeforeLast(pathSeparator) else null
    }

    override fun getLastModified(path: String): Long {
        return currentTime // For tests, assume all files are modified "now"
    }

    // --- Complex Operations (No-Op for most tests) ---

    override fun createZipArchive(sourceDirectoryPath: String, destinationZipPath: String) {
        // No-op
    }

    override fun openFolderInExplorer(path: String) {
        // No-op
    }

    override fun selectDirectoryPath(): String? {
        return "/fake/selected/directory" // Return a predictable path
    }

    // --- System Utilities ---

    override fun getSystemTimeMillis(): Long {
        return currentTime
    }

    override fun formatIsoTimestamp(timestamp: Long): String {
        return "ISO_TIMESTAMP_$timestamp"
    }

    override fun formatDisplayTimestamp(timestamp: Long): String {
        return "DISPLAY_TIMESTAMP_$timestamp"
    }

    override fun copyToClipboard(text: String) {
        clipboardContent = text
    }

    override fun applyNativeWindowDecorations(window: Any) {
        // No-op
    }
}