package app.auf

/**
 * A simple, platform-agnostic description of a file system entry.
 */
data class FileEntry(val path: String, val isDirectory: Boolean)

/**
 * A type-safe enumeration of all canonical base paths managed by the AUF application.
 * This is used to prevent "magic string" errors when requesting paths from PlatformDependencies.
 */
enum class BasePath {
    SETTINGS,
    BACKUPS,
    HOLONS,
    FRAMEWORK
}

/**
 * Defines a platform-agnostic contract for ALL platform-specific functionalities.
 * This class and its members are marked 'open' to allow for test fakes to inherit from it.
 *
 * @version 2.1
 * @since 2025-08-15
 */
expect open class PlatformDependencies() {
    open val pathSeparator: Char

    // --- File & Directory I/O ---
    open fun readFileContent(path: String): String
    open fun writeFileContent(path: String, content: String)
    open fun fileExists(path: String): Boolean
    open fun listDirectory(path: String): List<FileEntry>
    open fun createDirectories(path: String)
    open fun copyFile(sourcePath: String, destinationPath: String)
    open fun deleteFile(path: String)
    open fun getBasePathFor(type: BasePath): String
    open fun getFileName(path: String): String
    open fun getParentDirectory(path: String): String?
    open fun getLastModified(path: String): Long

    // --- Complex Operations ---
    open fun createZipArchive(sourceDirectoryPath: String, destinationZipPath: String)
    open fun openFolderInExplorer(path: String)

    // --- System Utilities ---
    open fun getSystemTimeMillis(): Long
    open fun formatIsoTimestamp(timestamp: Long): String
}