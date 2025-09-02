package app.auf

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.auf.core.*
import app.auf.feature.hkgagent.GatewayGemini
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

    // --- Core Dependencies ---
    val platformDependencies = remember { PlatformDependencies() }
    val jsonParser = remember { JsonProvider.appJson }
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


    // --- Feature Instantiation ---
    val features = remember {
        // The concrete implementation of the gateway is created here...
        val agentGateway = GatewayGemini(jsonParser, apiKey)

        listOf(
            SystemClockFeature(coroutineScope),
            KnowledgeGraphFeature(platformDependencies, coroutineScope),
            SessionFeature(platformDependencies, jsonParser, coroutineScope),
            // ...and injected into the feature that needs it.
            HkgAgentFeature(agentGateway, promptCompiler, platformDependencies, jsonParser, coroutineScope)
        )
    }

    val initialState = remember(savedSettings) {
        AppState(
            selectedModel = savedSettings.selectedModel,
            compilerSettings = savedSettings.compilerSettings,
            featureStates = mapOf(
                "SystemClockFeature" to savedSettings.systemClockState,
                "KnowledgeGraphFeature" to KnowledgeGraphState(
                    aiPersonaId = savedSettings.selectedAiPersonaId,
                    contextualHolonIds = savedSettings.activeContextualHolonIds
                )
            )
        )
    }

    val store = remember { Store(initialState, ::appReducer, features, coroutineScope) }

    val stateManager = remember {
        val backupManager = BackupManager(platformDependencies)
        val sourceCodeService = SourceCodeService(platformDependencies)

        StateManager(
            store = store,
            backupManager = backupManager,
            sourceCodeService = sourceCodeService,
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

            val currentSettingsToSave = UserSettings(
                windowWidth = windowState.size.width.value.toInt(),
                windowHeight = windowState.size.height.value.toInt(),
                selectedModel = currentState.selectedModel,
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
        App(stateManager, features)
    }
}