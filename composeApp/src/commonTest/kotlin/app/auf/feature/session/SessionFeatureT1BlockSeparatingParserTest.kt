package app.auf.feature.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tier 1 Unit Test for the BlockSeparatingParser component.
 *
 * Mandate (P-TEST-001, T1): To test the parser's internal logic in complete isolation.
 */
class SessionFeatureT1BlockSeparatingParserTest {

    private val parser = BlockSeparatingParser()

    @Test
    fun `should correctly parse single-line tool call`() {
        val rawResponse = "```auf_toastMessage Hello!```"
        val result = parser.parse(rawResponse)
        assertEquals(1, result.size)
        assertIs<ContentBlock.CodeBlock>(result[0])
        val codeBlock = result[0] as ContentBlock.CodeBlock
        assertEquals("auf_toastMessage", codeBlock.language)
        assertEquals("Hello!", codeBlock.code)
    }

    @Test
    fun `should handle fences with leading whitespace`() {
        val rawResponse = "  ```kotlin\n  val indented = true\n  ```"
        val result = parser.parse(rawResponse)
        assertEquals(2, result.size)
        assertIs<ContentBlock.Text>(result[0])
        assertIs<ContentBlock.CodeBlock>(result[1])
    }

    @Test
    fun `should correctly parse well formed text and a valid code block`() {
        val rawResponse = "Here is the plan.\n```json\n{}\n```\nProceed?"
        val result = parser.parse(rawResponse)
        assertEquals(3, result.size)
        assertIs<ContentBlock.Text>(result[0])
        assertIs<ContentBlock.CodeBlock>(result[1])
        assertIs<ContentBlock.Text>(result[2])
        assertEquals("json", (result[1] as ContentBlock.CodeBlock).language)
    }

    @Test
    fun `should handle code block with no language`() {
        val rawResponse = "```\nHello\n```"
        val result = parser.parse(rawResponse)
        assertEquals(1, result.size)
        assertIs<ContentBlock.CodeBlock>(result[0])
        assertEquals("text", (result[0] as ContentBlock.CodeBlock).language)
    }

    @Test
    fun `should treat unterminated code block as a valid greedy block`() {
        val rawResponse = "Here is ```some\ncode"
        val result = parser.parse(rawResponse)
        assertEquals(2, result.size)
        assertIs<ContentBlock.CodeBlock>(result[1])
        assertEquals("some", (result[1] as ContentBlock.CodeBlock).language)
    }

    @Test
    fun `should ignore nested code blocks`() {
        val rawResponse = "```markdown\n## Header\n```kotlin\nnested\n```\n## Footer\n```"
        val result = parser.parse(rawResponse)
        assertEquals(1, result.size)
        assertIs<ContentBlock.CodeBlock>(result[0])
        assertTrue((result[0] as ContentBlock.CodeBlock).code.contains("nested"))
    }

    @Test
    fun `should handle empty input`() {
        val result = parser.parse("")
        assertTrue(result.isEmpty())
    }
}