package asareon.raam.feature.session

import asareon.raam.core.Version
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
        val rawResponse = "```${Version.APP_TOOL_PREFIX}toastMessage Hello!```"
        val result = parser.parse(rawResponse)
        assertEquals(1, result.size)
        assertIs<ContentBlock.CodeBlock>(result[0])
        val codeBlock = result[0] as ContentBlock.CodeBlock
        assertEquals("${Version.APP_TOOL_PREFIX}toastMessage", codeBlock.language)
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

    // ---- Noise filtering: mid-line ``` (content on both sides) must not break outer fences ----

    @Test
    fun `should ignore triple backticks embedded mid-line inside a code block`() {
        // Simulates the exact agent failure: a raam_ tool call whose JSON "message"
        // value contains ``` mid-line.  Must not close the outer fence.
        val rawResponse = "```raam_session.POST\n" +
                "{ \"session\": \"s1\", \"message\": \"hello ```world``` bye\" }\n" +
                "```"
        val result = parser.parse(rawResponse)
        val codeBlocks = result.filterIsInstance<ContentBlock.CodeBlock>()
        assertEquals(1, codeBlocks.size, "Mid-line ``` must not split the outer code block")
        assertEquals("raam_session.POST", codeBlocks[0].language)
        assertTrue(codeBlocks[0].code.contains("hello ```world``` bye"), "Full JSON payload must be preserved")
    }

    @Test
    fun `should accept end-of-line closer glued to content`() {
        // LLM writes the closing fence at the end of a content line (no newline before it).
        // This is a real fence because nothing follows it on the same line.
        val rawResponse = "```kotlin\nval x = 1\nval y = 2```\nDone."
        val result = parser.parse(rawResponse)
        val codeBlocks = result.filterIsInstance<ContentBlock.CodeBlock>()
        assertEquals(1, codeBlocks.size, "End-of-line ``` must be accepted as a closer")
        assertEquals("kotlin", codeBlocks[0].language)
        assertTrue(codeBlocks[0].code.contains("val y = 2"), "Code must include content up to the closer")
        val textBlocks = result.filterIsInstance<ContentBlock.Text>()
        assertTrue(textBlocks.any { it.text.contains("Done.") }, "Text after closer must appear")
    }

    @Test
    fun `should ignore mid-line backticks in JSON value but find line-start closer`() {
        // JSON payload contains ``` mid-line. The real closing fence is on its own line.
        val payload = """{ "msg": "see ```this``` example" }"""
        val rawResponse = "```raam_test.ACTION\n$payload\n```\nDone."
        val result = parser.parse(rawResponse)
        assertEquals(2, result.size, "Expected one code block + trailing text")
        assertIs<ContentBlock.CodeBlock>(result[0])
        assertIs<ContentBlock.Text>(result[1])
        assertEquals("raam_test.ACTION", (result[0] as ContentBlock.CodeBlock).language)
        assertTrue((result[0] as ContentBlock.CodeBlock).code.contains("```this```"), "Mid-line ``` must be preserved in code body")
    }

    @Test
    fun `should still support genuine nested fences at line start`() {
        // Nested ```kotlin at line start should still increment depth correctly.
        val rawResponse = "```markdown\n## Header\n```kotlin\nval x = 1\n```\n## Footer\n```"
        val result = parser.parse(rawResponse)
        assertEquals(1, result.size, "Nested fences at line start should be depth-tracked")
        assertIs<ContentBlock.CodeBlock>(result[0])
        assertTrue((result[0] as ContentBlock.CodeBlock).code.contains("val x = 1"))
        assertTrue((result[0] as ContentBlock.CodeBlock).code.contains("## Footer"))
    }

    @Test
    fun `should handle multiple mid-line backtick occurrences without breaking`() {
        // Stress test: multiple ``` mid-line, only one real closer at line start.
        val rawResponse = "```json\n{\"a\": \"```\", \"b\": \"```\", \"c\": \"```\"}\n```"
        val result = parser.parse(rawResponse)
        val codeBlocks = result.filterIsInstance<ContentBlock.CodeBlock>()
        assertEquals(1, codeBlocks.size)
        assertEquals("json", codeBlocks[0].language)
        // All three mid-line ``` must survive inside the code body.
        assertEquals(3, Regex("```").findAll(codeBlocks[0].code).count(),
            "All three mid-line ``` must be preserved in code body")
    }

    @Test
    fun `should parse full agent turn with backticks in JSON message value`() {
        // Realistic agent output: thinking text, then a tool-call code block whose
        // JSON "message" value contains ``` as escaped markdown, then a second action.
        val rawResponse = "*Thinking about architecture...*\n\n" +
                "```raam_session.POST\n" +
                "{ \"session\": \"session.raam-improvement\", \"message\": \"## Layer Cake\\n\\n```\\nL3: AgentRuntime\\nL2: Session\\n```\\n\\nDependencies flow down.\" }\n" +
                "```\n\n" +
                "```raam_agent.UPDATE_NVRAM\n" +
                "{ \"updates\": { \"phase\": \"ACTIVE\" } }\n" +
                "```"
        val result = parser.parse(rawResponse)
        assertEquals(4, result.size, "Expected: text, POST block, text, NVRAM block")
        assertIs<ContentBlock.Text>(result[0])
        assertIs<ContentBlock.CodeBlock>(result[1])
        assertIs<ContentBlock.Text>(result[2])
        assertIs<ContentBlock.CodeBlock>(result[3])
        val postBlock = result[1] as ContentBlock.CodeBlock
        assertEquals("raam_session.POST", postBlock.language)
        assertTrue(postBlock.code.contains("L3: AgentRuntime"), "JSON payload must be intact")
        assertTrue(postBlock.code.contains("Dependencies flow down"), "JSON payload must not be truncated")
        val nvramBlock = result[3] as ContentBlock.CodeBlock
        assertEquals("raam_agent.UPDATE_NVRAM", nvramBlock.language)
    }
}