package app.auf.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReducerTest {

    @Test
    fun `appReducer correctly handles ShowToast action`() {
        // Arrange
        val initialState = AppState(toastMessage = null)
        val action = AppAction.ShowToast("Hello")

        // Act
        val newState = appReducer(initialState, action)

        // Assert
        assertEquals("Hello", newState.toastMessage)
    }

    @Test
    fun `appReducer correctly handles ClearToast action`() {
        // Arrange
        val initialState = AppState(toastMessage = "Something")
        val action = AppAction.ClearToast

        // Act
        val newState = appReducer(initialState, action)

        // Assert
        assertNull(newState.toastMessage)
    }

    @Test
    fun `appReducer correctly handles SetActiveView action`() {
        // Arrange
        val initialState = AppState(activeViewKey = "old.key")
        val action = AppAction.SetActiveView("new.key")

        // Act
        val newState = appReducer(initialState, action)

        // Assert
        assertEquals("new.key", newState.activeViewKey)
    }

    @Test
    fun `appReducer ignores unknown and feature-specific actions`() {
        // Arrange
        val initialState = AppState(toastMessage = "Unchanged")
        data object SomeFeatureAction : AppAction // A fake feature action

        // Act
        val newState = appReducer(initialState, SomeFeatureAction)

        // Assert
        // The entire state object should be identical to the initial one.
        assertEquals(initialState, newState)
    }
}