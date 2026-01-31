package app.auf.core

import app.auf.core.generated.ActionNames
import app.auf.feature.agent.AgentRuntimeFeature
import app.auf.feature.commandbot.CommandBotFeature
import app.auf.feature.core.CoreFeature
import app.auf.feature.filesystem.FileSystemFeature
import app.auf.feature.gateway.GatewayFeature
import app.auf.feature.gateway.gemini.GeminiProvider
import app.auf.feature.gateway.openai.OpenAIProvider
import app.auf.feature.gateway.anthropic.AnthropicProvider
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
        // This handler will catch any uncaught exception from any coroutine launched
        // by a feature that uses the provided scope.
        // We delegate to the Store's centralized handler to log the error and show a toast.
        if (::store.isInitialized) {
            store.handleFeatureException(throwable as Exception, "async-operation", "unknown-feature")
        } else {
            // Fallback if the store itself failed to initialize
            platformDependencies.log(
                app.auf.util.LogLevel.FATAL,
                "AppContainer",
                "FATAL: Uncaught exception before Store was initialized: ${throwable.stackTraceToString()}"
            )
        }
    }

    // Create a new scope that combines the SupervisorJob, the main dispatcher, and our exception handler.
    private val resilientCoroutineScope = CoroutineScope(appCoroutineScope.coroutineContext + SupervisorJob() + exceptionHandler)

    val features: List<Feature> = run {
        val gatewayFeature = GatewayFeature(
            platformDependencies,
            resilientCoroutineScope, // Pass the new, resilient scope to features
            providers = listOf(
                GeminiProvider(platformDependencies),
                OpenAIProvider(platformDependencies),
                AnthropicProvider(platformDependencies)
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
            CommandBotFeature(platformDependencies) // *** ADDED FEATURE ***
        ))
        allFeatures
    }

    init {
        store = Store(
            initialState = AppState(),
            features = features,
            platformDependencies = platformDependencies,
            validActionNames = ActionNames.allActionNames
        )
    }
}