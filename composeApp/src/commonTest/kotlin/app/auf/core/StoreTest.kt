package app.auf.core

import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreFeature
import app.auf.feature.core.CoreState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

// A simple, self-contained feature for testing state changes within this test file.
private data class TestState(val value: Int = 0) : FeatureState
private class TestFeature : Feature {
    override val name = "TestFeature"
    override fun reducer(state: AppState, action: Action): AppState {
        if (action.name == "test.INCREMENT") {
            val testState = state.featureStates[name] as? TestState ?: TestState()
            val newTestState = testState.copy(value = testState.value + 1)
            return state.copy(featureStates = state.featureStates + (name to newTestState))
        }
        return state
    }
}

class StoreTest {

    private val platform = FakePlatformDependencies("v2-test")

    private fun createStore(initialCoreState: CoreState): Store {
        val features = listOf(CoreFeature(platform), TestFeature())
        val initialState = AppState(
            featureStates = mapOf(
                "CoreFeature" to initialCoreState,
                "TestFeature" to TestState()
            )
        )
        return Store(initialState, features, platform)
    }

    @Test
    fun `store guard blocks normal actions when BOOTING`() {
        // Arrange
        val store = createStore(CoreState(lifecycle = AppLifecycle.BOOTING))
        val initialState = store.state.value

        // Act
        store.dispatch(Action("test.INCREMENT"))
        val finalState = store.state.value

        // Assert
        assertEquals(initialState, finalState, "State should not have changed.")
    }

    @Test
    fun `store guard allows app_INITIALIZING when BOOTING`() {
        // Arrange
        val store = createStore(CoreState(lifecycle = AppLifecycle.BOOTING))
        val initialState = store.state.value

        // Act
        store.dispatch(Action("app.INITIALIZING"))
        val finalState = store.state.value

        // Assert
        assertNotEquals(initialState, finalState, "State should have changed.")
        val finalCoreState = finalState.featureStates["CoreFeature"] as CoreState
        assertEquals(AppLifecycle.INITIALIZING, finalCoreState.lifecycle)
    }

    @Test
    fun `store guard allows all actions when INITIALIZING`() {
        // Arrange
        val store = createStore(CoreState(lifecycle = AppLifecycle.INITIALIZING))
        val initialTestState = store.state.value.featureStates["TestFeature"] as TestState

        // Act
        store.dispatch(Action("test.INCREMENT"))
        val finalTestState = store.state.value.featureStates["TestFeature"] as TestState

        // Assert
        assertNotEquals(initialTestState, finalTestState, "State should have changed.")
        assertEquals(1, finalTestState.value)
    }

    @Test
    fun `store guard blocks startup actions when RUNNING`() {
        // Arrange
        val store = createStore(CoreState(lifecycle = AppLifecycle.RUNNING))
        val initialState = store.state.value

        // Act & Assert for app.INITIALIZING
        store.dispatch(Action("app.INITIALIZING"))
        assertEquals(initialState, store.state.value, "State should not change for app.INITIALIZING in RUNNING state.")

        // Act & Assert for app.STARTING
        store.dispatch(Action("app.STARTING"))
        assertEquals(initialState, store.state.value, "State should not change for app.STARTING in RUNNING state.")
    }

    @Test
    fun `store guard allows normal actions when RUNNING`() {
        // Arrange
        val store = createStore(CoreState(lifecycle = AppLifecycle.RUNNING))
        val initialTestState = store.state.value.featureStates["TestFeature"] as TestState

        // Act
        store.dispatch(Action("test.INCREMENT"))
        val finalTestState = store.state.value.featureStates["TestFeature"] as TestState

        // Assert
        assertNotEquals(initialTestState, finalTestState, "State should have changed.")
        assertEquals(1, finalTestState.value)
    }
}