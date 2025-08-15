// FILE: composeApp/src/commonMain/kotlin/app/auf/SettingsManager.kt

package app.auf

/**
 * Defines the contract for a service that handles loading and saving user preferences.
 *
 * ---
 * ## Mandate
 * This expect class defines a platform-agnostic contract for persisting the
 * UserSettings object. It provides a stable API for the rest of the shared
 * application logic, while delegating the platform-specific file I/O
 * implementation to the `actual` class on each target platform.
 *
 * ---
 * ## Dependencies
 * - `app.auf.UserSettings`
 *
 * @version 1.0
 * @since 2025-08-15
 */
expect class SettingsManager(platform: PlatformDependencies) {
    /**
     * Saves the provided UserSettings object to a persistent location.
     */
    fun saveSettings(settings: UserSettings)

    /**
     * Loads UserSettings from a persistent location.
     * Returns null if no settings are found, allowing the app to use defaults.
     */
    fun loadSettings(): UserSettings?
}