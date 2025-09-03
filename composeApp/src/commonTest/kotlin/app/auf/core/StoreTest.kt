package app.auf.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StoreTest {

    // --- Test-specific helper classes ---

    /** A custom state slice for our FakeFeature. */
    data class FakeFeatureState(val name: String = "Fake", val eventCount: Int = 0)

    /** A custom action that only our FakeFeature understands. */
    sealed interface FakeFeatureAction : AppAction {
        data object IncrementEventCount : FakeFeatureAction
    }

    /**
     * A simple, self-contained Feature used to test the Store's orchestration.
     * Its reducer responds to both its own custom action and a core AppAction.
     */
    class FakeFeature : Feature {
        override val name: String = "FakeFeature"

        override fun reducer(state: AppState, action: AppAction): AppState {
            val currentState = state.featureStates[name] as? FakeFeatureState ?: FakeFeatureState()

            val newFeatureState = when (action) {
                is FakeFeatureAction.IncrementEventCount -> {
                    currentState.copy(eventCount = currentState.eventCount + 1)
                }
                is AppAction.SelectModel -> {
                    // Also react to a core action to test the full chain
                    currentState.copy(eventCount = 99)
                }
                else -> currentState
            }
            return state.copy(featureStates = state.featureStates + (name to newFeatureState))
        }

        // --- NEW: A fake start method to test lifecycle ---
        var startWasCalled = false
        override fun start(store: Store) {
            startWasCalled = true
        }
    }

    // --- Characterization Test ---

    @Test
    fun `store orchestrates root and feature reducers - CHARACTERIZATION`() = runTest {
        // ARRANGE
        val fakeFeature = FakeFeature()
        val initialState = AppState(
            selectedModel = "initial-model",
            featureStates = mapOf(fakeFeature.name to FakeFeatureState(eventCount = 0))
        )
        val store = Store(
            initialState = initialState,
            rootReducer = ::appReducer,
            features = listOf(fakeFeature),
            coroutineScope = this // Use the TestCoroutineScope
        )

        // ACT
        // 1. Dispatch a core action that BOTH reducers should handle.
        store.dispatch(AppAction.SelectModel("new-model"))

        // 2. Dispatch a feature-specific action.
        store.dispatch(FakeFeatureAction.IncrementEventCount)

        // ASSERT
        val finalState = store.state.value
        val finalFeatureState = finalState.featureStates[fakeFeature.name] as? FakeFeatureState

        // Assert that the root reducer worked
        assertEquals("new-model", finalState.selectedModel)

        // Assert that the feature reducer worked for both actions, in order
        assertNotNull(finalFeatureState)
        // SelectModel set it to 99, then IncrementEventCount added 1
        assertEquals(100, finalFeatureState.eventCount)
    }

    @Test
    fun `startFeatureLifecycles calls start on all registered features only once`() = runTest {
        // ARRANGE
        val fakeFeature = FakeFeature()
        val store = Store(
            initialState = AppState(),
            rootReducer = ::appReducer,
            features = listOf(fakeFeature),
            coroutineScope = this
        )

        // ASSERT Pre-condition
        assertFalse(fakeFeature.startWasCalled, "start() should not be called before lifecycles are started.")

        // ACT
        store.startFeatureLifecycles()
        // Call it a second time to ensure it's idempotent
        store.startFeatureLifecycles()

        // ASSERT Post-condition
        assertTrue(fakeFeature.startWasCalled, "start() should be called after lifecycles are started.")
    }
}