package app.auf.core

import app.auf.core.generated.ActionRegistry
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreFeature
import app.auf.feature.core.CoreState
import app.auf.test.testDescriptorsFor
import app.auf.util.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tier 1 Unit Tests for the Store's flow control logic.
 *
 * Mandate (P-TEST-001, T1): To test the deferred dispatch and re-entrancy guard
 * mechanisms, ensuring a predictable, sequential, and non-recursive action flow.
 */
class StoreT1FlowControlTest {

    private data class SequencingState(val log: List<String> = emptyList()) : FeatureState

    /**
     * A test feature that can dispatch other actions from its handlers, allowing us
     * to test the Store's flow control logic.
     */
    private class SequencingFeature : Feature {
        override val identity = Identity(
            uuid = null, localHandle = "sequencing", handle = "sequencing", name = "SequencingFeature"
        )
        override val composableProvider: Feature.ComposableProvider? = null

        // The reducer adds the action name to a log to verify order of state changes.
        override fun reducer(state: FeatureState?, action: Action): FeatureState? {
            if (action.name.startsWith("seq.")) {
                val currentLog = (state as? SequencingState)?.log ?: emptyList()
                val newLog = currentLog + "${action.name}:REDUCER"
                return SequencingState(newLog)
            }
            return state
        }

        // The handleSideEffects handler triggers subsequent dispatches based on the action name.
        override fun handleSideEffects(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {
            when (action.name) {
                "seq.A_DEFERRED_B" -> store.deferredDispatch(identity.handle, Action("seq.B"))
                "seq.A_IMMEDIATE_B" -> store.dispatch(identity.handle, Action("seq.B")) // Test re-entrancy guard
                "seq.A_DEFERRED_B_C" -> {
                    store.deferredDispatch(identity.handle, Action("seq.B"))
                    store.deferredDispatch(identity.handle, Action("seq.C"))
                }
                "seq.B_DEFERRED_C" -> store.deferredDispatch(identity.handle, Action("seq.C"))
            }
        }
    }

    private val platform = FakePlatformDependencies("v2-test")
    private val testActionNames = setOf(
        "seq.A", "seq.B", "seq.C",
        "seq.A_DEFERRED_B", "seq.A_IMMEDIATE_B",
        "seq.A_DEFERRED_B_C", "seq.B_DEFERRED_C"
    )

    private fun createStore(): Store {
        val features = listOf(CoreFeature(platform), SequencingFeature())
        val initialState = AppState(
            featureStates = mapOf(
                "core" to CoreState(lifecycle = AppLifecycle.RUNNING),
                "sequencing" to SequencingState()
            ),
            actionDescriptors = ActionRegistry.byActionName + testDescriptorsFor(testActionNames)
        )
        return Store(initialState, features, platform)
    }

    @Test
    fun `deferredDispatch queues an action to be processed after the current cycle`() {
        val store = createStore()

        store.dispatch("test", Action("seq.A_DEFERRED_B"))

        val finalState = store.state.value.featureStates["sequencing"] as SequencingState
        val expectedLog = listOf(
            "seq.A_DEFERRED_B:REDUCER",
            "seq.B:REDUCER"
        )
        assertEquals(expectedLog, finalState.log, "Reducer for B should run after reducer for A.")
    }

    @Test
    fun `re-entrant dispatch is automatically deferred and processed sequentially`() {
        platform.capturedLogs.clear()
        val store = createStore()

        store.dispatch("test", Action("seq.A_IMMEDIATE_B"))

        val finalState = store.state.value.featureStates["sequencing"] as SequencingState
        val expectedLog = listOf(
            "seq.A_IMMEDIATE_B:REDUCER",
            "seq.B:REDUCER"
        )
        assertEquals(expectedLog, finalState.log, "State changes should still happen sequentially.")

        val warningLog = platform.capturedLogs.find { it.level == LogLevel.WARN && it.message.contains("Re-entrant dispatch detected") }
        assertNotNull(warningLog, "A warning should be logged for the auto-deferral.")
    }

    @Test
    fun `multiple deferred actions are queued and processed in FIFO order`() {
        val store = createStore()

        store.dispatch("test", Action("seq.A_DEFERRED_B_C"))

        val finalState = store.state.value.featureStates["sequencing"] as SequencingState
        val expectedLog = listOf(
            "seq.A_DEFERRED_B_C:REDUCER",
            "seq.B:REDUCER",
            "seq.C:REDUCER"
        )
        assertEquals(expectedLog, finalState.log, "Actions B and C should be processed in the order they were deferred.")
    }

    @Test
    fun `deferred actions from a deferred action are processed correctly`() {
        val store = createStore()

        // This action will cause seq.B_DEFERRED_C's handler to defer seq.C.
        store.dispatch("test", Action("seq.B_DEFERRED_C"))

        val finalState = store.state.value.featureStates["sequencing"] as SequencingState
        val expectedLog = listOf(
            "seq.B_DEFERRED_C:REDUCER",
            "seq.C:REDUCER"
        )
        assertEquals(expectedLog, finalState.log, "Action C, deferred by B, should run after B.")
    }

    @Test
    fun `handleSideEffects for deferred action sees the state from the previous completed cycle`() {
        var stateSeenByB = ""

        class StateCheckingFeature : Feature {
            override val identity = Identity(
                uuid = null, localHandle = "statecheck", handle = "statecheck", name = "StateCheck"
            )
            override val composableProvider: Feature.ComposableProvider? = null
            override fun reducer(state: FeatureState?, action: Action): FeatureState? {
                return if (action.name == "seq.A") {
                    SequencingState(log = listOf("State from A"))
                } else state
            }
            override fun handleSideEffects(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {
                when(action.name) {
                    "seq.A" -> store.deferredDispatch(identity.handle, Action("seq.B"))
                    "seq.B" -> {
                        // CAPTURE: When B's handleSideEffects runs, what does it see in the state?
                        stateSeenByB = (newState as SequencingState).log.first()
                    }
                }
            }
        }

        val store = Store(
            AppState(
                featureStates = mapOf(
                    "core" to CoreState(lifecycle = AppLifecycle.RUNNING),
                    "statecheck" to SequencingState()
                ),
                actionDescriptors = ActionRegistry.byActionName + testDescriptorsFor(setOf("seq.A", "seq.B"))
            ),
            listOf(CoreFeature(platform), StateCheckingFeature()),
            platform
        )

        store.dispatch("test", Action("seq.A"))

        assertEquals("State from A", stateSeenByB, "handleSideEffects for B must see the state fully updated by A's reducer.")
    }
}