package app.auf

/**
 * A simple, platform-agnostic description of a file system entry.
 */
data class FileEntry(val path: String, val isDirectory: Boolean)

/**
 * Defines a platform-agnostic contract for ALL platform-specific functionalities.
 * This is the single, authoritative bridge between the shared business logic
 * and the host operating system.
 *
 * @version 2.0
 * @since 2025-08-15
 */
expect class PlatformDependencies {
    /** A platform-specific character used to separate path components (e.g., '/' or '\'). */
    val pathSeparator: Char

    // --- File & Directory I/O ---
    fun readFileContent(path: String): String
    fun writeFileContent(path: String, content: String)
    fun fileExists(path: String): Boolean
    fun listDirectory(path: String): List<FileEntry>
    fun createDirectories(path: String)
    fun copyFile(sourcePath: String, destinationPath: String)
    fun deleteFile(path: String)
    fun getBasePathFor(type: String): String
    fun getFileName(path: String): String
    fun getParentDirectory(path: String): String?
    fun getLastModified(path: String): Long

    // --- Complex Operations ---
    fun createZipArchive(sourceDirectoryPath: String, destinationZipPath: String)
    fun openFolderInExplorer(path: String)

    // --- System Utilities ---
    fun getSystemTimeMillis(): Long
    fun formatIsoTimestamp(timestamp: Long): String
}