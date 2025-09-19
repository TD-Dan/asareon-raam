package app.auf

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.auf.core.*
import app.auf.feature.agent.AgentRuntimeFeature
import app.auf.feature.agent.AgentGateway
import app.auf.feature.agent.GatewayGemini
import app.auf.feature.agent.AgentRuntimeFeatureState
import app.auf.feature.agent.PromptCompiler
import app.auf.feature.knowledgegraph.KnowledgeGraphFeature
import app.auf.feature.knowledgegraph.KnowledgeGraphService
import app.auf.feature.knowledgegraph.KnowledgeGraphState
import app.auf.feature.settings.SettingsFeature
import app.auf.feature.session.SessionFeature
import app.auf.feature.session.SessionFeatureState
import app.auf.service.*
import app.auf.ui.App
import app.auf.util.PlatformDependencies
import java.io.File
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
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
                    subclass(AgentRuntimeFeatureState::class)
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

        // The SessionFeature needs a reference to the list, so we add it last.
        // This is a bit of a code smell but acceptable for the composition root.
        allFeatures.addAll(listOf(
            AgentRuntimeFeature(agentGateway, platformDependencies, coroutineScope),
            KnowledgeGraphFeature(knowledgeGraphService, coroutineScope),
            SettingsFeature(allFeatures) // SettingsFeature also needs the list
        ))
        allFeatures.add(SessionFeature(platformDependencies, jsonParser, coroutineScope, allFeatures))
        allFeatures
    }

    val settingsPersistenceService = remember { SettingsPersistenceService(platformDependencies, jsonParser) }
    val savedSettings = remember { settingsPersistenceService.loadSettings() ?: UserSettings() }

    val initialState = remember(savedSettings) {
        AppState(
            featureStates = savedSettings.featureStates
        )
    }

    // --- FIX APPLIED ---
    // The Store constructor no longer takes a CoroutineScope.
    val store = remember { Store(initialState, ::appReducer, features) }

    val stateManager = remember {
        val backupManager = BackupManager(platformDependencies)
        val sourceCodeService = SourceCodeService(platformDependencies)

        StateManager(
            store = store,
            backupManager = backupManager,
            sourceCodeService = sourceCodeService,
            settingsPersistenceService = settingsPersistenceService,
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

    // --- EFFECT: Reactive Auto-Save ---
    LaunchedEffect(store, settingsPersistenceService) {
        store.state
            .drop(1) // Avoid a pointless save on startup
            .debounce(2000L) // The gatekeeper: wait for a 2s pause in state changes
            .collectLatest { state ->
                // Perform the slow I/O on a background thread
                withContext(Dispatchers.IO) {
                    println("Auto-saving settings...")
                    val currentSettingsToSave = UserSettings(
                        windowWidth = windowState.size.width.value.toInt(),
                        windowHeight = windowState.size.height.value.toInt(),
                        featureStates = state.featureStates
                    )
                    settingsPersistenceService.saveSettings(currentSettingsToSave)
                }
            }
    }

    Window(
        onCloseRequest = {
            // This now acts as a final, guaranteed save.
            val currentState = stateManager.state.value
            val currentSettingsToSave = UserSettings(
                windowWidth = windowState.size.width.value.toInt(),
                windowHeight = windowState.size.height.value.toInt(),
                featureStates = currentState.featureStates
            )
            settingsPersistenceService.saveSettings(currentSettingsToSave)
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