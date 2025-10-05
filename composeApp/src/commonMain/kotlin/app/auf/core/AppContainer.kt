package app.auf.core

import app.auf.feature.core.CoreFeature
import app.auf.feature.filesystem.FileSystemFeature
import app.auf.feature.settings.SettingsFeature
import app.auf.feature.session.SessionFeature
//import app.auf.feature.agent.AgentRuntimeFeature
//import app.auf.feature.knowledgegraph.KnowledgeGraphFeature
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope

/**
 * ## Mandate
 * A pure, stateless factory for instantiating and wiring together all application components.
 * It contains no behavioral logic or services. It ONLY connects the parts.
 * This class is platform-agnostic and resides in commonMain.
 */
class AppContainer(
    platformDependencies: PlatformDependencies,
    coroutineScope: CoroutineScope
) {
    // Features are responsible for creating their own internal dependencies.
    // The container is only responsible for instantiating the features themselves.
    val features: List<Feature> = run {
        val allFeatures = mutableListOf<Feature>()
        allFeatures.addAll(listOf(
            CoreFeature(platformDependencies),
            SettingsFeature(platformDependencies),
            FileSystemFeature(platformDependencies),
            SessionFeature(platformDependencies, coroutineScope)
            //AgentRuntimeFeature(platformDependencies, coroutineScope),
            //KnowledgeGraphFeature(platformDependencies, coroutineScope)
        ))
        allFeatures
    }

    val store: Store = Store(AppState(), features, platformDependencies)
}