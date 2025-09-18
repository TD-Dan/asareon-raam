package app.auf.feature.session

import app.auf.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CommandInterpreterTest {

    private val interpreter = CommandInterpreter()
    private val testSessionId = "test-session" // Use a consistent dummy session ID

    @Test
    fun `should return null for non-command blocks`() {
        val block = CodeBlock("kotlin", "val x = 1")
        val result = interpreter.interpret(block, testSessionId)
        assertNull(result, "Should not interpret a regular code block.")
    }

    @Test
    fun `should correctly parse auf_toastMessage command with various argument styles`() {
        val testCases = mapOf(
            "(\"Hello World!\")" to "Hello World!",
            "('This is a test')" to "This is a test",
            "\"Bare quotes\"" to "Bare quotes",
            "'Single quotes'" to "Single quotes",
            "argument123" to "argument123"
        )

        for ((input, expected) in testCases) {
            val block = CodeBlock("auf_toastMessage", input)
            val result = interpreter.interpret(block, testSessionId)
            assertNotNull(result)
            assertIs<ShowToast>(result, "Result for input '$input' should be ShowToast action.")
            assertEquals(expected, result.message)
        }
    }

    @Test
    fun `should correctly parse auf_clearSession command`() {
        val block = CodeBlock("auf_clearSession", "") // Argument is ignored for this command
        val result = interpreter.interpret(block, testSessionId)
        assertNotNull(result)
        assertIs<ClearSession>(result)
        assertEquals(testSessionId, result.sessionId, "The action should carry the correct session ID.")
    }


    @Test
    fun `should correctly parse command with multi-line argument`() {
        val multiLineContent = """
            This is line 1.
            This is line 2.
        """.trimIndent()
        val block = CodeBlock("auf_toastMessage", multiLineContent)
        val result = interpreter.interpret(block, testSessionId)
        assertNotNull(result)
        assertIs<ShowToast>(result)
        assertEquals(multiLineContent, result.message)
    }

    @Test
    fun `should handle empty or whitespace content`() {
        val block = CodeBlock("auf_toastMessage", "   ")
        val result = interpreter.interpret(block, testSessionId)
        assertNotNull(result)
        assertIs<ShowToast>(result)
        assertEquals("", result.message, "Argument should be trimmed to empty.")
    }

    @Test
    fun `should return null for unrecognized auf_ command`() {
        val block = CodeBlock("auf_nonExistentCommand", "someArgument")
        val result = interpreter.interpret(block, testSessionId)
        assertNull(result, "Should return null for an unrecognized command.")
    }
}