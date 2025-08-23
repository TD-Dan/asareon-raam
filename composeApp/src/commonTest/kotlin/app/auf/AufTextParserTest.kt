// --- FILE: composeApp/src/commonTest/kotlin/app/auf/service/AufTextParserTest.kt ---
package app.auf.service

import app.auf.core.ActionBlock
import app.auf.core.ParseErrorBlock
import app.auf.core.TextBlock
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
        assertIs<TextBlock>(result)
        assertEquals("Here is the plan.", (result as TextBlock).text)
        assertIs<ActionBlock>(result)
        assertEquals(1, (result as ActionBlock).actions.size)
        assertIs<TextBlock>(result)
        assertEquals("Proceed?", (result as TextBlock).text)
    }

    @Test
    fun `should return a single text block if no tags are present`() {
        val parser = setupTestEnvironment()
        val rawResponse = "This is just a simple sentence."
        val result = parser.parse(rawResponse)
        assertEquals(1, result.size)
        assertIs<TextBlock>(result)
        assertEquals(rawResponse, (result as TextBlock).text)
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
        assertIs<TextBlock>(result)
        assertIs<ParseErrorBlock>(result)
        val errorBlock = result as ParseErrorBlock
        assertEquals("ACTION_MANIFEST", errorBlock.originalTag)
        assertTrue(errorBlock.errorMessage.contains("not properly closed"))
    }

    @Test
    fun `should create a ParseErrorBlock for a nested tag`() {
        val parser = setupTestEnvironment()
        val rawResponse = """
            [AUF_FILE_VIEW: file.txt]
            Outer content.
            [AUF_ACTION_MANIFEST]
            Inner content.
            [/AUF_ACTION_MANIFEST]
            [/AUF_FILE_VIEW]
        """.trimIndent()
        val result = parser.parse(rawResponse)
        assertEquals(1, result.size)
        assertIs<ParseErrorBlock>(result)
        val errorBlock = result as ParseErrorBlock
        assertEquals("FILE_VIEW", errorBlock.originalTag)
        assertTrue(errorBlock.errorMessage.contains("nested start tag"))
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
        assertIs<ParseErrorBlock>(result)
        val errorBlock = result as ParseErrorBlock
        assertEquals("ACTION_MANIFEST", errorBlock.originalTag)
        assertTrue(errorBlock.errorMessage.contains("deserialization error"))
    }
}