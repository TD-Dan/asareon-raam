// --- FILE: composeApp/src/commonTest/kotlin/app/auf/service/AufTextParserTest.kt ---
package app.auf.service

import app.auf.core.ActionBlock
import app.auf.core.ParseErrorBlock
import app.auf.core.TextBlock
import app.auf.model.CreateFile
import app.auf.util.JsonProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AufTextParserTest {

    private fun setupTestEnvironment(): AufTextParser {
        val jsonParser = JsonProvider.appJson
        return AufTextParser(jsonParser)
    }

    @Test
    fun `should correctly parse text and a valid action block`() {
        // ARRANGE
        val parser = setupTestEnvironment()
        val rawResponse = """
            Here is the plan.
            [AUF_ACTION_MANIFEST]
            [{"type":"CreateFile","filePath":"test.txt","content":"Hello","summary":"Create"}]
            [/AUF_ACTION_MANIFEST]
            Proceed?
        """.trimIndent()

        // ACT
        val result = parser.parse(rawResponse)

        // ASSERT
        assertEquals(3, result.size)
        assertIs<TextBlock>(result[0], "First block should be Text")
        assertEquals("Here is the plan.", (result[0] as TextBlock).text)

        assertIs<ActionBlock>(result[1], "Second block should be Action")
        val actionBlock = result[1] as ActionBlock
        assertEquals(1, actionBlock.actions.size)
        assertIs<CreateFile>(actionBlock.actions[0])

        assertIs<TextBlock>(result[2], "Third block should be Text")
        assertEquals("Proceed?", (result[2] as TextBlock).text)
    }

    @Test
    fun `should return a single text block if no tags are present`() {
        val parser = setupTestEnvironment()
        val rawResponse = "This is just a simple sentence."
        val result = parser.parse(rawResponse)
        assertEquals(1, result.size)
        assertIs<TextBlock>(result[0])
        assertEquals(rawResponse, (result[0] as TextBlock).text)
    }

    @Test
    fun `should create a ParseErrorBlock for an unterminated tag`() {
        val parser = setupTestEnvironment()
        val rawResponse = """
            Here we go...
            [AUF_ACTION_MANIFEST]
            Some content that never gets closed.
        """.trimIndent()

        val result = parser.parse(rawResponse)
        assertEquals(2, result.size)
        assertIs<TextBlock>(result[0])
        assertIs<ParseErrorBlock>(result[1])
        val errorBlock = result[1] as ParseErrorBlock
        assertEquals("ACTION_MANIFEST", errorBlock.originalTag)
        assertTrue(errorBlock.errorMessage.contains("not properly closed"))
    }

    @Test
    fun `should create a ParseErrorBlock for a nested tag and then parse the inner tag correctly`() {
        val parser = setupTestEnvironment()
        val rawResponse = """
            [AUF_FILE_VIEW: file.txt]
            Outer content.
            [AUF_ACTION_MANIFEST]
            [{"type":"CreateFile","filePath":"inner.txt","content":"...","summary":"Inner"}]
            [/AUF_ACTION_MANIFEST]
            [/AUF_FILE_VIEW]
        """.trimIndent()
        val result = parser.parse(rawResponse)
        assertEquals(3, result.size, "Expected three blocks: Error, Action, and trailing Text")

        assertIs<ParseErrorBlock>(result[0])
        val errorBlock = result[0] as ParseErrorBlock
        assertEquals("FILE_VIEW", errorBlock.originalTag)
        assertTrue(errorBlock.errorMessage.contains("nested start tag"))
        assertEquals("Outer content.", errorBlock.rawContent.trim())

        assertIs<ActionBlock>(result[1])
        assertEquals(1, (result[1] as ActionBlock).actions.size)
        assertEquals("Inner", (result[1] as ActionBlock).actions[0].summary)

        assertIs<TextBlock>(result[2])
        assertEquals("[/AUF_FILE_VIEW]", (result[2] as TextBlock).text.trim())
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
        val errorBlock = result[0] as ParseErrorBlock
        assertEquals("ACTION_MANIFEST", errorBlock.originalTag)
        assertTrue(errorBlock.errorMessage.contains("deserialization error"))
    }
}