package feature.core

import app.auf.core.Action
import app.auf.core.AppState
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.core.CoreFeature
import app.auf.feature.core.CoreState
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CoreFeatureReducerTest {

    private val testAppVersion = "2.0.0-test"
    private val coreFeature = CoreFeature(FakePlatformDependencies(testAppVersion))
    private val featureName = coreFeature.name

    @Test
    fun `reducer correctly handles SET_ACTIVE_VIEW`() {
        // Arrange
        val initialState = AppState(featureStates = mapOf(featureName to CoreState(activeViewKey = "old.key")))
        val payload = buildJsonObject { put("key", "new.key") }
        val action = Action("core.SET_ACTIVE_VIEW", payload)

        // Act
        val newState = coreFeature.reducer(initialState, action)
        val newCoreState = newState.featureStates[featureName] as? CoreState

        // Assert
        assertNotNull(newCoreState)
        assertEquals("new.key", newCoreState.activeViewKey)
    }

    @Test
    fun `reducer correctly handles SHOW_TOAST`() {
        // Arrange
        val initialState = AppState(featureStates = mapOf(featureName to CoreState(toastMessage = null)))
        val payload = buildJsonObject { put("message", "Hello") }
        val action = Action("core.SHOW_TOAST", payload)

        // Act
        val newState = coreFeature.reducer(initialState, action)
        val newCoreState = newState.featureStates[featureName] as? CoreState

        // Assert
        assertNotNull(newCoreState)
        assertEquals("Hello", newCoreState.toastMessage)
    }

    @Test
    fun `reducer correctly handles CLEAR_TOAST`() {
        // Arrange
        val initialState = AppState(featureStates = mapOf(featureName to CoreState(toastMessage = "Something")))
        val action = Action("core.CLEAR_TOAST")

        // Act
        val newState = coreFeature.reducer(initialState, action)
        val newCoreState = newState.featureStates[featureName] as? CoreState

        // Assert
        assertNotNull(newCoreState)
        assertNull(newCoreState.toastMessage)
    }

    @Test
    fun `reducer ignores unknown actions`() {
        // Arrange
        val initialState = AppState(featureStates = mapOf(featureName to CoreState()))
        val action = Action("some.other.ACTION")

        // Act
        val newState = coreFeature.reducer(initialState, action)

        // Assert
        assertEquals(initialState, newState, "State should not change for an unknown action.")
    }
}