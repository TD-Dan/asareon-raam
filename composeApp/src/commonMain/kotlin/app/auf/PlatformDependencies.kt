package app.auf

object DefaultPaths {
    const val SETTINGS_FILE = "user_settings.json"
    const val HOLONS_DIR    = "holons"
    const val BACKUPS_DIR   = "backups"
}


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

    val user_home: String
    val data_dir: String

    /** Absolute path to the folder where we should store user settings. */
    fun settingsDirPath(): String

    /** Absolute path to the folder where Holon files live. */
    fun holonsDirPath(): String

    /** Absolute path to the folder where backups go. */
    fun backupsDirPath(): String

    /**
     * Reads the entire content of a file as a string.
     */
    fun readFileContent(filePath: String): String

    /**
     * Retrieves the current time in milliseconds.
     *
     * The method returns the number of milliseconds passed since the Unix epoch
     * (January 1, 1970 00:00:00 UTC).
     *
     * @return The current time in milliseconds as a `Long`.
     */
    fun getTimeMilliseconds(): Long

    /**
     * Format timeMilliseconds to an ISO 8601 standard format of YYYYMMDDTHHMMSSZ
     */
    fun formatIsoTimestamp(timeMilliseconds : Long): String

    /**
     * Shows a native folder picker dialog to the user.
     * Returns the selected folder's absolute path, or null if canceled.
     */
    fun showFolderPicker(): String?
}