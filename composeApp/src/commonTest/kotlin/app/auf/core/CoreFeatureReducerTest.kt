package app.auf.feature.core

import app.auf.core.Action
import app.auf.core.AppState
import app.auf.fakes.FakePlatformDependencies
import kotlin.test.Test
import kotlin.test.assertEquals

class CoreFeatureReducerTest {

    private val feature = CoreFeature(platformDependencies = FakePlatformDependencies("v2-test"))
    private val featureName = feature.name

    private fun createAppState(coreState: CoreState = CoreState()) = AppState(
        featureStates = mapOf(featureName to coreState)
    )

    @Test
    fun `reducer transitions from BOOTING to INITIALIZING on app_INITIALIZING`() {
        // Arrange
        val initialState = createAppState(CoreState(lifecycle = AppLifecycle.BOOTING))
        val action = Action("app.INITIALIZING")

        // Act
        val newState = feature.reducer(initialState, action)
        val newCoreState = newState.featureStates[featureName] as CoreState

        // Assert
        assertEquals(AppLifecycle.INITIALIZING, newCoreState.lifecycle)
    }

    @Test
    fun `reducer transitions from INITIALIZING to RUNNING on app_STARTING`() {
        // Arrange
        val initialState = createAppState(CoreState(lifecycle = AppLifecycle.INITIALIZING))
        val action = Action("app.STARTING")

        // Act
        val newState = feature.reducer(initialState, action)
        val newCoreState = newState.featureStates[featureName] as CoreState

        // Assert
        assertEquals(AppLifecycle.RUNNING, newCoreState.lifecycle)
    }

    @Test
    fun `reducer transitions to CLOSING on app_CLOSING`() {
        // Arrange
        val initialState = createAppState(CoreState(lifecycle = AppLifecycle.RUNNING))
        val action = Action("app.CLOSING")

        // Act
        val newState = feature.reducer(initialState, action)
        val newCoreState = newState.featureStates[featureName] as CoreState

        // Assert
        assertEquals(AppLifecycle.CLOSING, newCoreState.lifecycle)
    }
}