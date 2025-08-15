package app.auf

/**
 * ---
 * ## Mandate
 * Defines a platform-agnostic contract for a service that manages all backup-related
 * operations for the AUF application. This `expect` class establishes the "what"
 * (the API), while platform-specific `actual` classes provide the "how" (the
 * implementation).
 *
 * ---
 * ## Dependencies
 * - None. This is a pure contract.
 *
 * @version 1.1
 * @since 2025-08-15
 */
expect open class BackupManager {
    /**
     * Creates a zip archive of the entire 'holons' directory.
     * This is a non-blocking operation.
     * @param trigger A string to name the backup source (e.g., "on-launch", "pre-export")
     */
    open fun createBackup(trigger: String)

    /**
     * Opens the user's backup folder in the default system file explorer.
     */
    open fun openBackupFolder()
}