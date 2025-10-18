package app.auf.feature.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tier 1 Component Test for SessionFeature Text and CodeBlock parsing.
 *
 * Mandate (P-TEST-001, T1): To test the BlockSeparatingParsers internal logic
 *
 */

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
        // trimIndent() makes this a valid test case for the parser.
        val rawResponse = """
            Here is some text.
              ```kotlin
              val indented = true
              ```
            And some more text.
            And even more.
        """.trimIndent()

        val result = parser.parse(rawResponse)
        assertEquals(3, result.size)
        assertIs<ContentBlock.Text>(result[0])
        assertIs<ContentBlock.CodeBlock>(result[1])
        assertIs<ContentBlock.Text>(result[2])

        assertEquals("Here is some text.\n  ", (result[0] as ContentBlock.Text).text)
        val codeBlock = result[1] as ContentBlock.CodeBlock
        assertEquals("kotlin", codeBlock.language)
        assertEquals("  val indented = true\n  ", codeBlock.code)
        assertEquals("\nAnd some more text.\nAnd even more.", (result[2] as ContentBlock.Text).text)
    }

    @Test
    fun `should correctly parse well formed text and a valid code block`() {
        val parser = BlockSeparatingParser()
        val rawResponse = """
            Here is the plan.
            ```json
            [
                {
                    "type": "CreateFile"
                }
            ]
            ```
            Proceed?
            """.trimIndent()
        val result = parser.parse(rawResponse)
        assertEquals(3, result.size)
        assertIs<ContentBlock.Text>(result[0])
        assertIs<ContentBlock.CodeBlock>(result[1])
        assertIs<ContentBlock.Text>(result[2])

        assertEquals("Here is the plan.\n", (result[0] as ContentBlock.Text).text)
        val codeBlock = result[1] as ContentBlock.CodeBlock
        assertEquals("json", codeBlock.language)
        assertEquals("""
            [
                {
                    "type": "CreateFile"
                }
            ]
            """.trimIndent() + "\n", codeBlock.code)
        assertEquals("\nProceed?", (result[2] as ContentBlock.Text).text)
    }

    @Test
    fun `should correctly parse text and a valid code block with faulty newlines`() {
        val parser = BlockSeparatingParser()
        val rawResponse = """
            Here is the plan.```json
            [
                {
                    "type": "CreateFile"
                }
            ]```Proceed?
            """.trimIndent()
        val result = parser.parse(rawResponse)
        assertEquals(3, result.size)
        assertIs<ContentBlock.Text>(result[0])
        assertIs<ContentBlock.CodeBlock>(result[1])
        assertIs<ContentBlock.Text>(result[2])

        assertEquals("Here is the plan.", (result[0] as ContentBlock.Text).text)
        val codeBlock = result[1] as ContentBlock.CodeBlock
        assertEquals("json", codeBlock.language)
        assertEquals("""
            [
                {
                    "type": "CreateFile"
                }
            ]
            """.trimIndent(), codeBlock.code)
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
        val rawResponse = """
            ```markdown
            ## Header
            ```
            Some text in middle
            ```kotlin
            val realCode = true
            ```
            """.trimIndent()

        val result = parser.parse(rawResponse)
        assertEquals(3, result.size)
        assertIs<ContentBlock.CodeBlock>(result[0])
        assertIs<ContentBlock.Text>(result[1])
        assertIs<ContentBlock.CodeBlock>(result[2])
        assertEquals("## Header\n", (result[0] as ContentBlock.CodeBlock).code)
        assertEquals("\nSome text in middle\n", (result[1] as ContentBlock.Text).text)
        assertEquals("val realCode = true\n", (result[2] as ContentBlock.CodeBlock).code)
    }

    @Test
    fun `should ignore nested code blocks`() {
        val parser = BlockSeparatingParser()
        val rawResponse = """
            Starting text.
            ```markdown
            ## Header
            ```kotlin
            val nestedCode = true
            ```
            ## Footer
            ```
            Ending text.
            """.trimIndent()

        val result = parser.parse(rawResponse)
        assertEquals(3, result.size)
        assertIs<ContentBlock.Text>(result[0])
        assertIs<ContentBlock.CodeBlock>(result[1])
        assertIs<ContentBlock.Text>(result[2])
        assertEquals("Starting text.\n", (result[0] as ContentBlock.Text).text)
        assertEquals("""
            ## Header
            ```kotlin
            val nestedCode = true
            ```
            ## Footer
            """.trimIndent() + "\n", (result[1] as ContentBlock.CodeBlock).code)
        assertEquals("\nEnding text.", (result[2] as ContentBlock.Text).text)
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