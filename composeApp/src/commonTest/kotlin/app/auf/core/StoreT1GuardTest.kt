package app.auf.core

import app.auf.core.generated.ActionNames
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreFeature
import app.auf.feature.core.CoreState
import app.auf.util.LogLevel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Tier 1 Unit Tests for the Store component.
 *
 * Mandate (P-TEST-001, T1): To test the Store's internal logic, particularly its
 * security, lifecycle, and exception handling guards, in complete isolation.
 */
class StoreT1GuardTest {

    // A simple, self-contained feature for testing state changes within this test file.
    private data class TestState(val value: Int = 0) : FeatureState
    private class TestFeature : Feature {
        override val name = "TestFeature"
        override val composableProvider: Feature.ComposableProvider? = null
        override fun reducer(state: FeatureState?, action: Action): FeatureState? {
            if (action.name == "test.INCREMENT") {
                val testState = state as? TestState ?: TestState()
                return testState.copy(value = testState.value + 1)
            }
            return state
        }
    }

    // A feature designed to fail for testing exception handling.
    private open class CrashingFeature : Feature {
        override val name = "CrashingFeature"
        override val composableProvider: Feature.ComposableProvider? = null
        override fun reducer(state: FeatureState?, action: Action): FeatureState? {
            if (action.name == "test.CRASH_REDUCER") throw IllegalStateException("Reducer deliberately crashed")
            return state
        }
        override fun onAction(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {
            if (action.name == "test.CRASH_ON_ACTION") throw IllegalStateException("onAction deliberately crashed")
        }
        override fun onPrivateData(envelope: PrivateDataEnvelope, store: Store) {
            if (envelope.type == "test.CRASH_PRIVATE") throw IllegalStateException("onPrivateData deliberately crashed")
        }
    }

    // A specialized feature for the onAction crash test. It successfully changes its own
    // state in the reducer, and then fails in the onAction handler for the same action.
    private data class CrashingState(val changed: Boolean = false) : FeatureState
    private class StateChangingCrashingFeature : CrashingFeature() {
        override val name = "CrashingFeature" // Same name to occupy the same state slice
        override fun reducer(state: FeatureState?, action: Action): FeatureState? {
            if (action.name == "test.CRASH_ON_ACTION") {
                return CrashingState(changed = true)
            }
            return super.reducer(state, action)
        }
    }


    private val platform = FakePlatformDependencies("v2-test")

    // A minimal, custom action registry for testing the store's guards.
    private val testActionRegistry = setOf(
        ActionNames.SYSTEM_PUBLISH_INITIALIZING,
        ActionNames.SYSTEM_PUBLISH_STARTING,
        ActionNames.SYSTEM_PUBLISH_CLOSING,
        ActionNames.CORE_SHOW_TOAST, // Needed for the exception handler
        "test.INCREMENT",
        "test.CRASH_REDUCER",
        "test.CRASH_ON_ACTION"
    )

    private fun createStore(initialCoreState: CoreState, vararg extraFeatures: Feature): Store {
        val features = mutableListOf<Feature>(CoreFeature(platform), TestFeature())
        features.addAll(extraFeatures)
        val initialFeatureStates = mutableMapOf<String, FeatureState>(
            "core" to initialCoreState,
            "TestFeature" to TestState()
        )
        extraFeatures.forEach {
            // Ensure the feature has a default state if it's a crashing feature
            if (it.name == "CrashingFeature") {
                initialFeatureStates[it.name] = CrashingState()
            } else {
                initialFeatureStates[it.name] = object : FeatureState {}
            }
        }

        return Store(AppState(featureStates = initialFeatureStates), features, platform, testActionRegistry)
    }

    // --- Security & Lifecycle Guard Tests ---

    @Test
    fun `store guard blocks unknown actions`() {
        val store = createStore(CoreState(lifecycle = AppLifecycle.RUNNING))
        val initialState = store.state.value
        store.dispatch("test.feature", Action("test.UNKNOWN_ACTION"))
        assertEquals(initialState, store.state.value, "State should not have changed for an unknown action.")
    }

    @Test
    fun `store guard blocks normal actions when BOOTING`() {
        val store = createStore(CoreState(lifecycle = AppLifecycle.BOOTING))
        val initialState = store.state.value
        store.dispatch("test.feature", Action("test.INCREMENT"))
        assertEquals(initialState, store.state.value, "State should not have changed.")
    }

    @Test
    fun `store guard allows SYSTEM_PUBLISH_INITIALIZING when BOOTING`() {
        val store = createStore(CoreState(lifecycle = AppLifecycle.BOOTING))
        val initialState = store.state.value
        store.dispatch("system.main", Action(ActionNames.SYSTEM_PUBLISH_INITIALIZING))
        assertNotEquals(initialState, store.state.value, "State should have changed.")
        val finalCoreState = store.state.value.featureStates["core"] as CoreState
        assertEquals(AppLifecycle.INITIALIZING, finalCoreState.lifecycle)
    }

    // --- Exception Handling Tests ---

    @Test
    fun `exception in reducer should abort state change, log FATAL, and show toast`() {
        platform.capturedLogs.clear()
        val store = createStore(CoreState(lifecycle = AppLifecycle.RUNNING), CrashingFeature())
        val initialState = store.state.value

        store.dispatch("test.crasher", Action("test.CRASH_REDUCER"))

        val finalState = store.state.value
        val finalCoreState = finalState.featureStates["core"] as CoreState

        // CORRECTED ASSERTION: Compare the state maps after removing the 'core' slice from BOTH.
        // This correctly verifies that all other feature states were not mutated.
        assertEquals(
            initialState.featureStates - "core",
            finalState.featureStates - "core",
            "Non-core state should not have changed."
        )

        assertNotNull(finalCoreState.toastMessage, "A toast message should be present in the final state.")
        assertTrue(finalCoreState.toastMessage!!.contains("An internal error occurred in 'broadcast'"), "Toast message should identify the source.")

        val log = platform.capturedLogs.find { it.level == LogLevel.FATAL }
        assertNotNull(log, "A FATAL log should have been captured.")
        assertTrue(log.message.contains("FATAL EXCEPTION in reducer"), "Log message should identify the location.")
    }

    @Test
    fun `exception in onAction should NOT abort state change, but should log FATAL and show toast`() {
        platform.capturedLogs.clear()
        val store = createStore(CoreState(lifecycle = AppLifecycle.RUNNING), StateChangingCrashingFeature())
        val action = Action("test.CRASH_ON_ACTION")

        store.dispatch("test.crasher", action)

        val finalState = store.state.value
        val finalCoreState = finalState.featureStates["core"] as CoreState
        val finalCrashingState = finalState.featureStates["CrashingFeature"] as CrashingState

        assertTrue(finalCrashingState.changed, "State change from the successful reducer should be preserved.")
        assertNotNull(finalCoreState.toastMessage, "A toast message should be present.")
        assertTrue(finalCoreState.toastMessage!!.contains("An internal error occurred in 'broadcast'"))

        val log = platform.capturedLogs.find { it.level == LogLevel.FATAL }
        assertNotNull(log, "A FATAL log should have been captured.")
        assertTrue(log.message.contains("FATAL EXCEPTION in reducer/onAction"), "Log message should identify the correct generic location.")
    }


    @Test
    fun `exception in onPrivateData should log FATAL and show toast`() {
        platform.capturedLogs.clear()
        val store = createStore(CoreState(lifecycle = AppLifecycle.RUNNING), CrashingFeature())

        val envelope = PrivateDataEnvelope("test.CRASH_PRIVATE", buildJsonObject { put("data", "test") })
        store.deliverPrivateData("test.sender", "CrashingFeature", envelope)

        val finalState = store.state.value
        val finalCoreState = finalState.featureStates["core"] as CoreState

        assertNotNull(finalCoreState.toastMessage, "A toast message should be present.")
        assertTrue(finalCoreState.toastMessage!!.contains("An internal error occurred in 'CrashingFeature'"))

        val log = platform.capturedLogs.find { it.level == LogLevel.FATAL }
        assertNotNull(log, "A FATAL log should have been captured.")
        assertTrue(log.message.contains("FATAL EXCEPTION in onPrivateData for feature 'CrashingFeature'"))
    }
}
