package app.auf.feature.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BlockSeparatingParserTest {

    @Test
    fun `should correctly parse single-line tool call`() {
        val parser = BlockSeparatingParser()
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
        val parser = BlockSeparatingParser()
        val rawResponse = """
            Here is some text.
              ```kotlin
              val indented = true
              ```
            And some more text.
        """.trimMargin()

        val result = parser.parse(rawResponse)
        assertEquals(3, result.size)
        assertIs<ContentBlock.Text>(result[0])
        assertIs<ContentBlock.CodeBlock>(result[1])
        assertIs<ContentBlock.Text>(result[2])

        assertEquals("Here is some text.\n              ", (result[0] as ContentBlock.Text).text)
        val codeBlock = result[1] as ContentBlock.CodeBlock
        assertEquals("kotlin", codeBlock.language)
        assertEquals("val indented = true\n              ", codeBlock.code)
        assertEquals("\n            And some more text.", (result[2] as ContentBlock.Text).text)
    }

    @Test
    fun `should correctly parse text and a valid code block`() {
        val parser = BlockSeparatingParser()
        val rawResponse = "Here is the plan.```json\n[\n    {\n        \"type\": \"CreateFile\"\n    }\n]```Proceed?"
        val result = parser.parse(rawResponse)
        assertEquals(3, result.size)
        assertIs<ContentBlock.Text>(result[0])
        assertIs<ContentBlock.CodeBlock>(result[1])
        assertIs<ContentBlock.Text>(result[2])

        assertEquals("Here is the plan.", (result[0] as ContentBlock.Text).text)
        val codeBlock = result[1] as ContentBlock.CodeBlock
        assertEquals("json", codeBlock.language)
        assertEquals("[\n    {\n        \"type\": \"CreateFile\"\n    }\n]", codeBlock.code)
        assertEquals("Proceed?", (result[2] as ContentBlock.Text).text)
    }

    @Test
    fun `should handle code block with no language`() {
        val parser = BlockSeparatingParser()
        val rawResponse = "```\nHello\n```"
        val result = parser.parse(rawResponse)
        assertEquals(1, result.size)
        assertIs<ContentBlock.CodeBlock>(result[0])
        assertEquals("text", (result[0] as ContentBlock.CodeBlock).language)
        assertEquals("Hello\n", (result[0] as ContentBlock.CodeBlock).code)
    }

    @Test
    fun `should treat unterminated code block as a valid greedy block`() {
        val parser = BlockSeparatingParser()
        val rawResponse = "Here is ```some\ncode that never ends"
        val result = parser.parse(rawResponse)
        assertEquals(2, result.size)
        assertIs<ContentBlock.Text>(result[0])
        assertEquals("Here is ", (result[0] as ContentBlock.Text).text)
        assertIs<ContentBlock.CodeBlock>(result[1])
        val codeBlock = result[1] as ContentBlock.CodeBlock
        assertEquals("some", codeBlock.language)
        assertEquals("code that never ends", codeBlock.code)
    }

    @Test
    fun `should find multiple code blocks`() {
        val parser = BlockSeparatingParser()
        val rawResponse = "```markdown\n## Header\n```Some text in middle```kotlin\nval realCode = true\n``````txt\nFoobar.\n```"
        val result = parser.parse(rawResponse)
        assertEquals(4, result.size)
        assertIs<ContentBlock.CodeBlock>(result[0])
        assertIs<ContentBlock.Text>(result[1])
        assertIs<ContentBlock.CodeBlock>(result[2])
        assertIs<ContentBlock.CodeBlock>(result[3])
        assertEquals("## Header\n", (result[0] as ContentBlock.CodeBlock).code)
        assertEquals("Some text in middle", (result[1] as ContentBlock.Text).text)
        assertEquals("val realCode = true\n", (result[2] as ContentBlock.CodeBlock).code)
    }

    @Test
    fun `should handle empty input`() {
        val parser = BlockSeparatingParser()
        val result = parser.parse("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `should handle input with only whitespace`() {
        val parser = BlockSeparatingParser()
        val result = parser.parse("   \n\t  ")
        assertTrue(result.isEmpty())
    }
}
