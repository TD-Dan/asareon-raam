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

    // --- Test Doubles: Self-contained fakes to simulate real features ---

    // --- WidgetFeature ---
    @Serializable
    data class WidgetState(val count: Int = 0) : FeatureState
    data object IncrementWidgetCount : AppAction
    class WidgetFeature : Feature {
        override val name: String = "WidgetFeature"
        var startWasCalled = false
        override fun start(store: Store) { startWasCalled = true }
        override fun reducer(state: AppState, action: AppAction): AppState {
            if (action is IncrementWidgetCount) {
                val currentFeatureState = state.featureStates[name] as? WidgetState ?: WidgetState()
                val newFeatureState = currentFeatureState.copy(count = currentFeatureState.count + 1)
                return state.copy(featureStates = state.featureStates + (name to newFeatureState))
            }
            return state
        }
    }

    // --- GadgetFeature ---
    @Serializable
    data class GadgetState(val text: String = "initial") : FeatureState
    data class SetGadgetText(val newText: String) : AppAction
    class GadgetFeature : Feature {
        override val name: String = "GadgetFeature"
        var startWasCalled = false
        override fun start(store: Store) { startWasCalled = true }
        override fun reducer(state: AppState, action: AppAction): AppState {
            if (action is SetGadgetText) {
                val currentFeatureState = state.featureStates[name] as? GadgetState ?: GadgetState()
                val newFeatureState = currentFeatureState.copy(text = action.newText)
                return state.copy(featureStates = state.featureStates + (name to newFeatureState))
            }
            return state
        }
    }

    @Test
    fun `store correctly orchestrates core and multiple feature reducers while maintaining state isolation`() = runTest {
        // --- ARRANGE ---
        val widgetFeature = WidgetFeature()
        val gadgetFeature = GadgetFeature()
        val initialState = AppState(
            featureStates = mapOf(
                widgetFeature.name to WidgetState(count = 5),
                gadgetFeature.name to GadgetState(text = "initial")
            )
        )
        val store = Store(
            initialState = initialState,
            rootReducer = ::appReducer,
            features = listOf(widgetFeature, gadgetFeature),
            coroutineScope = this
        )

        // --- ACT ---
        store.dispatch(IncrementWidgetCount)              // A widget-specific action
        store.dispatch(SetGadgetText("new text"))         // A gadget-specific action

        // --- ASSERT ---
        val finalState = store.state.value

        // 1. Assert Widget Integrity
        val finalWidgetState = finalState.featureStates[widgetFeature.name] as? WidgetState
        assertNotNull(finalWidgetState)
        assertEquals(6, finalWidgetState.count, "WidgetFeature reducer should have incremented the count.")

        // 2. Assert Gadget Integrity
        val finalGadgetState = finalState.featureStates[gadgetFeature.name] as? GadgetState
        assertNotNull(finalGadgetState)
        assertEquals("new text", finalGadgetState.text, "GadgetFeature reducer should have set the text.")

        // 3. Assert State Isolation (Crucial)
        assertEquals("initial", (initialState.featureStates[gadgetFeature.name] as GadgetState).text, "Gadget initial text should be unchanged after WidgetAction.")
        assertEquals(5, (initialState.featureStates[widgetFeature.name] as WidgetState).count, "Widget initial count should be unchanged after GadgetAction.")
    }

    @Test
    fun `startFeatureLifecycles calls start on all registered features exactly once`() = runTest {
        // --- ARRANGE ---
        val widgetFeature = WidgetFeature()
        val gadgetFeature = GadgetFeature()
        val store = Store(
            initialState = AppState(),
            rootReducer = ::appReducer,
            features = listOf(widgetFeature, gadgetFeature),
            coroutineScope = this
        )

        assertFalse(widgetFeature.startWasCalled, "Widget start() should not be called before lifecycles are started.")
        assertFalse(gadgetFeature.startWasCalled, "Gadget start() should not be called before lifecycles are started.")

        // --- ACT ---
        store.startFeatureLifecycles()
        store.startFeatureLifecycles() // Call a second time to ensure it's idempotent

        // --- ASSERT ---
        assertTrue(widgetFeature.startWasCalled, "Widget start() should be called after lifecycles are started.")
        assertTrue(gadgetFeature.startWasCalled, "Gadget start() should be called after lifecycles are started.")
    }
}