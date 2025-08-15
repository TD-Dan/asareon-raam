package app.auf

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.io.File
import java.util.Properties

/**
 * The main entry point for the AUF application on the JVM platform.
 *
 * ---
 * ## Mandate
 * This file is responsible for setting up the application window, instantiating all
 * platform-specific dependencies (like SettingsManager and PlatformDependencies),
 * and wiring them together into the core StateManager. It also handles the graceful
 * shutdown of the application, ensuring that the final user settings are saved.
 *
 * ---
 * ## Dependencies
 * - `app.auf.StateManager`: The core state management class.
 * - `app.auf.SettingsManager`: Handles loading/saving of user settings.
 * - `app.auf.PlatformDependencies`: Provides platform-specific implementations.
 * - `java.io.File`: For locating the settings directory and local.properties.
 *
 * @version 1.2
 * @since 2025-0.8-15
 */
fun main() = application {

    val platform = PlatformDependencies()

    val settingsManager = SettingsManager(platform)
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

    val coroutineScope = rememberCoroutineScope()

    // Core application state setup
    val stateManager = remember {
        // --- All dependencies are instantiated here ---
        val gateway = Gateway(JsonProvider.appJson)
        val gatewayManager = GatewayManager(gateway, JsonProvider.appJson, apiKey)
        val backupManager = BackupManager(platform)
        val graphLoader = GraphLoader("holons", JsonProvider.appJson)
        val actionExecutor = ActionExecutor(JsonProvider.appJson)
        val importExportManager = ImportExportManager("framework", JsonProvider.appJson)
        val importExportViewModel = ImportExportViewModel(importExportManager, coroutineScope)


        StateManager(
            gatewayManager = gatewayManager,
            backupManager = backupManager,
            graphLoader = graphLoader,
            actionExecutor = actionExecutor,
            importExportViewModel = importExportViewModel,
            platform,
            initialSettings = savedSettings,
            coroutineScope = coroutineScope
        )
    }

    // Wire the callback after instantiation
    stateManager.importExportViewModel.onImportComplete = {
        stateManager.loadHolonGraph()
        stateManager.setViewMode(ViewMode.CHAT)
    }

    // Trigger initial loading
    stateManager.initialize()

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
                activeContextualHolonIds = currentState.contextualHolonIds,
                lastUsedExportPath = currentState.lastUsedExportPath,
                lastUsedImportPath = currentState.lastUsedImportPath
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