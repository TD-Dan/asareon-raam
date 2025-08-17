package app.auf

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.auf.core.AppState
import app.auf.core.Store
import app.auf.core.ViewMode
import app.auf.core.appReducer
import app.auf.model.UserSettings
import app.auf.service.ActionExecutor
import app.auf.service.BackupManager
import app.auf.service.ChatService
import app.auf.service.Gateway
import app.auf.service.GatewayManager
import app.auf.service.GraphLoader
import app.auf.service.GraphService
import app.auf.service.ImportExportManager
import app.auf.service.SettingsManager
import app.auf.service.SourceCodeService
import app.auf.ui.App
import app.auf.ui.ImportExportViewModel
import app.auf.util.JsonProvider
import app.auf.util.PlatformDependencies
import java.io.File
import java.util.Properties

fun main() = application {
    val coroutineScope = rememberCoroutineScope()

    // --- Dependency Injection Root ---
    // All dependencies are instantiated once, here at the top level.
    val platformDependencies = remember { PlatformDependencies() } // The one and only platform-specific instance
    val jsonParser = remember { JsonProvider.appJson }

    val settingsManager = remember { SettingsManager(platformDependencies, jsonParser) }
    val savedSettings = remember { settingsManager.loadSettings() ?: UserSettings() }

    val properties = remember { Properties() }
    val apiKey = remember {
        val localPropertiesFile = File("local.properties")
        if (localPropertiesFile.exists()) {
            properties.load(localPropertiesFile.inputStream())
            properties.getProperty("google.api.key", "")
        } else {
            ""
        }
    }

    if (apiKey.isBlank()) {
        println("WARNING: google.api.key not found in local.properties. AI will not function.")
    }

    // --- UDF Components ---
    val store = remember {
        Store(
            initialState = AppState(),
            reducer = ::appReducer,
            coroutineScope = coroutineScope
        )
    }

    val stateManager = remember {
        val gateway = Gateway(jsonParser)
        val gatewayManager = GatewayManager(gateway, jsonParser, apiKey)
        val backupManager = BackupManager(platformDependencies)
        val actionExecutor = ActionExecutor(platformDependencies, jsonParser)
        val importExportManager = ImportExportManager(platformDependencies, jsonParser)
        val importExportViewModel = ImportExportViewModel(importExportManager, coroutineScope)

        // --- Instantiate Services ---
        val graphLoader = GraphLoader(platformDependencies, jsonParser)
        val graphService = GraphService(graphLoader)
        val sourceCodeService = SourceCodeService(platformDependencies)
        // --- MODIFIED: Instantiate the new ChatService ---
        val chatService = ChatService(store, gatewayManager, platformDependencies, coroutineScope)


        // --- Instantiate the main StateManager with all its dependencies ---
        StateManager(
            store = store,
            backupManager = backupManager,
            graphService = graphService,
            sourceCodeService = sourceCodeService,
            chatService = chatService, // <<< MODIFIED: Inject ChatService
            actionExecutor = actionExecutor,
            importExportViewModel = importExportViewModel,
            platform = platformDependencies,
            initialSettings = savedSettings,
            coroutineScope = coroutineScope
        )
    }

    // Wire the callback after instantiation
    remember {
        stateManager.importExportViewModel.onImportComplete = {
            stateManager.loadHolonGraph()
            stateManager.setViewMode(ViewMode.CHAT)
        }
    }

    // Trigger initial loading
    remember { stateManager.initialize() }


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