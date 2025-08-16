package app.auf.service

import app.auf.util.BasePath
import app.auf.util.PlatformDependencies
import app.auf.model.UserSettings
import kotlinx.serialization.json.Json

/**
 * ---
 * ## Mandate
 * Manages the loading and saving of user preferences to a plain text JSON file.
 * This class handles no sensitive data. It contains only business logic for serialization
 * and delegates all file system I/O to the injected `PlatformDependencies` instance.
 *
 * ---
 * ## Dependencies
 * - `app.auf.util.PlatformDependencies`: The contract for all platform-specific I/O.
 * - `kotlinx.serialization.json.Json`: For serializing the UserSettings object.
 *
 * @version 2.0
 * @since 2025-08-15
 */
class SettingsManager(
    private val platform: PlatformDependencies,
    private val jsonParser: Json
) {

    private val settingsFilePath: String

    init {
        val settingsDir = platform.getBasePathFor(BasePath.SETTINGS)
        platform.createDirectories(settingsDir)
        settingsFilePath = settingsDir + platform.pathSeparator + "user_settings.json"
    }

    /**
     * Saves the provided UserSettings object to user_settings.json.
     */
    fun saveSettings(settings: UserSettings) {
        try {
            val jsonString = jsonParser.encodeToString(UserSettings.serializer(), settings)
            platform.writeFileContent(settingsFilePath, jsonString)
        } catch (e: Exception) {
            println("Error saving settings: ${e.message}")
        }
    }

    /**
     * Loads UserSettings from user_settings.json.
     * Returns null if the file doesn't exist or is corrupt, allowing the app to use defaults.
     */
    fun loadSettings(): UserSettings? {
        if (!platform.fileExists(settingsFilePath)) return null

        return try {
            val jsonString = platform.readFileContent(settingsFilePath)
            jsonParser.decodeFromString(UserSettings.serializer(), jsonString)
        } catch (e: Exception) {
            println("Error loading settings file. It might be corrupt. Using defaults. Error: ${e.message}")
            null
        }
    }
}