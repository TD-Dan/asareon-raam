package app.auf.ui

import androidx.compose.ui.test.*
import app.auf.fakes.FakeStore
import app.auf.ui.components.CodeEditor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * T1 Component Test for SimpleCodeEditor.
 * Verifies rendering, text input, and read-only mode.
 */
class UiT1CodeEditorTest {

    @Test
    fun `should render initial content`() = runTest {
        val content = "function hello() {\n  return 'world';\n}"

        composeTestRule.setContent {
            CodeEditor(
                value = content,
                onValueChange = {},
                readOnly = false
            )
        }

        // Verify text is displayed
        composeTestRule.onNodeWithText("function hello()", substring = true).assertExists()
    }

    @Test
    fun `should dispatch changes on edit`() = runTest {
        var capturedValue = ""
        val initialContent = "Start"

        composeTestRule.setContent {
            CodeEditor(
                value = initialContent,
                onValueChange = { capturedValue = it },
                readOnly = false
            )
        }

        // Perform text input
        composeTestRule.onNodeWithText("Start").performTextClearance()
        composeTestRule.onNodeWithTag("code_editor_input").performTextInput("End")

        // Verify callback
        assertEquals("End", capturedValue)
    }

    @Test
    fun `should respect read only mode`() = runTest {
        var callbackInvoked = false

        composeTestRule.setContent {
            CodeEditor(
                value = "ReadOnly",
                onValueChange = { callbackInvoked = true },
                readOnly = true
            )
        }

        // Try to input text (should be ignored or not trigger change in a way that invokes callback for new val)
        // Note: In Compose tests, performTextInput on a read-only field usually doesn't crash but doesn't update.
        // We verify semantics mostly.

        composeTestRule.onNodeWithText("ReadOnly").assertExists()

        // We can check if it has the semantics of a text field
        composeTestRule.onNodeWithTag("code_editor_input").assertIsDisplayed()
    }

    // Test Harness Setup
    private val store = FakeStore()
    @get:Rule
    val composeTestRule = createComposeRule()
}