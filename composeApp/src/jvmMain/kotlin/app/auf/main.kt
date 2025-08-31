package app.auf

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.auf.core.*
import app.auf.feature.systemclock.SystemClockFeature
import app.auf.model.UserSettings
import app.auf.service.*
import app.auf.ui.App
import app.auf.ui.ImportExportViewModel
import app.auf.util.JsonProvider
import app.auf.util.PlatformDependencies
import java.io.File
import java.util.Properties

fun main() = application {
    val coroutineScope = rememberCoroutineScope()

    val platformDependencies = remember { PlatformDependencies() }
    val jsonParser = remember { JsonProvider.appJson }

    val aufTextParser = remember { AufTextParser() }
    remember { ChatMessage.Factory.initialize(platformDependencies, aufTextParser) }

    val settingsManager = remember { SettingsManager(platformDependencies, jsonParser) }
    val sessionManager = remember { SessionManager(platformDependencies, jsonParser) }
    val savedSettings = remember { settingsManager.loadSettings() ?: UserSettings() }

    val properties = remember { Properties() }
    val apiKey = remember {
        val localPropertiesFile = File("local.properties")
        if (localPropertiesFile.exists()) {
            properties.load(localPropertiesFile.inputStream())
            properties.getProperty("google.api.key", "")
        } else ""
    }
    if (apiKey.isBlank()) {
        println("WARNING: google.api.key not found in local.properties. AI will not function.")
    }

    val features = remember {
        listOf(
            SystemClockFeature(coroutineScope)
            // Future features will be added here
        )
    }


    val initialState = remember(savedSettings) {
        AppState(
            selectedModel = savedSettings.selectedModel,
            aiPersonaId = savedSettings.selectedAiPersonaId,
            contextualHolonIds = savedSettings.activeContextualHolonIds,
            compilerSettings = savedSettings.compilerSettings,
            // --- MODIFICATION START: Load feature state from saved settings ---
            featureStates = mapOf(
                "SystemClockFeature" to savedSettings.systemClockState
            )
            // --- MODIFICATION END ---
        )
    }

    val store = remember { Store(initialState, ::appReducer, features, sessionManager, coroutineScope) }
    val promptCompiler = remember { PromptCompiler(jsonParser) }

    val stateManager = remember {
        val gateway = Gateway(jsonParser)
        val gatewayService = GatewayService(gateway, aufTextParser, apiKey, coroutineScope)
        val backupManager = BackupManager(platformDependencies)
        val actionExecutor = ActionExecutor(platformDependencies, jsonParser)
        val importExportManager = ImportExportManager(platformDependencies, jsonParser)
        val importExportViewModel = ImportExportViewModel(importExportManager, coroutineScope)
        val graphLoader = GraphLoader(platformDependencies, jsonParser)
        val graphService = GraphService(graphLoader)
        val sourceCodeService = SourceCodeService(platformDependencies)
        val chatService = ChatService(store, gatewayService, platformDependencies, aufTextParser, promptCompiler, coroutineScope)

        StateManager(
            store = store,
            backupManager = backupManager,
            graphService = graphService,
            sourceCodeService = sourceCodeService,
            chatService = chatService,
            gatewayService = gatewayService,
            actionExecutor = actionExecutor,
            parser = aufTextParser,
            settingsManager = settingsManager,
            sessionManager = sessionManager,
            importExportViewModel = importExportViewModel,
            platform = platformDependencies,
            coroutineScope = coroutineScope
        )
    }

    remember {
        stateManager.importExportViewModel.onImportComplete = {
            store.dispatch(AppAction.ShowToast("Import successful! Reloading graph..."))
            stateManager.loadHolonGraph()
            stateManager.setViewMode(ViewMode.CHAT)
        }
        stateManager.importExportViewModel.onImportFailed = { errorMessage ->
            store.dispatch(AppAction.ShowToast(errorMessage))
        }
    }

    remember {
        stateManager.initialize()
        store.startFeatureLifecycles()
    }

    val windowState = rememberWindowState(width = savedSettings.windowWidth.dp, height = savedSettings.windowHeight.dp)

    Window(
        onCloseRequest = {
            val currentState = stateManager.state.value
            sessionManager.saveSession(currentState.chatHistory)

            // --- MODIFICATION START: Save feature states on exit ---
            val currentSettingsToSave = UserSettings(
                windowWidth = windowState.size.width.value.toInt(),
                windowHeight = windowState.size.height.value.toInt(),
                selectedModel = currentState.selectedModel,
                selectedAiPersonaId = currentState.aiPersonaId,
                activeContextualHolonIds = currentState.contextualHolonIds,
                compilerSettings = currentState.compilerSettings,
                systemClockState = currentState.featureStates["SystemClockFeature"] as? app.auf.feature.systemclock.SystemClockState ?: app.auf.feature.systemclock.SystemClockState()
            )
            // --- MODIFICATION END ---
            settingsManager.saveSettings(currentSettingsToSave)
            exitApplication()
        },
        title = "AUF v${Version.APP_VERSION}",
        state = windowState
    ) {
        LaunchedEffect(Unit) {
            platformDependencies.applyNativeWindowDecorations(window)
        }
        App(stateManager)
    }
}