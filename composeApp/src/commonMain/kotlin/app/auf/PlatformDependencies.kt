package app.auf

/**
 * Defines a platform-agnostic contract for platform-specific functionalities
 * that the shared business logic (like StateManager) needs.
 */
expect class PlatformDependencies {
    /**
     * Reads the entire content of a file as a string.
     * The path is relative to the application's root.
     */
    fun readFileContent(filePath: String): String

    /**
     * Formats a millisecond timestamp into a standard ISO 8601 string (UTC).
     */
    fun formatIsoTimestamp(timestamp: Long): String
}