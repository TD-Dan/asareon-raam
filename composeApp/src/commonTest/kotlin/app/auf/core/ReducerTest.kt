package app.auf.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReducerTest {

    @Test
    fun `appReducer correctly handles ShowToast action`() {
        // Arrange
        val initialState = AppState(toastMessage = null)
        val action = ShowToast("Hello")

        // Act
        val newState = appReducer(initialState, action)

        // Assert
        assertEquals("Hello", newState.toastMessage)
    }

    @Test
    fun `appReducer correctly handles ClearToast action`() {
        // Arrange
        val initialState = AppState(toastMessage = "Something")
        val action = ClearToast

        // Act
        val newState = appReducer(initialState, action)

        // Assert
        assertNull(newState.toastMessage)
    }

    @Test
    fun `appReducer correctly handles SetActiveView action`() {
        // Arrange
        val initialState = AppState(activeViewKey = "old.key")
        val action = SetActiveView("new.key")

        // Act
        val newState = appReducer(initialState, action)

        // Assert
        assertEquals("new.key", newState.activeViewKey)
    }

    @Test
    fun `appReducer ignores unknown and feature-specific actions`() {
        // Arrange
        val initialState = AppState(toastMessage = "Unchanged")
        // FIX: Use an anonymous object that implements the Command interface to satisfy the sealed hierarchy.
        val someFeatureAction = object : Command {}

        // Act
        val newState = appReducer(initialState, someFeatureAction)

        // Assert
        // The entire state object should be identical to the initial one.
        assertEquals(initialState, newState)
    }
}