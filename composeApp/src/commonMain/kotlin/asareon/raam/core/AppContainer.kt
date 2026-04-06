package asareon.raam.core

import asareon.raam.feature.agent.AgentRuntimeFeature
import asareon.raam.feature.commandbot.CommandBotFeature
import asareon.raam.feature.core.CoreFeature
import asareon.raam.feature.backup.BackupFeature
import asareon.raam.feature.filesystem.FileSystemFeature
import asareon.raam.feature.gateway.GatewayFeature
import asareon.raam.feature.gateway.gemini.GeminiProvider
import asareon.raam.feature.gateway.openai.OpenAIProvider
import asareon.raam.feature.gateway.anthropic.AnthropicProvider
import asareon.raam.feature.gateway.inception.InceptionProvider
import asareon.raam.feature.gateway.minimax.MiniMaxProvider
import asareon.raam.feature.knowledgegraph.KnowledgeGraphFeature
import asareon.raam.feature.settings.SettingsFeature
import asareon.raam.feature.session.SessionFeature
import asareon.raam.util.PlatformDependencies
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
                asareon.raam.util.LogLevel.FATAL,
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
                InceptionProvider(platformDependencies),
                MiniMaxProvider(platformDependencies)
            )
        )

        val allFeatures = mutableListOf<Feature>()
        allFeatures.addAll(listOf(
            BackupFeature(platformDependencies),
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