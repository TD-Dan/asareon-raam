// FILE: composeApp/src/commonMain/kotlin/app/auf/PlatformDependencies.kt

package app.auf

/**
 * Defines a contract for platform-specific functionalities that the common code needs.
 *
 * ---
 * ## Mandate
 * This expect class defines a platform-agnostic API for functionalities that have
 * different implementations on each target platform (e.g., file I/O, showing a
 * native folder picker). This allows the common business logic to remain clean
 * and decoupled from any specific platform's libraries.
 *
 * ---
 * ## Dependencies
 * - None
 *
 * @version 1.1
 * @since 2025-08-15
 */
expect class PlatformDependencies {
    /**
     * Reads the entire content of a file as a string.
     */
    fun readFileContent(filePath: String): String

    /**
     * Formats a Unix timestamp (Long) into an ISO 8601 string.
     */
    fun formatIsoTimestamp(timestamp: Long): String

    /**
     * Shows a native folder picker dialog to the user.
     * Returns the selected folder's absolute path, or null if canceled.
     */
    fun showFolderPicker(): String?
}