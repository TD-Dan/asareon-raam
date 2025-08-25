package app.auf.service

import app.auf.model.SettingDefinition
import app.auf.util.BasePath
import app.auf.util.PlatformDependencies
import app.auf.model.UserSettings
import kotlinx.serialization.json.Json

/**
 * ---
 * ## Mandate
 * Manages the loading and saving of user preferences and provides a central registry
 * for discovering all available application settings. It handles serialization and
 * delegates all file system I/O to the injected `PlatformDependencies` instance.
 *
 * ---
 * ## Dependencies
 * - `app.auf.util.PlatformDependencies`: The contract for all platform-specific I/O.
 * - `kotlinx.serialization.json.Json`: For serializing the UserSettings object.
 *
 * @version 2.1
 * @since 2025-08-25
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

    /**
     * Collects all setting definitions from across the application.
     * This is the single point of contact for the UI to discover what settings are available.
     */
    fun getSettingDefinitions(): List<SettingDefinition> {
        // As new services with settings are created, they are added here.
        return PromptCompiler.SETTING_DEFINITIONS
    }
}