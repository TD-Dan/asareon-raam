package app.auf.feature.session

import app.auf.core.CodeBlock
import app.auf.core.TextBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BlockSeparatingParserTest {

    @Test
    fun `should correctly parse single-line tool call`() {
        val parser = BlockSeparatingParser()
        val rawResponse = "```auf_toastMessage(\"Hello!\")```"
        val result = parser.parse(rawResponse)

        assertEquals(1, result.size, "Should result in a single code block.")
        assertIs<CodeBlock>(result[0])

        val codeBlock = result[0] as CodeBlock
        assertEquals("auf_toastMessage", codeBlock.language, "The language should be the command name.")
        assertEquals("(\"Hello!\")", codeBlock.content, "The content should be the arguments.")
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
        assertIs<TextBlock>(result[0])
        assertIs<CodeBlock>(result[1])
        assertIs<TextBlock>(result[2])

        val codeBlock = result[1] as CodeBlock
        assertEquals("kotlin", codeBlock.language)
        assertEquals("val indented = true", codeBlock.content)
    }

    @Test
    fun `should correctly parse text and a valid code block`() {
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
        assertIs<TextBlock>(result[0])
        assertIs<CodeBlock>(result[1])
        assertIs<TextBlock>(result[2])
        val codeBlock = result[1] as CodeBlock
        assertEquals("json", codeBlock.language)
        assertEquals("[\n    {\n        \"type\": \"CreateFile\"\n    }\n]", codeBlock.content)
    }

    @Test
    fun `should handle code block with no language`() {
        val parser = BlockSeparatingParser()
        val rawResponse = "```\nHello\n```"
        val result = parser.parse(rawResponse)
        assertEquals(1, result.size)
        assertIs<CodeBlock>(result[0])
        assertEquals("text", (result[0] as CodeBlock).language)
        assertEquals("Hello", (result[0] as CodeBlock).content)
    }

    @Test
    fun `should treat unterminated code block as a valid greedy block`() {
        val parser = BlockSeparatingParser()
        val rawResponse = """
            Here is 
            ```some
            code that never ends
            """.trimIndent()
        val result = parser.parse(rawResponse)
        assertEquals(2, result.size)
        // --- CORRECTED ---
        assertIs<TextBlock>(result[0])
        assertEquals("Here is", (result[0] as TextBlock).text)
        assertIs<CodeBlock>(result[1])
        val codeBlock = result[1] as CodeBlock
        assertEquals("some", codeBlock.language)
        assertEquals("code that never ends", codeBlock.content)
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
            ```txt
            Foobar.
            ```
        """.trimIndent()
        val result = parser.parse(rawResponse)
        assertEquals(4, result.size)
        // --- CORRECTED ---
        assertIs<CodeBlock>(result[0])
        assertIs<TextBlock>(result[1])
        assertIs<CodeBlock>(result[2])
        assertIs<CodeBlock>(result[3])
    }

    @Test
    fun `should ignore fences inside strings and comments`() {
        val parser = BlockSeparatingParser()
        val rawResponse = """
            // This is a comment ``` with a fence.
            val myString = "Here is a ``` fence in a string"
            /*
             * And a multiline ``` comment
             */
            ```kotlin
            val realCode = true
            ```
        """.trimIndent()
        val result = parser.parse(rawResponse)
        assertEquals(2, result.size)
        assertIs<TextBlock>(result[0])
        assertIs<CodeBlock>(result[1])
        val textBlock = result[0] as TextBlock
        assertEquals(
            """
            // This is a comment ``` with a fence.
            val myString = "Here is a ``` fence in a string"
            /*
             * And a multiline ``` comment
             */
            """.trimIndent(),
            textBlock.text
        )
        val codeBlock = result[1] as CodeBlock
        assertEquals("kotlin", codeBlock.language)
        assertEquals("val realCode = true", codeBlock.content)
    }
}