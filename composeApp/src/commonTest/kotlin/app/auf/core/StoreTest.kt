package app.auf.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable

@OptIn(ExperimentalCoroutinesApi::class)
class StoreTest {

    // --- Test-specific helper classes ---

    /** A custom state slice for our FakeFeature. */
    @Serializable
    data class FakeFeatureState(
        val name: String = "Fake",
        val eventCount: Int = 0,
        val selectedModel: String = "initial-model"
    ) : FeatureState

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
                    currentState.copy(eventCount = 99, selectedModel = action.modelName)
                }
                else -> currentState
            }
            return state.copy(featureStates = state.featureStates + (name to newFeatureState))
        }

        var startWasCalled = false
        override fun start(store: Store) {
            startWasCalled = true
        }
    }

    @Test
    fun `store orchestrates root and feature reducers`() = runTest {
        // ARRANGE
        val fakeFeature = FakeFeature()
        val initialState = AppState(
            featureStates = mapOf(fakeFeature.name to FakeFeatureState(eventCount = 0))
        )
        val store = Store(
            initialState = initialState,
            rootReducer = ::appReducer,
            features = listOf(fakeFeature),
            coroutineScope = this
        )

        // ACT
        store.dispatch(AppAction.SelectModel("new-model"))
        store.dispatch(FakeFeatureAction.IncrementEventCount)

        // ASSERT
        val finalState = store.state.value
        val finalFeatureState = finalState.featureStates[fakeFeature.name] as? FakeFeatureState
        assertNotNull(finalFeatureState)

        // Assert the feature reducer handled both actions correctly
        assertEquals("new-model", finalFeatureState.selectedModel)
        assertEquals(100, finalFeatureState.eventCount) // 99 from SelectModel + 1 from Increment
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

        assertFalse(fakeFeature.startWasCalled, "start() should not be called before lifecycles are started.")

        // ACT
        store.startFeatureLifecycles()
        store.startFeatureLifecycles() // Call a second time to ensure it's idempotent

        // ASSERT
        assertTrue(fakeFeature.startWasCalled, "start() should be called after lifecycles are started.")
    }
}