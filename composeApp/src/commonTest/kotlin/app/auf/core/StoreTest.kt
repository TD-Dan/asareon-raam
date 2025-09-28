package app.auf.core

import app.auf.fakes.CapturedLog
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreFeature
import app.auf.feature.core.CoreState
import app.auf.util.LogLevel
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
import kotlin.test.assertNotEquals

@OptIn(ExperimentalCoroutinesApi::class)
class StoreTest {

    private val testAppVersion = "2.0.0-test"

    // --- Test Doubles: Self-contained fakes, co-located within the test file. ---
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
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val initialState = AppState(
            featureStates = mapOf(
                coreFeature.name to CoreState(),
                widgetFeature.name to WidgetState(count = 5),
                gadgetFeature.name to GadgetState(text = "initial")
            )
        )
        val store = Store(
            initialState = initialState,
            features = listOf(coreFeature, widgetFeature, gadgetFeature),
            platformDependencies = fakePlatform
        )
        val startAppAction = Action(name = "app.STARTING")
        val incrementWidgetAction = Action(name = "widget.INCREMENT")
        val setGadgetTextAction = Action(
            name = "gadget.SET_TEXT",
            payload = buildJsonObject { put("newText", "new value") }
        )

        // --- ACT ---
        store.dispatch(startAppAction)
        store.dispatch(incrementWidgetAction)
        store.dispatch(setGadgetTextAction)

        // --- ASSERT ---
        val finalState = store.state.value

        val finalCoreState = finalState.featureStates[coreFeature.name] as? CoreState
        assertNotNull(finalCoreState)
        assertEquals(AppLifecycle.RUNNING, finalCoreState.lifecycle)

        val finalWidgetState = finalState.featureStates[widgetFeature.name] as? WidgetState
        assertNotNull(finalWidgetState)
        assertEquals(6, finalWidgetState.count)

        val finalGadgetState = finalState.featureStates[gadgetFeature.name] as? GadgetState
        assertNotNull(finalGadgetState)
        assertEquals("new value", finalGadgetState.text)
    }

    @Test
    fun `store ignores action and logs error when dispatched before app STARTING`() = runTest {
        // --- ARRANGE ---
        val coreFeature = CoreFeature()
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val initialState = AppState(featureStates = mapOf(coreFeature.name to CoreState()))
        val store = Store(
            initialState = initialState,
            features = listOf(coreFeature),
            platformDependencies = fakePlatform
        )
        val illegalAction = Action("widget.INCREMENT")

        // --- ACT ---
        store.dispatch(illegalAction)

        // --- ASSERT ---
        val finalState = store.state.value
        assertEquals(initialState, finalState, "State must not change when an action is dispatched before start.")

        assertNotEquals(0, fakePlatform.capturedLogs.size, "An error should have been logged.")
        val log = fakePlatform.capturedLogs.last()
        assertEquals(LogLevel.ERROR, log.level)
        assertEquals("Store", log.tag)
        assertTrue(log.message.contains("Action 'widget.INCREMENT' dispatched before app started"), "The log message is incorrect.")
    }

    @Test
    fun `initFeatureLifecycles calls init on all registered features exactly once`() = runTest {
        // --- ARRANGE ---
        val widgetFeature = WidgetFeature()
        val gadgetFeature = GadgetFeature()
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val store = Store(
            initialState = AppState(),
            features = listOf(widgetFeature, gadgetFeature),
            platformDependencies = fakePlatform
        )

        assertFalse(widgetFeature.initWasCalled)
        assertFalse(gadgetFeature.initWasCalled)

        // --- ACT ---
        store.initFeatureLifecycles()
        store.initFeatureLifecycles() // Idempotency check

        // --- ASSERT ---
        assertTrue(widgetFeature.initWasCalled)
        assertTrue(gadgetFeature.initWasCalled)
    }
}