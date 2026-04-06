package asareon.raam.feature.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * T1 — Unit tests for [BlockSeparatingParser] XML tag block support.
 *
 * Tests the parser's ability to recognise `<tag>...</tag>` patterns and produce
 * [ContentBlock.XmlTagBlock] instances with raw string content.
 *
 * Inner content is NOT recursively parsed — code fences and nested XML tags
 * inside an XmlTagBlock are inert text. Models that want commands to execute
 * must place them outside XML wrappers.
 *
 * Existing code fence behaviour is covered by the pre-existing parser test suite;
 * this file focuses on the XML tag additions and their interaction with code fences.
 */
class SessionT1BlockSeparatingParserXmlTagTest {

    private val parser = BlockSeparatingParser()

    // ═══════════════════════════════════════════════════════════════════
    // Basic XML tag recognition
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `think block at start of string`() {
        val result = parser.parse("<think>\nI should greet the user.\n</think>\nHello!")
        assertEquals(2, result.size)

        val think = assertIs<ContentBlock.XmlTagBlock>(result[0])
        assertEquals("think", think.tag)
        assertTrue(think.content.contains("greet"))

        assertIs<ContentBlock.Text>(result[1])
    }

    @Test
    fun `think block after text`() {
        val result = parser.parse("Some preamble.\n<think>\nReasoning here.\n</think>")
        assertEquals(2, result.size)

        assertIs<ContentBlock.Text>(result[0])
        val think = assertIs<ContentBlock.XmlTagBlock>(result[1])
        assertEquals("think", think.tag)
        assertTrue(think.content.contains("Reasoning"))
    }

    @Test
    fun `text before and after think block`() {
        val result = parser.parse("Before.\n<think>\nMiddle.\n</think>\nAfter.")
        assertEquals(3, result.size)

        assertIs<ContentBlock.Text>(result[0])
        assertIs<ContentBlock.XmlTagBlock>(result[1])
        assertIs<ContentBlock.Text>(result[2])
    }

    @Test
    fun `empty think block`() {
        val result = parser.parse("<think>\n</think>\nHello!")
        assertEquals(2, result.size)

        val think = assertIs<ContentBlock.XmlTagBlock>(result[0])
        assertEquals("think", think.tag)
        assertTrue(think.content.isBlank())
    }

    @Test
    fun `different tag names are recognised`() {
        val input = "<reasoning>\nStep 1.\n</reasoning>\n<reflection>\nLooks good.\n</reflection>"
        val result = parser.parse(input)

        assertEquals(2, result.size)
        val reasoning = assertIs<ContentBlock.XmlTagBlock>(result[0])
        assertEquals("reasoning", reasoning.tag)
        val reflection = assertIs<ContentBlock.XmlTagBlock>(result[1])
        assertEquals("reflection", reflection.tag)
    }

    // ═══════════════════════════════════════════════════════════════════
    // XML tag must be at line start
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `tag mid-line is not recognised as XML block`() {
        val input = "The model uses <think> tags for reasoning."
        val result = parser.parse(input)

        assertEquals(1, result.size)
        assertIs<ContentBlock.Text>(result[0])
    }

    @Test
    fun `tag with leading whitespace on line is recognised`() {
        val result = parser.parse("  <think>\nContent.\n</think>")
        assertEquals(1, result.size)
        assertIs<ContentBlock.XmlTagBlock>(result[0])
    }

    // ═══════════════════════════════════════════════════════════════════
    // Unterminated tags
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `unterminated tag falls back to text`() {
        val input = "<think>\nThis never closes."
        val result = parser.parse(input)

        assertEquals(1, result.size)
        assertIs<ContentBlock.Text>(result[0])
    }

    // ═══════════════════════════════════════════════════════════════════
    // Inner content is raw text (NOT recursively parsed)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `code fence inside think block is raw text not a CodeBlock`() {
        val input = "<think>\nLet me try:\n```kotlin\nval x = 1\n```\n</think>"
        val result = parser.parse(input)

        assertEquals(1, result.size)
        val think = assertIs<ContentBlock.XmlTagBlock>(result[0])
        // The code fence is preserved as raw text, not parsed into a CodeBlock
        assertTrue(think.content.contains("```kotlin"))
        assertTrue(think.content.contains("val x = 1"))
    }

    @Test
    fun `nested XML tags inside think block are raw text`() {
        val input = """
<cognitive_state>
  <identity>The Silicon Sage</identity>
  <mode>HIBERNATION</mode>
  <analysis>Three updates to emit.</analysis>
</cognitive_state>
        """.trimIndent()

        val result = parser.parse(input)
        assertEquals(1, result.size)

        val block = assertIs<ContentBlock.XmlTagBlock>(result[0])
        assertEquals("cognitive_state", block.tag)
        // Nested tags are preserved as raw text
        assertTrue(block.content.contains("<identity>"))
        assertTrue(block.content.contains("<analysis>"))
    }

    @Test
    fun `raam action inside think block is inert`() {
        val input = "<think>\nI need to list files.\n```raam_filesystem.LIST\n{\"recursive\": true}\n```\n</think>"
        val result = parser.parse(input)

        assertEquals(1, result.size)
        val think = assertIs<ContentBlock.XmlTagBlock>(result[0])
        // The raam_ code fence is just text inside the think block — not executable
        assertTrue(think.content.contains("raam_filesystem.LIST"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // XML tags inside code fences (should NOT be parsed)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `think tag inside code fence is not parsed as XML block`() {
        val input = "```text\n<think>\nThis is just code content.\n</think>\n```"
        val result = parser.parse(input)

        assertEquals(1, result.size)
        val code = assertIs<ContentBlock.CodeBlock>(result[0])
        assertTrue(code.code.contains("<think>"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // Ordering: code fence before XML tag
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `code fence before think block`() {
        val input = "```kotlin\nval x = 1\n```\n<think>\nReasoning.\n</think>"
        val result = parser.parse(input)

        assertEquals(2, result.size)
        assertIs<ContentBlock.CodeBlock>(result[0])
        assertIs<ContentBlock.XmlTagBlock>(result[1])
    }

    @Test
    fun `code fence and think block interleaved with text`() {
        val input = "Hello.\n```kotlin\ncode\n```\nMiddle.\n<think>\nThinking.\n</think>\nEnd."
        val result = parser.parse(input)

        assertEquals(5, result.size)
        assertIs<ContentBlock.Text>(result[0])
        assertIs<ContentBlock.CodeBlock>(result[1])
        assertIs<ContentBlock.Text>(result[2])
        assertIs<ContentBlock.XmlTagBlock>(result[3])
        assertIs<ContentBlock.Text>(result[4])
    }

    // ═══════════════════════════════════════════════════════════════════
    // Mixed content: full realistic response
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `realistic response with think block then visible code`() {
        val input = """
<think>
The user wants to list files. I'll respond with results.
</think>
I've listed the files in your workspace. Here are the results:
```json
["file1.kt", "file2.kt"]
```
        """.trimIndent()

        val result = parser.parse(input)
        assertEquals(3, result.size)

        val think = assertIs<ContentBlock.XmlTagBlock>(result[0])
        assertEquals("think", think.tag)
        assertTrue(think.content.contains("list files"))

        assertIs<ContentBlock.Text>(result[1])

        val json = assertIs<ContentBlock.CodeBlock>(result[2])
        assertEquals("json", json.language)
    }

    @Test
    fun `commands outside think block are real CodeBlocks`() {
        val input = """
<think>
I should list the files.
</think>
```raam_filesystem.LIST
{"recursive": true}
```
        """.trimIndent()

        val result = parser.parse(input)
        assertEquals(2, result.size)

        assertIs<ContentBlock.XmlTagBlock>(result[0])
        val code = assertIs<ContentBlock.CodeBlock>(result[1])
        assertTrue(code.language.startsWith("raam_"), "Code block outside think should be a real executable CodeBlock")
    }
}