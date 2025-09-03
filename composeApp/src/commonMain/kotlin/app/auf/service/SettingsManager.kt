package app.auf.service

import app.auf.core.Feature
import app.auf.model.SettingDefinition
import app.auf.util.BasePath
import app.auf.util.PlatformDependencies
import app.auf.model.UserSettings
import kotlinx.serialization.json.Json

class SettingsManager(
    private val platform: PlatformDependencies,
    private val jsonParser: Json,
    private val features: List<Feature>
) {
    private val settingsFilePath: String

    init {
        val settingsDir = platform.getBasePathFor(BasePath.SETTINGS)
        platform.createDirectories(settingsDir)
        settingsFilePath = settingsDir + platform.pathSeparator + "user_settings.json"
    }

    fun saveSettings(settings: UserSettings) {
        try {
            val jsonString = jsonParser.encodeToString(UserSettings.serializer(), settings)
            platform.writeFileContent(settingsFilePath, jsonString)
        } catch (e: Exception) {
            println("Error saving settings: ${e.message}")
        }
    }

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

    // --- CORRECTED ---
    // This method is no longer used by the new SettingsView, but is fixed here for correctness.
    // It now correctly looks inside the composableProvider.
    fun getSettingDefinitions(): List<SettingDefinition> {
        return features.flatMap { it.composableProvider?.settingDefinitions ?: emptyList() }
    }
}