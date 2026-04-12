package asareon.raam.util

import kotlinx.serialization.Serializable

/**
 * A simple, platform-agnostic description of a file system entry.
 * CORRECTED: This class is now serializable to be used in FeatureState.
 */
@Serializable
data class FileEntry(
    val path: String,
    val isDirectory: Boolean,
    val lastModified: Long? = null  // epoch millis — null preserves backward compat
)

/**
 * Represents a file received via drag-and-drop from outside the application.
 * Used by platform-specific drop target composables to pass dropped files
 * back to the shared feature layer for persistence via FileSystemFeature.
 */
data class DroppedFile(
    /** The original file name (e.g., "notes.md"). */
    val name: String,
    /** The raw file content as bytes. For text files, decode with UTF-8. */
    val bytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DroppedFile) return false
        return name == other.name && bytes.contentEquals(other.bytes)
    }
    override fun hashCode(): Int = 31 * name.hashCode() + bytes.contentHashCode()
}

/**
 * A type-safe enumeration of the fundamental security zones for file system access.
 * This is the implementation of the "Principle of Minimal Platform Knowledge". The platform
 * layer has no awareness of specific features like "settings" or "logs".
 */
enum class BasePath {
    /** The root directory for all internal, application-managed data (e.g., ~/.raam/v2/). */
    APP_ZONE,
    /** The user's home directory, serving as the default root for user-initiated file operations. */
    USER_ZONE
}

/**
 * Defines the severity level for a log message.
 */
enum class LogLevel { DEBUG, INFO, WARN, ERROR, FATAL }

/**
 * A single buffered log entry for retrieval by scripts and diagnostic tools.
 */
data class LogBufferEntry(
    val level: LogLevel,
    val tag: String,
    val message: String,
    val timestamp: Long
)

/**
 * Defines a platform-agnostic contract for ALL platform-specific functionalities.
 * This class and its members are marked 'open' to allow for test fakes to inherit from it.
 */
expect open class PlatformDependencies(appVersion: String) {
    open val pathSeparator: Char

    // --- File & Directory I/O ---
    open fun readFileContent(path: String): String
    open fun writeFileContent(path: String, content: String)

    /**
     * Reads a file as raw bytes. Used by drag-and-drop to export files
     * from the sandbox to the OS drag layer without text encoding assumptions.
     */
    open fun readFileBytes(path: String): ByteArray

    /**
     * Writes raw bytes to a file, creating parent directories if needed.
     * Used by drag-and-drop to persist files dropped from outside the app.
     */
    open fun writeFileBytes(path: String, bytes: ByteArray)

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

    /**
     * Resolves a relative sandbox path to an absolute filesystem path.
     * Used by drag-out to provide the OS drag layer with a real file path.
     *
     * @param featureHandle The feature's handle (e.g., "session") — used to locate the sandbox root.
     * @param relativePath The path relative to the feature's sandbox (e.g., "{uuid}/workspace/file.txt").
     * @return The absolute filesystem path.
     */
    open fun resolveAbsoluteSandboxPath(featureHandle: String, relativePath: String): String

    // --- Complex Operations ---
    open fun openFolderInExplorer(path: String)
    open fun selectDirectoryPath(): String?

    // --- Backup/Restore Support ---
    /**
     * Creates a zip archive from a source directory, optionally excluding a named
     * subdirectory (matched by name, not path).
     *
     * @param sourceDirectoryPath The directory to archive.
     * @param destinationZipPath The output zip file path.
     * @param excludeDirectoryName Optional directory name to exclude (e.g., "_backups").
     *        When non-null, any directory with this exact name at any depth is skipped.
     * @param onProgress Optional callback invoked after each entry is written.
     *        Receives (bytesProcessed, totalBytes). totalBytes is estimated from source
     *        directory size — compression means the zip will be smaller, but the ratio
     *        is useful for progress display.
     */
    open fun createZipArchive(
        sourceDirectoryPath: String,
        destinationZipPath: String,
        excludeDirectoryName: String,
        onProgress: ((bytesProcessed: Long, totalBytes: Long) -> Unit)? = null
    )

    /**
     * Extracts a zip archive to a target directory, creating it if needed.
     *
     * @param zipPath Path to the zip file to extract.
     * @param targetDirectoryPath The directory to extract into.
     * @param onProgress Optional callback invoked after each entry is extracted.
     *        Receives (bytesProcessed, totalBytes) where totalBytes is the sum of
     *        uncompressed entry sizes read from the zip central directory.
     */
    open fun extractZipArchive(
        zipPath: String,
        targetDirectoryPath: String,
        onProgress: ((bytesProcessed: Long, totalBytes: Long) -> Unit)? = null
    )

    /**
     * Returns the size of a file in bytes.
     *
     * @param path The absolute path to the file.
     * @return The file size in bytes, or 0 if the file does not exist.
     */
    open fun fileSize(path: String): Long

    /**
     * Requests the platform to restart the application.
     * The implementation is platform-specific:
     * - JVM: Launches a new process and exits the current one.
     * - Other platforms: May show a "please restart" message instead.
     */
    open fun restartApplication()

    // --- System Utilities ---
    open fun currentTimeMillis(): Long
    open fun formatIsoTimestamp(timestamp: Long): String
    /**
     * Parses an ISO 8601 timestamp string into epoch milliseconds.
     * Returns null if the string cannot be parsed.
     *
     * @param timestamp An ISO 8601 formatted string (e.g., "2025-02-07T18:40:00Z").
     * @return The epoch milliseconds, or null if parsing fails.
     */
    open fun parseIsoTimestamp(timestamp: String): Long?
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

    // --- Scheduling ---
    /**
     * Schedules a callback to be invoked after a delay.
     * Returns a platform-specific handle that can be passed to [cancelScheduled].
     *
     * @param delayMs The delay in milliseconds before the callback fires.
     * @param callback The function to invoke after the delay.
     * @return A cancellation handle, or null if scheduling is not supported.
     */
    open fun scheduleDelayed(delayMs: Long, callback: () -> Unit): Any?

    /**
     * Cancels a previously scheduled delayed callback.
     * No-op if the handle is null or already cancelled.
     *
     * @param handle The handle returned by [scheduleDelayed].
     */
    open fun cancelScheduled(handle: Any?)

    // --- Logging ---
    /**
     * Logs a message to the platform's standard output or logging system.
     * [REFACTOR] This function is now overloaded to accept an optional throwable,
     * making it the sole authority on exception formatting.
     *
     * @param level The severity level of the message.
     * @param tag A short string identifying the source of the message (e.g., the class name).
     * @param message The content of the log message.
     * @param throwable An optional exception whose stack trace will be automatically appended.
     */
    open fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null)

    /**
     * @deprecated Use [addLogListener]/[removeLogListener] instead.
     * Kept for backward compatibility during migration.
     */
    @Deprecated("Use addLogListener/removeLogListener", ReplaceWith("addLogListener(id, listener)"))
    open var logListener: ((LogLevel, String, String) -> Unit)?

    // --- Log Listener Registry ---

    /**
     * Registers a named log listener. Multiple listeners can coexist.
     * Each listener receives every log entry in real-time.
     *
     * @param id Unique identifier for this listener (e.g., "boot", "lua")
     * @param listener Callback: (level, tag, message, timestamp)
     */
    open fun addLogListener(id: String, listener: (LogLevel, String, String, Long) -> Unit)

    /**
     * Removes a previously registered log listener by ID.
     */
    open fun removeLogListener(id: String)

    /**
     * Returns recent log entries from the in-memory ring buffer.
     *
     * @param limit Maximum entries to return (default 100)
     * @param minLevel Minimum severity level to include (default DEBUG = all)
     * @return Log entries ordered oldest-first, filtered by level
     */
    open fun getRecentLogs(limit: Int = 100, minLevel: LogLevel = LogLevel.DEBUG): List<LogBufferEntry>
}