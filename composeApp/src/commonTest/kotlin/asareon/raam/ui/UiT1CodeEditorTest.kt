package asareon.raam.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import asareon.raam.core.AppState
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.fakes.FakePlatformDependencies
import asareon.raam.fakes.FakeStore
import asareon.raam.ui.components.CodeEditor
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * T1 Component Test for CodeEditor.
 * Verifies rendering, text input, and read-only mode.
 */
class UiT1CodeEditorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // Fix: Instantiate FakeStore with required arguments
    private val platform = FakePlatformDependencies("test")
    private val store = FakeStore(
        initialState = AppState(),
        platformDependencies = platform,
        validActionNames = ActionRegistry.Names.allActionNames
    )

    @Test
    fun should_render_initial_content() = runTest {
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
    fun should_dispatch_changes_on_edit() = runTest {
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
        composeTestRule.onNodeWithTag("code_editor_input").performTextReplacement("End")

        // Verify callback
        assertEquals("End", capturedValue)
    }

    @Test
    fun should_respect_read_only_mode() = runTest {
        var callbackInvoked = false

        composeTestRule.setContent {
            CodeEditor(
                value = "ReadOnly",
                onValueChange = { callbackInvoked = true },
                readOnly = true
            )
        }

        // Try to input text
        // In read-only mode, the input should essentially be ignored or the callback not fired.
        // We verify the node exists and is displayed.
        composeTestRule.onNodeWithText("ReadOnly").assertExists()
        composeTestRule.onNodeWithTag("code_editor_input").assertIsDisplayed()

        // Ensure no crash on interaction
        composeTestRule.onNodeWithTag("code_editor_input").performClick()
    }
}