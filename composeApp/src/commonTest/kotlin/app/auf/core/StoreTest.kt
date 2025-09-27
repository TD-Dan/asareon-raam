package app.auf.core

import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreFeature
import app.auf.feature.core.CoreState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalCoroutinesApi::class)
class StoreTest {

    // --- Test Doubles: Self-contained fakes to simulate real features ---

    // --- WidgetFeature ---
    @Serializable
    data class WidgetState(val count: Int = 0) : FeatureState
    class WidgetFeature : Feature {
        override val name: String = "WidgetFeature"
        override val composableProvider: Feature.ComposableProvider? = null
        var initWasCalled = false
        override fun init(store: Store) { initWasCalled = true }
        override fun reducer(state: AppState, action: Action): AppState {
            if (action.name == "widget.INCREMENT") {
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
    class GadgetFeature : Feature {
        override val name: String = "GadgetFeature"
        override val composableProvider: Feature.ComposableProvider? = null
        var initWasCalled = false
        override fun init(store: Store) { initWasCalled = true }
        override fun reducer(state: AppState, action: Action): AppState {
            if (action.name == "gadget.SET_TEXT") {
                val newText = action.payload?.get("newText")?.toString()?.trim('"') ?: "error"
                val currentFeatureState = state.featureStates[name] as? GadgetState ?: GadgetState()
                val newFeatureState = currentFeatureState.copy(text = newText)
                return state.copy(featureStates = state.featureStates + (name to newFeatureState))
            }
            return state
        }
    }

    @Test
    fun `store correctly orchestrates core and multiple feature reducers while maintaining state isolation`() = runTest {
        // --- ARRANGE ---
        val coreFeature = CoreFeature()
        val widgetFeature = WidgetFeature()
        val gadgetFeature = GadgetFeature()
        val initialState = AppState(
            featureStates = mapOf(
                coreFeature.name to CoreState(),
                widgetFeature.name to WidgetState(count = 5),
                gadgetFeature.name to GadgetState(text = "initial")
            )
        )
        val store = Store(
            initialState = initialState,
            features = listOf(coreFeature, widgetFeature, gadgetFeature)
        )
        val startAppAction = Action(name = "app.STARTING")
        val incrementWidgetAction = Action(name = "widget.INCREMENT")
        val setGadgetTextAction = Action(
            name = "gadget.SET_TEXT",
            payload = buildJsonObject { put("newText", "new value") }
        )

        // --- ACT ---
        // First, we MUST start the app to allow other actions to be processed.
        store.dispatch(startAppAction)
        store.dispatch(incrementWidgetAction)
        store.dispatch(setGadgetTextAction)

        // --- ASSERT ---
        val finalState = store.state.value

        // 0. Assert App Lifecycle
        val finalCoreState = finalState.featureStates[coreFeature.name] as? CoreState
        assertNotNull(finalCoreState)
        assertEquals(AppLifecycle.RUNNING, finalCoreState.lifecycle, "The app lifecycle should be RUNNING.")

        // 1. Assert Widget Integrity
        val finalWidgetState = finalState.featureStates[widgetFeature.name] as? WidgetState
        assertNotNull(finalWidgetState)
        assertEquals(6, finalWidgetState.count, "WidgetFeature reducer should have incremented the count.")

        // 2. Assert Gadget Integrity
        val finalGadgetState = finalState.featureStates[gadgetFeature.name] as? GadgetState
        assertNotNull(finalGadgetState)
        assertEquals("new value", finalGadgetState.text, "GadgetFeature reducer should have set the text.")
    }

    @Test
    fun `initFeatureLifecycles calls init on all registered features exactly once`() = runTest {
        // --- ARRANGE ---
        val widgetFeature = WidgetFeature()
        val gadgetFeature = GadgetFeature()
        val store = Store(
            initialState = AppState(),
            features = listOf(widgetFeature, gadgetFeature)
        )

        assertFalse(widgetFeature.initWasCalled, "Widget init() should not be called before lifecycles are started.")
        assertFalse(gadgetFeature.initWasCalled, "Gadget init() should not be called before lifecycles are started.")

        // --- ACT ---
        store.initFeatureLifecycles()
        store.initFeatureLifecycles() // Call a second time to ensure it's idempotent

        // --- ASSERT ---
        assertTrue(widgetFeature.initWasCalled, "Widget init() should be called after lifecycles are started.")
        assertTrue(gadgetFeature.initWasCalled, "Gadget init() should be called after lifecycles are started.")
    }
}