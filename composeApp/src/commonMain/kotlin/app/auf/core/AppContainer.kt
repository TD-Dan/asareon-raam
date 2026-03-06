package app.auf.core

import app.auf.feature.agent.AgentRuntimeFeature
import app.auf.feature.commandbot.CommandBotFeature
import app.auf.feature.core.CoreFeature
import app.auf.feature.filesystem.FileSystemFeature
import app.auf.feature.gateway.GatewayFeature
import app.auf.feature.gateway.gemini.GeminiProvider
import app.auf.feature.gateway.openai.OpenAIProvider
import app.auf.feature.gateway.anthropic.AnthropicProvider
import app.auf.feature.gateway.inception.InceptionProvider
import app.auf.feature.knowledgegraph.KnowledgeGraphFeature
import app.auf.feature.settings.SettingsFeature
import app.auf.feature.session.SessionFeature
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * ## Mandate
 * A pure, stateless factory for instantiating and wiring together all application components.
 * It contains no behavioral logic or services. It ONLY connects the parts.
 * This class is platform-agnostic and resides in commonMain.
 */
class AppContainer(
    platformDependencies: PlatformDependencies,
    appCoroutineScope: CoroutineScope
) {
    lateinit var store: Store

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (::store.isInitialized) {
            store.handleFeatureException(throwable as Exception, "async-operation", "unknown-feature")
        } else {
            platformDependencies.log(
                app.auf.util.LogLevel.FATAL,
                "AppContainer",
                "FATAL: Uncaught exception before Store was initialized: ${throwable.stackTraceToString()}"
            )
        }
    }

    private val resilientCoroutineScope = CoroutineScope(appCoroutineScope.coroutineContext + SupervisorJob() + exceptionHandler)

    val features: List<Feature> = run {
        val gatewayFeature = GatewayFeature(
            platformDependencies,
            resilientCoroutineScope,
            providers = listOf(
                GeminiProvider(platformDependencies),
                OpenAIProvider(platformDependencies),
                AnthropicProvider(platformDependencies),
                InceptionProvider(platformDependencies)
            )
        )

        val allFeatures = mutableListOf<Feature>()
        allFeatures.addAll(listOf(
            CoreFeature(platformDependencies),
            SettingsFeature(platformDependencies),
            FileSystemFeature(platformDependencies),
            SessionFeature(platformDependencies, resilientCoroutineScope),
            gatewayFeature,
            AgentRuntimeFeature(platformDependencies, resilientCoroutineScope),
            KnowledgeGraphFeature(platformDependencies, resilientCoroutineScope),
            CommandBotFeature(platformDependencies)
        ))
        allFeatures
    }

    init {
        // Phase 2: validActionNames param removed. Store now validates against
        // AppState.actionDescriptors (pre-populated from ActionRegistry.byActionName).
        store = Store(
            initialState = AppState(),
            features = features,
            platformDependencies = platformDependencies
        )
    }
}