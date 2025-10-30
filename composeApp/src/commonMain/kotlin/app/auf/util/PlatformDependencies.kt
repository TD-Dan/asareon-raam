package app.auf.util

import kotlinx.serialization.Serializable

/**
 * A simple, platform-agnostic description of a file system entry.
 * CORRECTED: This class is now serializable to be used in FeatureState.
 */
@Serializable
data class FileEntry(val path: String, val isDirectory: Boolean)

/**
 * A type-safe enumeration of the fundamental security zones for file system access.
 * This is the implementation of the "Principle of Minimal Platform Knowledge". The platform
 * layer has no awareness of specific features like "settings" or "logs".
 */
enum class BasePath {
    /** The root directory for all internal, application-managed data (e.g., ~/.auf/v2/). */
    APP_ZONE,
    /** The user's home directory, serving as the default root for user-initiated file operations. */
    USER_ZONE
}

/**
 * Defines the severity level for a log message.
 */
enum class LogLevel { DEBUG, INFO, WARN, ERROR, FATAL }

/**
 * Defines a platform-agnostic contract for ALL platform-specific functionalities.
 * This class and its members are marked 'open' to allow for test fakes to inherit from it.
 */
expect open class PlatformDependencies(appVersion: String) {
    open val pathSeparator: Char

    // --- File & Directory I/O ---
    open fun readFileContent(path: String): String
    open fun writeFileContent(path: String, content: String)
    open fun fileExists(path: String): Boolean
    open fun listDirectory(path: String): List<FileEntry>
    open fun listDirectoryRecursive(path: String): List<FileEntry>
    open fun createDirectories(path: String)
    open fun copyFile(sourcePath: String, destinationPath: String)
    open fun deleteFile(path: String)
    open fun deleteDirectory(path: String) // NEW
    open fun getBasePathFor(type: BasePath): String
    open fun getFileName(path: String): String
    open fun getParentDirectory(path: String): String?
    open fun getLastModified(path: String): Long
    open fun getUserHomePath(): String

    // --- Complex Operations ---
    open fun createZipArchive(sourceDirectoryPath: String, destinationZipPath: String)
    open fun openFolderInExplorer(path: String)
    open fun selectDirectoryPath(): String?

    // --- System Utilities ---
    open fun getSystemTimeMillis(): Long
    open fun formatIsoTimestamp(timestamp: Long): String
    open fun formatDisplayTimestamp(timestamp: Long): String
    open fun copyToClipboard(text: String)
    /**
     * Applies any available native window decorations, such as enabling a dark
     * mode title bar on Windows. This function is a no-op on platforms where
     * such decorations are not available or not implemented.
     *
     * @param window The platform-specific window object (e.g., java.awt.Window).
     */
    open fun applyNativeWindowDecorations(window: Any)
    open fun generateUUID(): String

    // --- Logging ---
    /**
     * Logs a message to the platform's standard output or logging system.
     *
     * @param level The severity level of the message.
     * @param tag A short string identifying the source of the message (e.g., the class name).
     * @param message The content of the log message.
     */
    open fun log(level: LogLevel, tag: String, message: String)
}