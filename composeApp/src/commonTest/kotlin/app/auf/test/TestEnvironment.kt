package app.auf.test

import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.Feature
import app.auf.core.FeatureState
import app.auf.core.Identity
import app.auf.core.PrivateDataEnvelope
import app.auf.core.Store
import app.auf.core.generated.ActionRegistry
import app.auf.core.generated.ActionRegistry
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
 *
 * Phase 2 changes: The validActionNames constructor param was removed. The Store
 * now validates actions against AppState.actionDescriptors. To register test-only
 * actions, merge testDescriptorsFor(...) into the initial AppState.actionDescriptors.
 */
class RecordingStore(
    initialState: AppState,
    features: List<Feature>,
    platformDependencies: FakePlatformDependencies
) : Store(initialState, features, platformDependencies) {

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

    /**
     * Executes a block of test code and, if it fails, prints a detailed
     * diagnostic report of all actions and logs before re-throwing the original exception.
     * This is the primary tool for debugging test failures.
     */
    fun runAndLogOnFailure(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            // Use Throwable to catch AssertionErrors and others
            println("\n\n" + "=".repeat(80))
            println("TEST FAILED: An exception was thrown. Printing diagnostic report...")
            println("=".repeat(80))

            println("\n--- PROCESSED ACTIONS (${processedActions.size}) ---")
            if (processedActions.isEmpty()) {
                println("No actions were processed by the Store.")
            } else {
                processedActions.forEachIndexed { index, action ->
                    println("${index.toString().padStart(3, ' ')}: $action")
                }
            }

            println("\n--- DELIVERED PRIVATE DATA (${deliveredPrivateData.size}) ---")
            if (deliveredPrivateData.isEmpty()) {
                println("No private data was delivered.")
            } else {
                deliveredPrivateData.forEachIndexed { index, delivery ->
                    println("${index.toString().padStart(3, ' ')}: From='${delivery.originator}' To='${delivery.recipient}' Type='${delivery.envelope.type}'")
                }
            }

            println("\n--- PLATFORM LOGS (${platform.capturedLogs.size}) ---")
            if (platform.capturedLogs.isEmpty()) {
                println("No platform logs were captured.")
            } else {
                platform.capturedLogs.forEach { log ->
                    val throwableString = log.throwable?.let { "\n${it.stackTraceToString()}" } ?: ""
                    println("[${log.level}] [${log.tag}] ${log.message}$throwableString")
                }
            }

            println("=".repeat(80))
            println("Original Exception:")
            println("=".repeat(80))
            throw e // Re-throw the original exception to ensure the test fails correctly.
        }
    }
}

/**
 * A fluent builder for creating controlled, multi-feature test environments.
 * This is the cornerstone of our Tier 2, 3, and 4 testing strategy.
 *
 * Phase 2 changes: The validActionNames constructor param was removed from the Store.
 * The Store now validates actions against AppState.actionDescriptors. To override,
 * use withActionDescriptors() to merge additional descriptors, or withActionRegistry()
 * to provide a complete override set of action names (which will be converted to
 * open+broadcast descriptors for backward compatibility).
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
    private var extraDescriptors: Map<String, ActionRegistry.ActionDescriptor> = emptyMap()

    companion object {
        fun create(): TestEnvironment {
            return TestEnvironment()
        }
    }

    fun withFeature(feature: Feature): TestEnvironment {
        if (features.none { it.identity.handle == feature.identity.handle }) {
            features.add(feature)
        }
        return this
    }

    fun withInitialState(featureName: String, state: FeatureState): TestEnvironment {
        initialStates[featureName] = state
        return this
    }

    /**
     * Overrides the default ActionRegistry with a specific set of action names.
     * These are converted to open+broadcast ActionDescriptors for backward compatibility.
     * This is an advanced feature for testing security guards and feature boundaries.
     */
    fun withActionRegistry(actions: Set<String>): TestEnvironment {
        this.actionRegistryOverride = actions
        return this
    }

    /**
     * Merges additional ActionDescriptors into AppState.actionDescriptors.
     * Use testDescriptorsFor() to create descriptors for test-only action names.
     * These are merged ON TOP of the base registry (ActionRegistry.byActionName or override).
     */
    fun withExtraDescriptors(descriptors: Map<String, ActionRegistry.ActionDescriptor>): TestEnvironment {
        this.extraDescriptors = this.extraDescriptors + descriptors
        return this
    }

    fun build(
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
        platform: FakePlatformDependencies = FakePlatformDependencies("2.0.0-test")
    ): TestHarness {
        val allFeatures = mutableListOf<Feature>()

        // Ensure CoreFeature and its state are always present for lifecycle management.
        val coreFeature = CoreFeature(platform)
        if (features.none { it.identity.handle == coreFeature.identity.handle }) {
            allFeatures.add(coreFeature)
        }
        if (!initialStates.containsKey(coreFeature.identity.handle)) {
            initialStates[coreFeature.identity.handle] = CoreState(lifecycle = AppLifecycle.RUNNING)
        }
        allFeatures.addAll(features)

        val initStateAction = Action("test.internal.INIT_DEFAULT_STATE")

        // Initialize state for features that don't have it specified
        val newFeatureStates = initialStates.toMutableMap()
        allFeatures.forEach { feature ->
            if (!newFeatureStates.containsKey(feature.identity.handle)) {
                val defaultState = feature.reducer(null, initStateAction)
                defaultState?.let { newFeatureStates[feature.identity.handle] = it }
            }
        }

        // Build action descriptors: start from base, apply override or default, merge extras
        val baseDescriptors = if (actionRegistryOverride != null) {
            testDescriptorsFor(actionRegistryOverride!!)
        } else {
            ActionRegistry.byActionName
        }
        val finalDescriptors = baseDescriptors + extraDescriptors

        val fullyPopulatedState = AppState(
            featureStates = newFeatureStates,
            actionDescriptors = finalDescriptors
        )

        val store = RecordingStore(fullyPopulatedState, allFeatures, platform)
        store.initFeatureLifecycles()

        return TestHarness(store, platform)
    }
}

/**
 * Creates open, broadcast ActionDescriptor entries for a set of test action names.
 * This allows test-only actions to pass the Store's schema validation against
 * AppState.actionDescriptors.
 *
 * All test actions are created as open+broadcast (Command type) so any originator
 * can dispatch them and all features receive them — the most permissive configuration
 * for general testing.
 *
 * For testing specific routing behaviors (internal, targeted, etc.), construct
 * ActionDescriptor instances directly with the desired flag combination.
 */
fun testDescriptorsFor(actionNames: Set<String>): Map<String, ActionRegistry.ActionDescriptor> {
    return actionNames.associateWith { name ->
        val parts = name.split(".", limit = 2)
        ActionRegistry.ActionDescriptor(
            fullName = name,
            featureName = parts.firstOrNull() ?: "test",
            suffix = parts.getOrElse(1) { name },
            summary = "Test action",
            open = true,
            broadcast = true,
            targeted = false,
            payloadFields = emptyList(),
            requiredFields = emptyList(),
            agentExposure = null
        )
    }
}