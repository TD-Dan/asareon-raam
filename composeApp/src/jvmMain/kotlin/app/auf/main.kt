// FILE: composeApp/src/jvmMain/kotlin/app/auf/main.kt
package app.auf

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.io.File
import java.util.Properties

fun main() = application {
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

    val coroutineScope = rememberCoroutineScope()
    val stateManager = remember {
        // --- All dependencies are instantiated here ---
        val gateway = Gateway(JsonProvider.appJson)
        val gatewayManager = GatewayManager(gateway, JsonProvider.appJson, apiKey)
        val backupManager = BackupManager("holons", File(System.getProperty("user.home"), ".auf"))
        val graphLoader = GraphLoader("holons", JsonProvider.appJson)
        val actionExecutor = ActionExecutor(JsonProvider.appJson)
        val importExportManager = ImportExportManager("framework", JsonProvider.appJson)
        val importExportViewModel = ImportExportViewModel(importExportManager, coroutineScope)
        val platformDependencies = PlatformDependencies() // <-- INSTANTIATE THE NEW DEPENDENCY

        StateManager(
            gatewayManager = gatewayManager,
            backupManager = backupManager,
            graphLoader = graphLoader,
            actionExecutor = actionExecutor,
            importExportViewModel = importExportViewModel,
            platform = platformDependencies, // <-- INJECT THE NEW DEPENDENCY
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