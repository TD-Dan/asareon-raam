package app.auf

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.io.File
import java.util.Properties

fun main() = application {
    // --- MODIFIED: Proper settings management ---
    val settingsDir = File(System.getProperty("user.home"), ".auf")
    val settingsManager = SettingsManager(settingsDir)
    val savedSettings = settingsManager.loadSettings() ?: UserSettings()

    val properties = Properties()
    val localPropertiesFile = File("local.properties")
    val apiKey = if (localPropertiesFile.exists()) {
        properties.load(localPropertiesFile.inputStream())
        properties.getProperty("google.api.key", "")
    } else { "" }

    if (apiKey.isBlank()) {
        println("WARNING: google.api.key not found in local.properties. AI will not function.")
    }

    val stateManager = StateManager(apiKey, savedSettings)

    val windowState = rememberWindowState(
        width = savedSettings.windowWidth.dp,
        height = savedSettings.windowHeight.dp
    )

    Window(
        onCloseRequest = {
            val currentState = stateManager.state.value
            val currentSettingsToSave = UserSettings(
                windowWidth = windowState.size.width.value.toInt(),
                windowHeight = windowState.size.height.value.toInt(),
                selectedModel = currentState.selectedModel,
                selectedAiPersonaId = currentState.aiPersonaId,
                activeContextualHolonIds = currentState.contextualHolonIds
            )
            settingsManager.saveSettings(currentSettingsToSave)
            exitApplication()
        },
        title = "AUF",
        state = windowState
    ) {
        App(stateManager)
    }
}