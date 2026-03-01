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

    // ---- LLM robustness: malformed fence patterns ----

    @Test
    fun `should handle leading whitespace with closing fence on same line as content`() {
        // LLM sometimes indents the opening fence and puts the closing fence inline.
        val rawResponse = "  ```foo.txt\ntext```"
        val result = parser.parse(rawResponse)
        val codeBlocks = result.filterIsInstance<ContentBlock.CodeBlock>()
        assertEquals(1, codeBlocks.size, "Expected exactly one code block")
        assertEquals("foo.txt", codeBlocks[0].language)
        assertTrue(codeBlocks[0].code.contains("text"), "Code body should contain 'text'")
    }

    @Test
    fun `should handle closing fence on same line without leading whitespace`() {
        // Variant without indentation – closing ``` glued to content.
        val rawResponse = "```foo.txt\ntext```"
        val result = parser.parse(rawResponse)
        val codeBlocks = result.filterIsInstance<ContentBlock.CodeBlock>()
        assertEquals(1, codeBlocks.size, "Expected exactly one code block")
        assertEquals("foo.txt", codeBlocks[0].language)
        assertTrue(codeBlocks[0].code.contains("text"), "Code body should contain 'text'")
    }

    @Test
    fun `should handle no language name with text immediately after closing fence`() {
        // LLM forgets the language and appends text right after the closing fence.
        val rawResponse = "  ```\ntext\n```here"
        val result = parser.parse(rawResponse)
        val codeBlocks = result.filterIsInstance<ContentBlock.CodeBlock>()
        assertEquals(1, codeBlocks.size, "Expected exactly one code block")
        assertEquals("text", codeBlocks[0].language) // no language → defaults to "text"
        val textBlocks = result.filterIsInstance<ContentBlock.Text>()
        assertTrue(textBlocks.any { it.text.contains("here") }, "Trailing text 'here' should appear in a text block")
    }

    @Test
    fun `should handle language name with text immediately after closing fence`() {
        // LLM provides a language tag but glues trailing prose onto the closing fence.
        val rawResponse = "```kotlin\nval x = 1\n```and then"
        val result = parser.parse(rawResponse)
        val codeBlocks = result.filterIsInstance<ContentBlock.CodeBlock>()
        assertEquals(1, codeBlocks.size, "Expected exactly one code block")
        assertEquals("kotlin", codeBlocks[0].language)
        val textBlocks = result.filterIsInstance<ContentBlock.Text>()
        assertTrue(textBlocks.any { it.text.contains("and then") }, "Trailing text should appear in a text block")
    }
}