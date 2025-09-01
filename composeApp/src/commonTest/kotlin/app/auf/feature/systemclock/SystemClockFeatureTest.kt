package app.auf.feature.systemclock

import app.auf.core.AppAction
import app.auf.core.AppState
import app.auf.fakes.FakeSessionManager
import app.auf.fakes.FakeStore
import app.auf.model.SettingValue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SystemClockFeatureTest {

    private fun getClockState(state: AppState, feature: SystemClockFeature): SystemClockState {
        val featureState = state.featureStates[feature.name]
        assertNotNull(featureState, "Feature state slice should exist.")
        return featureState as SystemClockState
    }

    @Test
    fun `reducer handles ClockAction Start`() = runTest {
        val feature = SystemClockFeature(this)
        val initialState = AppState(featureStates = mapOf(feature.name to SystemClockState(isEnabled = false)))
        val newState = feature.reducer(initialState, ClockAction.Start)
        assertTrue(getClockState(newState, feature).isEnabled)
    }

    @Test
    fun `reducer handles ClockAction Stop`() = runTest {
        val feature = SystemClockFeature(this)
        val initialState = AppState(featureStates = mapOf(feature.name to SystemClockState(isEnabled = true)))
        val newState = feature.reducer(initialState, ClockAction.Stop)
        assertFalse(getClockState(newState, feature).isEnabled)
    }

    @Test
    fun `reducer handles ClockAction SetInterval`() = runTest {
        val feature = SystemClockFeature(this)
        val initialState = AppState(featureStates = mapOf(feature.name to SystemClockState(intervalMillis = 100L)))
        val newState = feature.reducer(initialState, ClockAction.SetInterval(5000L))
        assertEquals(5000L, getClockState(newState, feature).intervalMillis)
    }

    @Test
    fun `reducer handles UpdateSetting for isEnabled`() = runTest {
        val feature = SystemClockFeature(this)
        val initialState = AppState(featureStates = mapOf(feature.name to SystemClockState(isEnabled = false)))
        val action = AppAction.UpdateSetting(SettingValue("clock.isEnabled", true))
        val newState = feature.reducer(initialState, action)
        assertTrue(getClockState(newState, feature).isEnabled)
    }

    @Test
    fun `reducer handles UpdateSetting for intervalMillis`() = runTest {
        val feature = SystemClockFeature(this)
        val initialState = AppState(featureStates = mapOf(feature.name to SystemClockState(intervalMillis = 100L)))
        val action = AppAction.UpdateSetting(SettingValue("clock.intervalMillis", 9999L))
        val newState = feature.reducer(initialState, action)
        assertEquals(9999L, getClockState(newState, feature).intervalMillis)
    }

    @Test
    fun `reducer ignores irrelevant actions`() = runTest {
        val feature = SystemClockFeature(this)
        val initialState = AppState(featureStates = mapOf(feature.name to SystemClockState()))
        val newState = feature.reducer(initialState, AppAction.AddUserMessage("test")) // Using an irrelevant action
        assertEquals(initialState, newState)
    }

    @Test
    fun `reducer correctly initializes default state if not present`() = runTest {
        val feature = SystemClockFeature(this)
        val initialState = AppState(featureStates = emptyMap())
        val newState = feature.reducer(initialState, ClockAction.Start)
        val clockState = getClockState(newState, feature)
        assertTrue(clockState.isEnabled)
        assertEquals(300_000L, clockState.intervalMillis)
    }

    // --- AUTONOMOUS BEHAVIOR TESTS ---

    @Test
    fun `start lifecycle method dispatches Tick when enabled`() = runTest {
        val feature = SystemClockFeature(backgroundScope)
        val initialState = AppState(featureStates = mapOf(feature.name to SystemClockState(isEnabled = true, intervalMillis = 1000L)))
        val store = FakeStore(initialState, this, FakeSessionManager(), listOf(feature))

        feature.start(store)
        advanceTimeBy(1001)

        val tickDispatched = store.dispatchedActions.any { it is ClockAction.Tick }
        assertTrue(tickDispatched, "A Tick action should have been dispatched.")
    }

    @Test
    fun `start lifecycle method does NOT dispatch Tick when disabled`() = runTest {
        val feature = SystemClockFeature(backgroundScope)
        val initialState = AppState(featureStates = mapOf(feature.name to SystemClockState(isEnabled = false, intervalMillis = 1000L)))
        val store = FakeStore(initialState, this, FakeSessionManager(), listOf(feature))

        feature.start(store)
        advanceTimeBy(1001)

        val tickDispatched = store.dispatchedActions.any { it is ClockAction.Tick }
        assertFalse(tickDispatched, "No Tick action should be dispatched when the feature is disabled.")
    }

    @Test
    fun `start lifecycle method does NOT dispatch Tick when gateway is busy`() = runTest {
        val feature = SystemClockFeature(backgroundScope)
        val initialState = AppState(
            isProcessing = true,
            featureStates = mapOf(feature.name to SystemClockState(isEnabled = true, intervalMillis = 1000L))
        )
        val store = FakeStore(initialState, this, FakeSessionManager(), listOf(feature))

        feature.start(store)
        advanceTimeBy(1001)

        val tickDispatched = store.dispatchedActions.any { it is ClockAction.Tick }
        assertFalse(tickDispatched, "No Tick action should be dispatched when the gateway is busy.")
    }
}