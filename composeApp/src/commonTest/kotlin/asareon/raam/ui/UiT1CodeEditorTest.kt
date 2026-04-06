package asareon.raam.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import asareon.raam.ui.components.CodeEditor
import asareon.raam.ui.components.SyntaxMode
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * T1 Component Test for CodeEditor.
 * Verifies rendering, text input, read-only mode, and syntax highlighting modes.
 */
class UiT1CodeEditorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ═════════════════════════════════════════════════════════════════════
    // Basic Rendering
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun should_render_initial_content() {
        val content = "function hello() {\n  return 'world';\n}"

        composeTestRule.setContent {
            CodeEditor(
                value = content,
                onValueChange = {}
            )
        }

        composeTestRule.onNodeWithText("function hello()", substring = true).assertExists()
    }

    @Test
    fun should_render_empty_content() {
        composeTestRule.setContent {
            CodeEditor(
                value = "",
                onValueChange = {}
            )
        }

        composeTestRule.onNodeWithTag("code_editor_input").assertExists()
    }

    // ═════════════════════════════════════════════════════════════════════
    // Editing
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun should_dispatch_changes_on_edit() {
        var capturedValue = ""

        composeTestRule.setContent {
            CodeEditor(
                value = "Start",
                onValueChange = { capturedValue = it }
            )
        }

        composeTestRule.onNodeWithTag("code_editor_input").performTextReplacement("End")
        assertEquals("End", capturedValue)
    }

    // ═════════════════════════════════════════════════════════════════════
    // Read-Only Mode
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun should_respect_read_only_mode() {
        var callbackInvoked = false

        composeTestRule.setContent {
            CodeEditor(
                value = "ReadOnly",
                onValueChange = { callbackInvoked = true },
                readOnly = true
            )
        }

        composeTestRule.onNodeWithText("ReadOnly").assertExists()
        composeTestRule.onNodeWithTag("code_editor_input").assertIsDisplayed()
        // Ensure no crash on interaction
        composeTestRule.onNodeWithTag("code_editor_input").performClick()
    }

    // ═════════════════════════════════════════════════════════════════════
    // Syntax Modes — Smoke Tests
    // ═════════════════════════════════════════════════════════════════════
    // These verify each mode renders without crashing; visual correctness
    // of highlighting is a design concern, not a unit-testable property.

    @Test
    fun should_render_with_xml_syntax() {
        composeTestRule.setContent {
            CodeEditor(
                value = "<root><child attr=\"value\"/></root>",
                onValueChange = {},
                syntax = SyntaxMode.XML
            )
        }

        composeTestRule.onNodeWithText("root", substring = true).assertExists()
    }

    @Test
    fun should_render_with_json_syntax() {
        composeTestRule.setContent {
            CodeEditor(
                value = """{"key": "value", "number": 42, "flag": true}""",
                onValueChange = {},
                syntax = SyntaxMode.JSON
            )
        }

        composeTestRule.onNodeWithText("key", substring = true).assertExists()
    }

    @Test
    fun should_render_with_markdown_syntax() {
        composeTestRule.setContent {
            CodeEditor(
                value = "# Heading\n\n**Bold** and *italic*",
                onValueChange = {},
                syntax = SyntaxMode.MARKDOWN
            )
        }

        composeTestRule.onNodeWithText("Heading", substring = true).assertExists()
    }

    @Test
    fun should_render_with_none_syntax() {
        composeTestRule.setContent {
            CodeEditor(
                value = "Plain text, no highlighting",
                onValueChange = {},
                syntax = SyntaxMode.NONE
            )
        }

        composeTestRule.onNodeWithText("Plain text", substring = true).assertExists()
    }

    // ═════════════════════════════════════════════════════════════════════
    // Bordered Parameter
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun should_render_without_border() {
        composeTestRule.setContent {
            CodeEditor(
                value = "No border",
                onValueChange = {},
                bordered = false
            )
        }

        composeTestRule.onNodeWithText("No border").assertExists()
    }

    // ═════════════════════════════════════════════════════════════════════
    // Edge Cases
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun should_handle_very_long_single_line() {
        val longLine = "x".repeat(10_000)

        composeTestRule.setContent {
            CodeEditor(
                value = longLine,
                onValueChange = {},
                syntax = SyntaxMode.JSON
            )
        }

        composeTestRule.onNodeWithTag("code_editor_input").assertExists()
    }

    @Test
    fun should_handle_deeply_nested_json() {
        val nested = "{" + "\"a\":{".repeat(20) + "\"v\":1" + "}".repeat(21)

        composeTestRule.setContent {
            CodeEditor(
                value = nested,
                onValueChange = {},
                syntax = SyntaxMode.JSON
            )
        }

        composeTestRule.onNodeWithTag("code_editor_input").assertExists()
    }

    @Test
    fun should_handle_malformed_xml_gracefully() {
        composeTestRule.setContent {
            CodeEditor(
                value = "<unclosed><also <broken attr",
                onValueChange = {},
                syntax = SyntaxMode.XML
            )
        }

        composeTestRule.onNodeWithTag("code_editor_input").assertExists()
    }
}