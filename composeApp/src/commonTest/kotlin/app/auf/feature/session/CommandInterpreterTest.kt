package app.auf.feature.session

import app.auf.core.CodeBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CommandInterpreterTest {

    private val interpreter = CommandInterpreter()

    @Test
    fun `should return null for non-command blocks`() {
        val block = CodeBlock("kotlin", "val x = 1")
        val result = interpreter.interpret(block)
        assertNull(result, "Should not interpret a regular code block.")
    }

    @Test
    fun `should correctly parse command with double-quoted, parenthesized argument`() {
        val block = CodeBlock("auf_toastMessage", "(\"Hello World!\")")
        val result = interpreter.interpret(block)
        assertNotNull(result)
        assertEquals("auf_toastMessage", result.command)
        assertEquals("Hello World!", result.argument)
    }

    @Test
    fun `should correctly parse command with single-quoted, parenthesized argument`() {
        val block = CodeBlock("auf_toastMessage", "('This is a test')")
        val result = interpreter.interpret(block)
        assertNotNull(result)
        assertEquals("auf_toastMessage", result.command)
        assertEquals("This is a test", result.argument)
    }

    @Test
    fun `should correctly parse command with double-quoted argument`() {
        val block = CodeBlock("auf_toastMessage", "\"Bare quotes\"")
        val result = interpreter.interpret(block)
        assertNotNull(result)
        assertEquals("auf_toastMessage", result.command)
        assertEquals("Bare quotes", result.argument)
    }

    @Test
    fun `should correctly parse command with single-quoted argument`() {
        val block = CodeBlock("auf_toastMessage", "'Single quotes'")
        val result = interpreter.interpret(block)
        assertNotNull(result)
        assertEquals("auf_toastMessage", result.command)
        assertEquals("Single quotes", result.argument)
    }

    @Test
    fun `should correctly parse command with bare, unquoted argument`() {
        val block = CodeBlock("auf_someCommand", "argument123")
        val result = interpreter.interpret(block)
        assertNotNull(result)
        assertEquals("auf_someCommand", result.command)
        assertEquals("argument123", result.argument)
    }

    @Test
    fun `should correctly parse command with multi-line argument`() {
        val multiLineContent = """
            This is line 1.
            This is line 2.
        """.trimIndent()
        val block = CodeBlock("auf_longMessage", multiLineContent)
        val result = interpreter.interpret(block)
        assertNotNull(result)
        assertEquals("auf_longMessage", result.command)
        assertEquals(multiLineContent, result.argument)
    }

    @Test
    fun `should handle empty or whitespace content`() {
        val block = CodeBlock("auf_doSomething", "   ")
        val result = interpreter.interpret(block)
        assertNotNull(result)
        assertEquals("auf_doSomething", result.command)
        assertEquals("", result.argument, "Argument should be trimmed to empty.")
    }
}