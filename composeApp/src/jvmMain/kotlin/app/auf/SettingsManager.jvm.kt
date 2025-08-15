package app.auf

import kotlinx.serialization.json.Json
import java.io.File

/**
 * The actual JVM implementation of the SettingsManager contract.
 * It uses java.io.File to save settings to a JSON file in the user's home directory.
 *
 * ---
 * ## Mandate
 * This class is responsible for the concrete file system operations required to
 * save and load the UserSettings object on a JVM platform. It creates the necessary
 * directories and handles all potential file I/O exceptions gracefully.
 *
 * ---
 * ## Dependencies
 * - `java.io.File`: For file system access.
 * - `kotlinx.serialization.json.Json`: For serializing/deserializing the settings object.
 *
 * @version 1.0
 * @since 2025-08-15
 */
actual class SettingsManager actual constructor(platform: PlatformDependencies) {

    private val settingsFile: File = File(platform.settingsDirPath(), DefaultPaths.SETTINGS_FILE)
    private val jsonParser = Json { prettyPrint = true }

    actual fun saveSettings(settings: UserSettings) {
        try {
            val jsonString = jsonParser.encodeToString(UserSettings.serializer(), settings)
            settingsFile.writeText(jsonString)
        } catch (e: Exception) {
            println("Error saving settings: ${e.message}")
        }
    }

    actual fun loadSettings(): UserSettings? {
        if (!settingsFile.exists()) return null

        return try {
            val jsonString = settingsFile.readText()
            jsonParser.decodeFromString(UserSettings.serializer(), jsonString)
        } catch (e: Exception) {
            println("Error loading settings file. It might be corrupt. Using defaults. Error: ${e.message}")
            null
        }
    }
}