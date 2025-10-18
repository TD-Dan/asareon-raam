package app.auf.test

import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.Feature
import app.auf.core.FeatureState
import app.auf.core.Store
import app.auf.core.generated.ActionRegistrySource
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreFeature
import app.auf.feature.core.CoreState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * A private, test-only feature whose sole purpose is to capture all actions that
 * successfully pass the Store's guards and are broadcast to feature onAction handlers.
 * This is the canonical way to assert on successful action processing.
 */
class RecordingFeature : Feature {
    override val name = "internal_recorder"
    val receivedActions = mutableListOf<Action>()

    // This composableProvider is nullable, so a default implementation is enough.
    override val composableProvider: Feature.ComposableProvider? = null
    override fun onAction(action: Action, store: Store) {
        // The action is stamped with an originator by the time it gets here.
        receivedActions.add(action)
    }
}


/**
 * The final, constructed test environment, providing convenient access to the
 * store, the recording feature, and fake platform dependencies.
 */
data class TestHarness(
    val store: Store,
    val platform: FakePlatformDependencies,
    private val recorder: RecordingFeature
) {
    /** A convenience accessor for the list of successfully processed actions. */
    val processedActions: List<Action>
        get() = recorder.receivedActions
}

/**
 * A fluent builder for creating controlled, multi-feature test environments.
 * This is the cornerstone of our Tier 2, 3, and 4 testing strategy.
 * It defaults to using the canonical ActionRegistrySource but allows for explicit overrides.
 */
class TestEnvironment {
    private val features = mutableListOf<Feature>()
    private val initialStates = mutableMapOf<String, FeatureState>()
    private var actionRegistryOverride: Set<String>? = null

    companion object {
        fun create(): TestEnvironment {
            return TestEnvironment()
        }
    }

    fun withFeature(feature: Feature): TestEnvironment {
        if (features.none { it.name == feature.name }) {
            features.add(feature)
        }
        return this
    }

    fun withInitialState(featureName: String, state: FeatureState): TestEnvironment {
        initialStates[featureName] = state
        return this
    }

    /**
     * Overrides the default ActionRegistrySource with a specific set of action names.
     * This is an advanced feature for testing security guards and feature boundaries.
     */
    fun withActionRegistry(actions: Set<String>): TestEnvironment {
        this.actionRegistryOverride = actions
        return this
    }

    fun build(
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
        platform: FakePlatformDependencies = FakePlatformDependencies("2.0.0-test")
    ): TestHarness {
        val allFeatures = mutableListOf<Feature>()

        // Ensure CoreFeature and its state are always present for lifecycle management.
        val coreFeature = CoreFeature(platform)
        if (features.none { it.name == coreFeature.name }) {
            allFeatures.add(coreFeature)
        }
        if (!initialStates.containsKey(coreFeature.name)) {
            initialStates[coreFeature.name] = CoreState(lifecycle = AppLifecycle.RUNNING)
        }
        allFeatures.addAll(features)

        // Add the recording feature to intercept all successful actions.
        val recorder = RecordingFeature()
        allFeatures.add(recorder)


        var fullyPopulatedState = AppState(featureStates = initialStates)
        val initStateAction = Action("test.internal.INIT_DEFAULT_STATE")

        allFeatures.forEach { feature ->
            fullyPopulatedState = feature.reducer(fullyPopulatedState, initStateAction)
        }

        val validActionNames = actionRegistryOverride ?: ActionRegistrySource.allActionNames
        val store = Store(fullyPopulatedState, allFeatures, platform, validActionNames)
        store.initFeatureLifecycles()

        return TestHarness(store, platform, recorder)
    }
}