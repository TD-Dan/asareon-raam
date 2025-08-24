package app.auf.service

import app.auf.core.ActionBlock
import app.auf.core.FileContentBlock
import app.auf.core.ParseErrorBlock
import app.auf.core.TextBlock
import app.auf.model.Parameter
import app.auf.model.ToolDefinition
import app.auf.util.JsonProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AufTextParserTest {

    private fun setupTestEnvironment(): AufTextParser {
        val jsonParser = JsonProvider.appJson
        val toolRegistry = listOf(
            ToolDefinition(
                name = "Atomic Change Manifest",
                command = "ACTION_MANIFEST",
                description = "",
                parameters = emptyList(),
                expectsPayload = true,
                usage = ""
            ),
            ToolDefinition(
                name = "File Content View",
                command = "FILE_VIEW",
                description = "",
                parameters = listOf(
                    Parameter(name = "path", type = "String", isRequired = true),
                    Parameter(name = "language", type = "String", isRequired = false, defaultValue = "plaintext")
                ),
                expectsPayload = true,
                usage = ""
            ),
            ToolDefinition("App Request", "APP_REQUEST", "", emptyList(), true, ""),
            ToolDefinition("State Anchor", "STATE_ANCHOR", "", emptyList(), true, "")
        )
        return AufTextParser(jsonParser, toolRegistry)
    }

    // --- Existing Passing Tests (No Changes) ---

    @Test
    fun `should correctly parse text and a valid action block`() {
        val parser = setupTestEnvironment()
        val rawResponse = """
            Here is the plan.
[AUF_ACTION_MANIFEST]
[
    {
        "type": "CreateFile",
        "filePath": "test.txt",
        "content": "Hello",
        "summary": "Create"
    }
]
[/AUF_ACTION_MANIFEST]
Proceed?
        """.trimIndent()
        val result = parser.parse(rawResponse)
        assertEquals(3, result.size)
        assertIs<ActionBlock>(result[1])
        assertIs<TextBlock>(result[0])
        assertIs<TextBlock>(result[2])
    }

    @Test
    fun `should return a single text block if no tags are present`() {
        val parser = setupTestEnvironment()
        val rawResponse = "This is just a simple sentence."
        val result = parser.parse(rawResponse)
        assertEquals(1, result.size)
        assertIs<TextBlock>(result[0])
    }

    @Test
    fun `should create a ParseErrorBlock for an unterminated tag`() {
        val parser = setupTestEnvironment()
        val rawResponse = """
            Here we go...
[AUF_ACTION_MANIFEST]Some content that never gets closed.
        """.trimIndent()
        val result = parser.parse(rawResponse)
        assertEquals(2, result.size)
        assertIs<ParseErrorBlock>(result[1])
        val errorBlock = result[1] as ParseErrorBlock
        val expectedErrorMessageContent = "Closing tag '[/AUF_ACTION_MANIFEST]' not found."
        assertTrue(
            errorBlock.errorMessage == expectedErrorMessageContent,
            "Error message should be specific. Was: '${errorBlock.errorMessage}'"
        )
    }

    @Test
    fun `should treat nested tags as part of the payload`() {
        val parser = setupTestEnvironment()
        val rawResponse = """
[AUF_FILE_VIEW(path="outer.txt")]
Outer content.
            [AUF_ACTION_MANIFEST]
            [{"type":"CreateFile","filePath":"inner.txt","content":"...","summary":"Inner"}]
            [/AUF_ACTION_MANIFEST]
[/AUF_FILE_VIEW]
""".trimIndent()
        val result = parser.parse(rawResponse)
        assertEquals(1, result.size, "Expected a single FileContentBlock")
        assertIs<FileContentBlock>(result[0])
        val fileBlock = result[0] as FileContentBlock
        assertTrue(fileBlock.content.contains("[AUF_ACTION_MANIFEST]"))
    }

    @Test
    fun `should create a ParseErrorBlock for malformed JSON`() {
        val parser = setupTestEnvironment()
        val rawResponse = """
            [AUF_ACTION_MANIFEST]
            [{"type":"CreateFile", "summary":"Forgot a quote}]
            [/AUF_ACTION_MANIFEST]
        """.trimIndent()
        val result = parser.parse(rawResponse)
        assertEquals(1, result.size)
        assertIs<ParseErrorBlock>(result[0])
        assertTrue((result[0] as ParseErrorBlock).errorMessage.contains("deserialization error"))
    }

    // --- Parameter Parsing Tests ---

    @Test
    fun `should parse a single named parameter correctly`() {
        val parser = setupTestEnvironment()
        val rawResponse = """
[AUF_FILE_VIEW(path="test.kt")]
fun main() {}
[/AUF_FILE_VIEW]
""".trimIndent()
        val result = parser.parse(rawResponse)
        assertEquals(1, result.size)
        assertIs<FileContentBlock>(result[0])
        val block = result[0] as FileContentBlock
        assertEquals("test.kt", block.fileName)
        assertEquals("plaintext", block.language) // Expecting default value
    }

    @Test
    fun `should parse multiple named parameters with varied whitespace`() {
        val parser = setupTestEnvironment()
        val rawResponse = """
[AUF_FILE_VIEW(path="test.kt", language="kotlin")]
fun main() {}
[/AUF_FILE_VIEW]
""".trimIndent()
        val result = parser.parse(rawResponse)
        assertEquals(1, result.size)
        assertIs<FileContentBlock>(result[0])
        val block = result[0] as FileContentBlock
        assertEquals("test.kt", block.fileName)
        assertEquals("kotlin", block.language)
    }

    @Test
    fun `should create ParseErrorBlock for malformed parameters`() {
        val parser = setupTestEnvironment()
        // Missing closing quote on the path value
        val rawResponse = """
            [AUF_FILE_VIEW(path="test.kt, language="kotlin")]
            fun main() {}
            [/AUF_FILE_VIEW]
        """.trimIndent()
        val result = parser.parse(rawResponse)

        assertEquals(2, result.size, "Should produce an error AND the leftover text.")

        assertIs<ParseErrorBlock>(result[0])
        val errorBlock = result[0] as ParseErrorBlock
        assertTrue(errorBlock.errorMessage.contains("Failed to parse parameters"))

        assertIs<TextBlock>(result[1])
        val textBlock = result[1] as TextBlock
        // The leftover text should contain the payload and the now-unmatched closing tag
        assertTrue(textBlock.text.contains("fun main() {}"))
        assertTrue(textBlock.text.contains("[/AUF_FILE_VIEW]"))
    }
}