package app.auf

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.auf.core.*
import app.auf.feature.hkgagent.AgentGateway
import app.auf.feature.hkgagent.GatewayGemini
import app.auf.feature.hkgagent.HkgAgentFeature
import app.auf.feature.hkgagent.HkgAgentFeatureState
import app.auf.feature.hkgagent.PromptCompiler
import app.auf.feature.knowledgegraph.KnowledgeGraphFeature
import app.auf.feature.knowledgegraph.KnowledgeGraphService
import app.auf.feature.knowledgegraph.KnowledgeGraphState
import app.auf.feature.session.SessionFeature
import app.auf.feature.session.SessionFeatureState
import app.auf.feature.systemclock.SystemClockFeature
import app.auf.feature.systemclock.SystemClockState
import app.auf.model.UserSettings
import app.auf.service.*
import app.auf.ui.App
import app.auf.util.PlatformDependencies
import java.io.File
import java.util.Properties
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass


fun main() = application {
    val coroutineScope = rememberCoroutineScope()

    val platformDependencies = remember { PlatformDependencies() }
    val jsonParser = remember {
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            serializersModule = SerializersModule {
                polymorphic(FeatureState::class) {
                    subclass(SystemClockState::class)
                    subclass(HkgAgentFeatureState::class)
                    subclass(KnowledgeGraphState::class)
                    subclass(SessionFeatureState::class)
                }
            }
        }
    }

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

    val features = remember {
        val agentGateway: AgentGateway = GatewayGemini(jsonParser, apiKey)
        val knowledgeGraphService = KnowledgeGraphService(platformDependencies)
        val allFeatures = mutableListOf<Feature>()

        allFeatures.addAll(listOf(
            SystemClockFeature(coroutineScope),
            KnowledgeGraphFeature(knowledgeGraphService, coroutineScope),
            HkgAgentFeature(agentGateway, promptCompiler, platformDependencies, jsonParser, coroutineScope),
            SessionFeature(platformDependencies, jsonParser, coroutineScope, allFeatures)
        ))
        allFeatures
    }

    val settingsManager = remember { SettingsManager(platformDependencies, jsonParser, features) }
    val savedSettings = remember { settingsManager.loadSettings() ?: UserSettings() }

    val initialState = remember(savedSettings) {
        AppState(
            featureStates = savedSettings.featureStates
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
            coroutineScope = coroutineScope,
            features = features
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
            val currentSettingsToSave = UserSettings(
                windowWidth = windowState.size.width.value.toInt(),
                windowHeight = windowState.size.height.value.toInt(),
                featureStates = currentState.featureStates
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