package app.auf

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.auf.core.*
import app.auf.feature.hkgagent.HkgAgentFeature
import app.auf.feature.knowledgegraph.KnowledgeGraphFeature
import app.auf.feature.knowledgegraph.KnowledgeGraphState
import app.auf.feature.session.SessionFeature
import app.auf.feature.systemclock.SystemClockFeature
import app.auf.feature.systemclock.SystemClockState
import app.auf.model.UserSettings
import app.auf.service.*
import app.auf.ui.App
import app.auf.util.JsonProvider
import app.auf.util.PlatformDependencies
import java.io.File
import java.util.Properties

fun main() = application {
    val coroutineScope = rememberCoroutineScope()

    // --- Core Dependencies (Unchanged) ---
    val platformDependencies = remember { PlatformDependencies() }
    val jsonParser = remember { JsonProvider.appJson }
    val aufTextParser = remember { AufTextParser() }
    val settingsManager = remember { SettingsManager(platformDependencies, jsonParser) }
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
    val promptCompiler = remember { PromptCompiler(jsonParser) }
    val gateway = remember { Gateway(jsonParser) }
    val gatewayService = remember { GatewayService(gateway, aufTextParser, apiKey, coroutineScope) }


    // --- MODIFICATION START: Instantiate all features, including the new HkgAgentFeature ---
    val features = remember {
        listOf(
            SystemClockFeature(coroutineScope),
            KnowledgeGraphFeature(platformDependencies, coroutineScope),
            SessionFeature(platformDependencies, jsonParser, coroutineScope),
            HkgAgentFeature(gatewayService, promptCompiler, platformDependencies, jsonParser, coroutineScope)
        )
    }
    // --- MODIFICATION END ---


    val initialState = remember(savedSettings) {
        AppState(
            selectedModel = savedSettings.selectedModel,
            compilerSettings = savedSettings.compilerSettings,
            featureStates = mapOf(
                "SystemClockFeature" to savedSettings.systemClockState,
                "KnowledgeGraphFeature" to KnowledgeGraphState(
                    // Note: These KG-specific settings are now deprecated, as the agent manages its own persona.
                    // This can be cleaned up in a future refactor.
                    aiPersonaId = savedSettings.selectedAiPersonaId,
                    contextualHolonIds = savedSettings.activeContextualHolonIds
                )
            )
        )
    }

    // --- MODIFICATION: The SessionManager is no longer passed to the Store ---
    val store = remember { Store(initialState, ::appReducer, features, coroutineScope) }

    // --- DEPRECATED: ChatService is no longer needed. ---
    // val chatService = remember { ... }

    val stateManager = remember {
        val backupManager = BackupManager(platformDependencies)
        val sourceCodeService = SourceCodeService(platformDependencies)

        StateManager(
            store = store,
            backupManager = backupManager,
            sourceCodeService = sourceCodeService,
            // --- MODIFICATION: Remove deprecated service dependencies ---
            // chatService = chatService,
            gatewayService = gatewayService,
            parser = aufTextParser,
            settingsManager = settingsManager,
            platform = platformDependencies,
            coroutineScope = coroutineScope
        )
    }

    remember {
        stateManager.initialize()
        store.startFeatureLifecycles()
    }

    val windowState = rememberWindowState(width = savedSettings.windowWidth.dp, height = savedSettings.windowHeight.dp)

    Window(
        onCloseRequest = {
            val currentState = stateManager.state.value
            val kgState = currentState.featureStates["KnowledgeGraphFeature"] as? KnowledgeGraphState ?: KnowledgeGraphState()
            val clockState = currentState.featureStates["SystemClockFeature"] as? SystemClockState ?: SystemClockState()

            // --- MODIFICATION: Simplify settings to save. Session state is now autonomous. ---
            val currentSettingsToSave = UserSettings(
                windowWidth = windowState.size.width.value.toInt(),
                windowHeight = windowState.size.height.value.toInt(),
                selectedModel = currentState.selectedModel,
                // These are now effectively deprecated but kept for schema compatibility.
                selectedAiPersonaId = kgState.aiPersonaId,
                activeContextualHolonIds = kgState.contextualHolonIds,
                compilerSettings = currentState.compilerSettings,
                systemClockState = clockState
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
        // --- MODIFICATION: Pass the features list to the App composable ---
        App(stateManager, features)
    }
}