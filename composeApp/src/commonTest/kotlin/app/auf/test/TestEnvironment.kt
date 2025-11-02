package app.auf.test

import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.Feature
import app.auf.core.FeatureState
import app.auf.core.PrivateDataEnvelope
import app.auf.core.Store
import app.auf.core.generated.ActionNames
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreFeature
import app.auf.feature.core.CoreState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * A test-only Store that inherits from the real Store. In its init block, it
 * registers a callback with the `onDispatch` test hook. This allows it to
 * perfectly record all actions that successfully pass the real Store's guards
 * without modifying any production logic. This is the architecturally correct
 * pattern for observing the action flow in tests.
 */
class RecordingStore(
    initialState: AppState,
    features: List<Feature>,
    platformDependencies: FakePlatformDependencies,
    validActionNames: Set<String>
) : Store(initialState, features, platformDependencies, validActionNames) {

    val processedActions = mutableListOf<Action>()
    val deliveredPrivateData = mutableListOf<TestHarness.CapturedPrivateData>()

    init {
        // Register with the test hook to receive a copy of all validated actions.
        onDispatch = { action ->
            processedActions.add(action)
        }
    }

    override fun deliverPrivateData(originator: String, recipient: String, envelope: PrivateDataEnvelope) {
        deliveredPrivateData.add(TestHarness.CapturedPrivateData(originator, recipient, envelope))
        super.deliverPrivateData(originator, recipient, envelope)
    }
}


/**
 * The final, constructed test environment, providing convenient access to the
 * store, the recording feature, and fake platform dependencies.
 */
data class TestHarness(
    val store: RecordingStore,
    val platform: FakePlatformDependencies
) {
    /** A data class to hold captured private data for test assertions. */
    data class CapturedPrivateData(val originator: String, val recipient: String, val envelope: PrivateDataEnvelope)

    /** A convenience accessor for the list of successfully processed actions. */
    val processedActions: List<Action>
        get() = store.processedActions

    /** A convenience accessor for the list of delivered private data envelopes. */
    val deliveredPrivateData: List<CapturedPrivateData>
        get() = store.deliveredPrivateData
}

/**
 * A fluent builder for creating controlled, multi-feature test environments.
 * This is the cornerstone of our Tier 2, 3, and 4 testing strategy.
 * It defaults to using the canonical ActionRegistrySource but allows for explicit overrides.
 *
 * # USAGE WARNING: LIFECYCLE STATE
 *
 * This builder defaults to an `AppLifecycle.RUNNING` state **only if you do not
 * provide your own `CoreState`**. If you use `.withInitialState("core", ...)`
 * to set up specific test data, the `CoreState` constructor will default to
 * `AppLifecycle.BOOTING`, which will block most actions.
 *
 * **This is a frequent cause of test failures.**
 *
 * ### Example of Correct Setup:
 * ```
 * TestEnvironment.create()
 *     .withFeature(MyFeature())
 *     .withInitialState("core", CoreState(
 *         userIdentities = listOf(testUser), // Your specific setup data
 *         lifecycle = AppLifecycle.RUNNING    // <-- IMPORTANT: Explicitly set the lifecycle
 *     ))
 *     .build(platform)
 * ```
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

        var fullyPopulatedState = AppState(featureStates = initialStates)
        val initStateAction = Action("test.internal.INIT_DEFAULT_STATE")

        allFeatures.forEach { feature ->
            fullyPopulatedState = feature.reducer(fullyPopulatedState, initStateAction)
        }

        val validActionNames = actionRegistryOverride ?: ActionNames.allActionNames
        val store = RecordingStore(fullyPopulatedState, allFeatures, platform, validActionNames)
        store.initFeatureLifecycles()

        return TestHarness(store, platform)
    }
}