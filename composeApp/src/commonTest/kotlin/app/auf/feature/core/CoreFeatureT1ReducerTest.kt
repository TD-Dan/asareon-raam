package app.auf.feature.core

import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.generated.ActionNames
import app.auf.fakes.FakePlatformDependencies
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tier 1 Unit Tests for CoreFeature's reducer.
 *
 * Mandate (P-TEST-001, T1): To test the reducer as a pure function in complete isolation.
 * No TestEnvironment or real Store is used.
 */
class CoreFeatureT1ReducerTest {

    private val feature = CoreFeature(platformDependencies = FakePlatformDependencies("v2-test"))
    private val featureName = feature.name

    private fun createAppState(coreState: CoreState = CoreState()) = AppState(
        featureStates = mapOf(featureName to coreState)
    )

    // --- Lifecycle Reducer Tests ---

    @Test
    fun `reducer transitions from BOOTING to INITIALIZING on SYSTEM_PUBLISH_INITIALIZING`() {
        val initialState = createAppState(CoreState(lifecycle = AppLifecycle.BOOTING))
        val action = Action(ActionNames.SYSTEM_PUBLISH_INITIALIZING)

        val newState = feature.reducer(initialState, action)
        val newCoreState = newState.featureStates[featureName] as CoreState

        assertEquals(AppLifecycle.INITIALIZING, newCoreState.lifecycle)
    }

    @Test
    fun `reducer transitions from INITIALIZING to RUNNING on SYSTEM_PUBLISH_STARTING`() {
        val initialState = createAppState(CoreState(lifecycle = AppLifecycle.INITIALIZING))
        val action = Action(ActionNames.SYSTEM_PUBLISH_STARTING)

        val newState = feature.reducer(initialState, action)
        val newCoreState = newState.featureStates[featureName] as CoreState

        assertEquals(AppLifecycle.RUNNING, newCoreState.lifecycle)
    }

    @Test
    fun `reducer transitions to CLOSING on SYSTEM_PUBLISH_CLOSING`() {
        val initialState = createAppState(CoreState(lifecycle = AppLifecycle.RUNNING))
        val action = Action(ActionNames.SYSTEM_PUBLISH_CLOSING)

        val newState = feature.reducer(initialState, action)
        val newCoreState = newState.featureStates[featureName] as CoreState

        assertEquals(AppLifecycle.CLOSING, newCoreState.lifecycle)
    }

    // --- State Mutation Reducer Tests ---

    @Test
    fun `reducer correctly handles SET_ACTIVE_VIEW`() {
        val initialState = createAppState(CoreState(activeViewKey = "old.key"))
        val payload = buildJsonObject { put("key", "new.key") }
        val action = Action(ActionNames.CORE_SET_ACTIVE_VIEW, payload)

        val newState = feature.reducer(initialState, action)
        val newCoreState = newState.featureStates[featureName] as? CoreState

        assertNotNull(newCoreState)
        assertEquals("new.key", newCoreState.activeViewKey)
    }

    @Test
    fun `reducer correctly handles SHOW_TOAST`() {
        val initialState = createAppState(CoreState(toastMessage = null))
        val payload = buildJsonObject { put("message", "Hello") }
        val action = Action(ActionNames.CORE_SHOW_TOAST, payload)

        val newState = feature.reducer(initialState, action)
        val newCoreState = newState.featureStates[featureName] as? CoreState

        assertNotNull(newCoreState)
        assertEquals("Hello", newCoreState.toastMessage)
    }

    @Test
    fun `reducer correctly handles CLEAR_TOAST`() {
        val initialState = createAppState(CoreState(toastMessage = "Something"))
        val action = Action(ActionNames.CORE_CLEAR_TOAST)

        val newState = feature.reducer(initialState, action)
        val newCoreState = newState.featureStates[featureName] as? CoreState

        assertNotNull(newCoreState)
        assertNull(newCoreState.toastMessage)
    }

    @Test
    fun `reducer ignores unknown actions`() {
        val initialState = createAppState()
        val action = Action("some.other.ACTION")

        val newState = feature.reducer(initialState, action)

        assertEquals(initialState, newState, "State should not change for an unknown action.")
    }
}