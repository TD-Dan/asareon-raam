package app.auf.core

import app.auf.core.generated.ActionNames
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreFeature
import app.auf.feature.core.CoreState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Tier 1 Unit Tests for the Store component.
 *
 * Mandate (P-TEST-001, T1): To test the Store's internal logic, particularly its
 * security and lifecycle guards, in complete isolation.
 */
class StoreT1GuardTest {

    // A simple, self-contained feature for testing state changes within this test file.
    private data class TestState(val value: Int = 0) : FeatureState
    private class TestFeature : Feature {
        override val name = "TestFeature"
        override val composableProvider: Feature.ComposableProvider? = null
        override fun reducer(state: AppState, action: Action): AppState {
            if (action.name == "test.INCREMENT") {
                val testState = state.featureStates[name] as? TestState ?: TestState()
                val newTestState = testState.copy(value = testState.value + 1)
                return state.copy(featureStates = state.featureStates + (name to newTestState))
            }
            return state
        }
    }

    private val platform = FakePlatformDependencies("v2-test")

    // A minimal, custom action registry for testing the store's guards.
    private val testActionRegistry = setOf(
        ActionNames.SYSTEM_PUBLISH_INITIALIZING,
        ActionNames.SYSTEM_PUBLISH_STARTING,
        ActionNames.SYSTEM_PUBLISH_CLOSING,
        "test.INCREMENT"
    )

    private fun createStore(initialCoreState: CoreState): Store {
        val features = listOf(CoreFeature(platform), TestFeature())
        val initialState = AppState(
            featureStates = mapOf(
                "core" to initialCoreState,
                "TestFeature" to TestState()
            )
        )
        return Store(initialState, features, platform, testActionRegistry)
    }

    @Test
    fun `store guard blocks unknown actions`() {
        val store = createStore(CoreState(lifecycle = AppLifecycle.RUNNING))
        val initialState = store.state.value

        store.dispatch("test.feature", Action("test.UNKNOWN_ACTION"))
        val finalState = store.state.value

        assertEquals(initialState, finalState, "State should not have changed for an unknown action.")
    }

    @Test
    fun `store guard blocks normal actions when BOOTING`() {
        val store = createStore(CoreState(lifecycle = AppLifecycle.BOOTING))
        val initialState = store.state.value

        store.dispatch("test.feature", Action("test.INCREMENT"))
        val finalState = store.state.value

        assertEquals(initialState, finalState, "State should not have changed.")
    }

    @Test
    fun `store guard allows SYSTEM_PUBLISH_INITIALIZING when BOOTING`() {
        val store = createStore(CoreState(lifecycle = AppLifecycle.BOOTING))
        val initialState = store.state.value

        store.dispatch("system.main", Action(ActionNames.SYSTEM_PUBLISH_INITIALIZING))
        val finalState = store.state.value

        assertNotEquals(initialState, finalState, "State should have changed.")
        val finalCoreState = finalState.featureStates["core"] as CoreState
        assertEquals(AppLifecycle.INITIALIZING, finalCoreState.lifecycle)
    }

    @Test
    fun `store guard allows all actions when INITIALIZING`() {
        val store = createStore(CoreState(lifecycle = AppLifecycle.INITIALIZING))
        val initialTestState = store.state.value.featureStates["TestFeature"] as TestState

        store.dispatch("test.feature", Action("test.INCREMENT"))
        val finalTestState = store.state.value.featureStates["TestFeature"] as TestState

        assertNotEquals(initialTestState, finalTestState, "State should have changed.")
        assertEquals(1, finalTestState.value)
    }

    @Test
    fun `store guard blocks startup actions when RUNNING`() {
        val store = createStore(CoreState(lifecycle = AppLifecycle.RUNNING))
        val initialState = store.state.value

        store.dispatch("system.main", Action(ActionNames.SYSTEM_PUBLISH_INITIALIZING))
        assertEquals(initialState, store.state.value, "State should not change for INITIALIZING in RUNNING state.")

        store.dispatch("system.main", Action(ActionNames.SYSTEM_PUBLISH_STARTING))
        assertEquals(initialState, store.state.value, "State should not change for STARTING in RUNNING state.")
    }

    @Test
    fun `store guard allows normal actions when RUNNING`() {
        val store = createStore(CoreState(lifecycle = AppLifecycle.RUNNING))
        val initialTestState = store.state.value.featureStates["TestFeature"] as TestState

        store.dispatch("test.feature", Action("test.INCREMENT"))
        val finalTestState = store.state.value.featureStates["TestFeature"] as TestState

        assertNotEquals(initialTestState, finalTestState, "State should have changed.")
        assertEquals(1, finalTestState.value)
    }
}