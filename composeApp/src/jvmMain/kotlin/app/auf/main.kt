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
import app.auf.model.Parameter
import app.auf.model.ToolDefinition
import app.auf.model.UserSettings
import app.auf.service.ActionExecutor
import app.auf.service.AufTextParser
import app.auf.service.BackupManager
import app.auf.service.ChatService
import app.auf.service.Gateway
import app.auf.service.GatewayService
import app.auf.service.GraphLoader
import app.auf.service.GraphService
import app.auf.service.ImportExportManager
import app.auf.service.PromptCompiler
import app.auf.service.SessionManager
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

    val platformDependencies = remember { PlatformDependencies() }
    val jsonParser = remember { JsonProvider.appJson }

    val toolRegistry = remember {
        listOf(
            ToolDefinition(
                name = "Atomic Change Manifest",
                command = "ACTION_MANIFEST",
                description = "Propose a transactional set of changes to the Holon Knowledge Graph file system.",
                parameters = emptyList(),
                expectsPayload = true,
                usage = "[AUF_ACTION_MANIFEST]\n[...json array of Action objects...]\n[/AUF_ACTION_MANIFEST]"
            ),
            ToolDefinition(
                name = "Application Request",
                command = "APP_REQUEST",
                description = "Request the host application to perform a pre-defined, non-file-system action.",
                parameters = emptyList(),
                expectsPayload = true,
                usage = "[AUF_APP_REQUEST]START_DREAM_CYCLE[/AUF_APP_REQUEST]"
            ),
            ToolDefinition(
                name = "File Content View",
                command = "FILE_VIEW",
                description = "Display the content of a non-Holon file within the chat.",
                parameters = listOf(
                    Parameter(name = "path", type = "String", isRequired = true, defaultValue = null),
                    Parameter(name = "language", type = "String", isRequired = false, defaultValue = null)
                ),
                expectsPayload = true,
                usage = "[AUF_FILE_VIEW(path=\"path/to/your/file.kt\")]\n...file content...\n[/AUF_FILE_VIEW]"
            ),
            ToolDefinition(
                name = "State Anchor",
                command = "STATE_ANCHOR",
                description = "Create a persistent, context-immune memory waypoint within the chat history.",
                parameters = emptyList(),
                expectsPayload = true,
                usage = "[AUF_STATE_ANCHOR]\n{\"anchorId\": \"...\", ...}\n[/AUF_STATE_ANCHOR]"
            )
        )
    }

    val aufTextParser = remember { AufTextParser(jsonParser, toolRegistry) }
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
            // --- MODIFICATION START: Initialize feature states from our new feature list ---
            featureStates = features.associate { it.name to (when(it) {
                is SystemClockFeature -> app.auf.feature.systemclock.SystemClockState()
                else -> Any()
            }) }
        )
    }

    val store = remember { Store(initialState, ::appReducer, features, sessionManager, coroutineScope) }
    val promptCompiler = remember { PromptCompiler(jsonParser) }

    val stateManager = remember {
        val gateway = Gateway(jsonParser)
        val gatewayService = GatewayService(gateway, aufTextParser, toolRegistry, apiKey, coroutineScope)
        val backupManager = BackupManager(platformDependencies)
        val actionExecutor = ActionExecutor(platformDependencies, jsonParser)
        val importExportManager = ImportExportManager(platformDependencies, jsonParser)
        val importExportViewModel = ImportExportViewModel(importExportManager, coroutineScope)
        val graphLoader = GraphLoader(platformDependencies, jsonParser)
        val graphService = GraphService(graphLoader)
        val sourceCodeService = SourceCodeService(platformDependencies)
        val chatService = ChatService(store, gatewayService, platformDependencies, aufTextParser, toolRegistry, promptCompiler, coroutineScope)

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

            val currentSettingsToSave = UserSettings(
                windowWidth = windowState.size.width.value.toInt(),
                windowHeight = windowState.size.height.value.toInt(),
                selectedModel = currentState.selectedModel,
                selectedAiPersonaId = currentState.aiPersonaId,
                activeContextualHolonIds = currentState.contextualHolonIds,
                compilerSettings = currentState.compilerSettings
            )
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