package app.auf.core

import app.auf.core.generated.ActionRegistry
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreFeature
import app.auf.feature.core.CoreState
import app.auf.test.testDescriptorsFor
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
        override val identity = Identity(
            uuid = null, localHandle = "testfeature", handle = "testfeature", name = "TestFeature"
        )
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
        override val identity = Identity(
            uuid = null, localHandle = "crashingfeature", handle = "crashingfeature", name = "CrashingFeature"
        )
        override val composableProvider: Feature.ComposableProvider? = null
        override fun reducer(state: FeatureState?, action: Action): FeatureState? {
            if (action.name == "test.CRASH_REDUCER") throw IllegalStateException("Reducer deliberately crashed")
            return state
        }
        override fun handleSideEffects(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {
            if (action.name == "test.CRASH_ON_ACTION") throw IllegalStateException("handleSideEffects deliberately crashed")
        }
        @Suppress("DEPRECATION")
        override fun onPrivateData(envelope: PrivateDataEnvelope, store: Store) {
            if (envelope.type == "test.CRASH_PRIVATE") throw IllegalStateException("onPrivateData deliberately crashed")
        }
    }

    // A specialized feature for the handleSideEffects crash test. It successfully changes its own
    // state in the reducer, and then fails in the handleSideEffects handler for the same action.
    private data class CrashingState(val changed: Boolean = false) : FeatureState
    private class StateChangingCrashingFeature : CrashingFeature() {
        override fun reducer(state: FeatureState?, action: Action): FeatureState? {
            if (action.name == "test.CRASH_ON_ACTION") {
                return CrashingState(changed = true)
            }
            return super.reducer(state, action)
        }
    }


    private val platform = FakePlatformDependencies("v2-test")

    // A minimal, custom action descriptor set for testing the store's guards.
    private val testActionNames = setOf(
        "test.INCREMENT",
        "test.CRASH_REDUCER",
        "test.CRASH_ON_ACTION"
    )

    private fun createStore(initialCoreState: CoreState, vararg extraFeatures: Feature): Store {
        val features = mutableListOf<Feature>(CoreFeature(platform), TestFeature())
        features.addAll(extraFeatures)
        val initialFeatureStates = mutableMapOf<String, FeatureState>(
            "core" to initialCoreState,
            "testfeature" to TestState()
        )
        extraFeatures.forEach {
            // Ensure the feature has a default state if it's a crashing feature
            if (it.identity.handle == "crashingfeature") {
                initialFeatureStates[it.identity.handle] = CrashingState()
            } else {
                initialFeatureStates[it.identity.handle] = object : FeatureState {}
            }
        }

        return Store(
            AppState(
                featureStates = initialFeatureStates,
                actionDescriptors = ActionRegistry.byActionName + testDescriptorsFor(testActionNames)
            ),
            features,
            platform
        )
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
    fun `store guard allows SYSTEM_INITIALIZING when BOOTING`() {
        val store = createStore(CoreState(lifecycle = AppLifecycle.BOOTING))
        val initialState = store.state.value
        store.dispatch("system.main", Action(ActionRegistry.Names.SYSTEM_INITIALIZING))
        assertNotEquals(initialState, store.state.value, "State should have changed.")
        val finalCoreState = store.state.value.featureStates["core"] as CoreState
        assertEquals(AppLifecycle.INITIALIZING, finalCoreState.lifecycle)
    }

    @Test
    fun `store guard blocks normal actions when CLOSING`() {
        val store = createStore(CoreState(lifecycle = AppLifecycle.CLOSING))
        val initialState = store.state.value
        store.dispatch("test.feature", Action("test.INCREMENT"))
        assertEquals(initialState, store.state.value, "State should not have changed during CLOSING.")
    }

    @Test
    fun `store guard allows SYSTEM_CLOSING when CLOSING`() {
        platform.capturedLogs.clear()
        val store = createStore(CoreState(lifecycle = AppLifecycle.CLOSING))
        store.dispatch("system.main", Action(ActionRegistry.Names.SYSTEM_CLOSING))

        val lifecycleError = platform.capturedLogs.find {
            it.level == LogLevel.ERROR && it.message.contains("invalid lifecycle state")
        }
        assertNull(lifecycleError, "system.CLOSING should be allowed during CLOSING lifecycle.")
    }

    @Test
    fun `store guard blocks SYSTEM_STARTING when RUNNING`() {
        platform.capturedLogs.clear()
        val store = createStore(CoreState(lifecycle = AppLifecycle.RUNNING))
        store.dispatch("system.main", Action(ActionRegistry.Names.SYSTEM_STARTING))

        val lifecycleError = platform.capturedLogs.find {
            it.level == LogLevel.ERROR && it.message.contains("invalid lifecycle state")
        }
        assertNotNull(lifecycleError, "system.STARTING should be blocked during RUNNING lifecycle.")
    }

    @Test
    fun `store guard blocks SYSTEM_INITIALIZING when RUNNING`() {
        platform.capturedLogs.clear()
        val store = createStore(CoreState(lifecycle = AppLifecycle.RUNNING))
        store.dispatch("system.main", Action(ActionRegistry.Names.SYSTEM_INITIALIZING))

        val lifecycleError = platform.capturedLogs.find {
            it.level == LogLevel.ERROR && it.message.contains("invalid lifecycle state")
        }
        assertNotNull(lifecycleError, "system.INITIALIZING should be blocked during RUNNING lifecycle.")
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
    fun `exception in handleSideEffects should NOT abort state change, but should log FATAL and show toast`() {
        platform.capturedLogs.clear()
        val store = createStore(CoreState(lifecycle = AppLifecycle.RUNNING), StateChangingCrashingFeature())
        val action = Action("test.CRASH_ON_ACTION")

        store.dispatch("test.crasher", action)

        val finalState = store.state.value
        val finalCoreState = finalState.featureStates["core"] as CoreState
        val finalCrashingState = finalState.featureStates["crashingfeature"] as CrashingState

        assertTrue(finalCrashingState.changed, "State change from the successful reducer should be preserved.")
        assertNotNull(finalCoreState.toastMessage, "A toast message should be present.")
        assertTrue(finalCoreState.toastMessage!!.contains("An internal error occurred in 'broadcast'"))

        val log = platform.capturedLogs.find { it.level == LogLevel.FATAL }
        assertNotNull(log, "A FATAL log should have been captured.")
        assertTrue(log.message.contains("FATAL EXCEPTION in reducer/handleSideEffects"), "Log message should identify the correct generic location.")
    }


    @Test
    fun `deprecated deliverPrivateData bridges to targeted dispatch and rejects unknown action`() {
        // Phase 3: deliverPrivateData is now a deprecated bridge that converts to
        // deferredDispatch with targetRecipient. The envelope type becomes the action name,
        // which flows through processAction with full validation.
        //
        // Since "test.CRASH_PRIVATE" is not in actionDescriptors, the Store rejects it
        // at Step 1 (schema lookup) as an unknown action. The onPrivateData method is
        // never called — crashes there are no longer possible via this code path.
        platform.capturedLogs.clear()
        val store = createStore(CoreState(lifecycle = AppLifecycle.RUNNING), CrashingFeature())

        @Suppress("DEPRECATION")
        val envelope = PrivateDataEnvelope("test.CRASH_PRIVATE", buildJsonObject { put("data", "test") })
        @Suppress("DEPRECATION")
        store.deliverPrivateData("test.sender", "crashingfeature", envelope)

        // The bridge should log a deprecation warning
        val warnLog = platform.capturedLogs.find {
            it.level == LogLevel.WARN && it.message.contains("DEPRECATED deliverPrivateData")
        }
        assertNotNull(warnLog, "The deprecated bridge should log a warning about the deprecated call.")

        // The Store should reject the action as unknown (not in actionDescriptors)
        val errorLog = platform.capturedLogs.find {
            it.level == LogLevel.ERROR && it.message.contains("Unknown Action")
        }
        assertNotNull(errorLog, "The bridge routes through processAction, which rejects unknown action names.")

        // No toast or FATAL log — the action was simply rejected, not crashed
        val fatalLog = platform.capturedLogs.find { it.level == LogLevel.FATAL }
        assertNull(fatalLog, "No FATAL error should occur — the action is cleanly rejected before reaching any feature.")
    }

    // --- handleFeatureException Direct Test ---

    @Test
    fun `handleFeatureException logs FATAL and dispatches toast action`() {
        platform.capturedLogs.clear()
        val store = createStore(CoreState(lifecycle = AppLifecycle.RUNNING))

        val testException = RuntimeException("Something went wrong")
        store.handleFeatureException(testException as Exception, "test-location", "test-feature")

        // Verify FATAL log
        val fatalLog = platform.capturedLogs.find { it.level == LogLevel.FATAL }
        assertNotNull(fatalLog, "A FATAL log should be captured.")
        assertTrue(fatalLog.message.contains("FATAL EXCEPTION in test-location"),
            "Log should contain the location.")
        assertTrue(fatalLog.message.contains("test-feature"),
            "Log should contain the feature name.")
        assertTrue(fatalLog.message.contains("Something went wrong"),
            "Log should contain the exception message.")

        // Verify toast was dispatched (core state should have a toast message)
        val coreState = store.state.value.featureStates["core"] as CoreState
        assertNotNull(coreState.toastMessage, "A toast message should be present after handleFeatureException.")
        assertTrue(coreState.toastMessage!!.contains("test-feature"),
            "Toast message should reference the feature name.")
    }
}