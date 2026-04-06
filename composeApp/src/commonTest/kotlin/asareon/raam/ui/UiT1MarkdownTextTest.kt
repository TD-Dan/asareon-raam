package asareon.raam.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import asareon.raam.ui.components.InlineColors
import asareon.raam.ui.components.MdBlock
import asareon.raam.ui.components.parseBlocks
import asareon.raam.ui.components.parseInline
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * T1 Unit Tests for MarkdownText parsing.
 *
 * Tests the pure-function block parser ([parseBlocks]) and inline parser
 * ([parseInline]) in isolation. No Compose UI, no Store, no coroutines.
 */
class UiT1MarkdownTextTest {

    private val testColors = InlineColors(
        codeBackground = Color.LightGray,
        codeColor = Color.Black,
        linkColor = Color.Blue,
        mutedColor = Color.Gray
    )

    // ═════════════════════════════════════════════════════════════════════
    // Block Parser — Headings
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun heading_levels_1_through_6() {
        val input = "# H1\n## H2\n### H3\n#### H4\n##### H5\n###### H6"
        val blocks = parseBlocks(input)

        assertEquals(6, blocks.size)
        for (i in 0..5) {
            val block = assertIs<MdBlock.Heading>(blocks[i])
            assertEquals(i + 1, block.level)
            assertEquals("H${i + 1}", block.content)
        }
    }

    @Test
    fun heading_requires_space_after_hashes() {
        val blocks = parseBlocks("##NoSpace")
        // Should be a paragraph, not a heading
        assertEquals(1, blocks.size)
        assertIs<MdBlock.Paragraph>(blocks[0])
    }

    @Test
    fun heading_preserves_inline_content() {
        val blocks = parseBlocks("# Hello **world**")
        val heading = assertIs<MdBlock.Heading>(blocks[0])
        assertEquals("Hello **world**", heading.content)
    }

    // ═════════════════════════════════════════════════════════════════════
    // Block Parser — Paragraphs
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun consecutive_lines_merge_into_paragraph() {
        val input = "Line one\nLine two\nLine three"
        val blocks = parseBlocks(input)

        assertEquals(1, blocks.size)
        val para = assertIs<MdBlock.Paragraph>(blocks[0])
        assertEquals("Line one Line two Line three", para.content)
    }

    @Test
    fun blank_lines_separate_paragraphs() {
        val input = "First paragraph\n\nSecond paragraph"
        val blocks = parseBlocks(input)

        assertEquals(2, blocks.size)
        assertIs<MdBlock.Paragraph>(blocks[0])
        assertIs<MdBlock.Paragraph>(blocks[1])
        assertEquals("First paragraph", (blocks[0] as MdBlock.Paragraph).content)
        assertEquals("Second paragraph", (blocks[1] as MdBlock.Paragraph).content)
    }

    @Test
    fun empty_input_produces_no_blocks() {
        assertEquals(emptyList(), parseBlocks(""))
    }

    @Test
    fun whitespace_only_input_produces_no_blocks() {
        assertEquals(emptyList(), parseBlocks("   \n   \n"))
    }

    // ═════════════════════════════════════════════════════════════════════
    // Block Parser — Block Quotes
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun single_blockquote_line() {
        val blocks = parseBlocks("> Hello world")
        assertEquals(1, blocks.size)
        val quote = assertIs<MdBlock.BlockQuote>(blocks[0])
        assertEquals("Hello world", quote.content)
    }

    @Test
    fun consecutive_blockquote_lines_merge() {
        val blocks = parseBlocks("> Line one\n> Line two")
        assertEquals(1, blocks.size)
        val quote = assertIs<MdBlock.BlockQuote>(blocks[0])
        assertEquals("Line one\nLine two", quote.content)
    }

    @Test
    fun blockquote_does_not_eat_subsequent_content() {
        val input = "> Quote\n\nParagraph after quote"
        val blocks = parseBlocks(input)

        assertEquals(2, blocks.size)
        assertIs<MdBlock.BlockQuote>(blocks[0])
        val para = assertIs<MdBlock.Paragraph>(blocks[1])
        assertEquals("Paragraph after quote", para.content)
    }

    @Test
    fun blockquote_followed_by_non_quote_without_blank_line() {
        val input = "> Quote\nNot a quote"
        val blocks = parseBlocks(input)

        assertEquals(2, blocks.size)
        assertIs<MdBlock.BlockQuote>(blocks[0])
        assertIs<MdBlock.Paragraph>(blocks[1])
    }

    // ═════════════════════════════════════════════════════════════════════
    // Block Parser — Horizontal Rules
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun horizontal_rule_variants() {
        listOf("---", "***", "___", "- - -", "* * *", "_ _ _", "----------").forEach { hr ->
            val blocks = parseBlocks(hr)
            assertEquals(1, blocks.size, "Expected HR for: '$hr'")
            assertIs<MdBlock.HorizontalRule>(blocks[0], "Expected HR for: '$hr'")
        }
    }

    @Test
    fun horizontal_rule_does_not_eat_subsequent_content() {
        val input = "Before\n---\nAfter"
        val blocks = parseBlocks(input)

        assertEquals(3, blocks.size)
        assertIs<MdBlock.Paragraph>(blocks[0])
        assertIs<MdBlock.HorizontalRule>(blocks[1])
        assertIs<MdBlock.Paragraph>(blocks[2])
        assertEquals("After", (blocks[2] as MdBlock.Paragraph).content)
    }

    @Test
    fun two_dashes_is_not_horizontal_rule() {
        val blocks = parseBlocks("--")
        assertEquals(1, blocks.size)
        assertIs<MdBlock.Paragraph>(blocks[0])
    }

    // ═════════════════════════════════════════════════════════════════════
    // Block Parser — Unordered Lists
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun unordered_list_with_dash() {
        val blocks = parseBlocks("- Item one\n- Item two")
        assertEquals(2, blocks.size)
        val item1 = assertIs<MdBlock.UnorderedListItem>(blocks[0])
        assertEquals(0, item1.depth)
        assertEquals("Item one", item1.content)
    }

    @Test
    fun nested_unordered_list() {
        val blocks = parseBlocks("- Top\n  - Nested\n    - Deep")
        assertEquals(3, blocks.size)
        assertEquals(0, (blocks[0] as MdBlock.UnorderedListItem).depth)
        assertEquals(1, (blocks[1] as MdBlock.UnorderedListItem).depth)
        assertEquals(2, (blocks[2] as MdBlock.UnorderedListItem).depth)
    }

    @Test
    fun unordered_list_with_asterisk_and_plus() {
        val blocks = parseBlocks("* Star item\n+ Plus item")
        assertEquals(2, blocks.size)
        assertIs<MdBlock.UnorderedListItem>(blocks[0])
        assertIs<MdBlock.UnorderedListItem>(blocks[1])
    }

    // ═════════════════════════════════════════════════════════════════════
    // Block Parser — Ordered Lists
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun ordered_list() {
        val blocks = parseBlocks("1. First\n2. Second\n3. Third")
        assertEquals(3, blocks.size)
        val item = assertIs<MdBlock.OrderedListItem>(blocks[0])
        assertEquals("1", item.number)
        assertEquals("First", item.content)
    }

    @Test
    fun nested_ordered_list() {
        val blocks = parseBlocks("1. Top\n  2. Nested")
        assertEquals(2, blocks.size)
        assertEquals(0, (blocks[0] as MdBlock.OrderedListItem).depth)
        assertEquals(1, (blocks[1] as MdBlock.OrderedListItem).depth)
    }

    // ═════════════════════════════════════════════════════════════════════
    // Block Parser — Mixed Content
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun full_document_with_all_block_types() {
        val input = """
# Title

A paragraph.

> A quote

- Item 1
- Item 2

1. Ordered

---

Final paragraph.
        """.trimIndent()

        val blocks = parseBlocks(input)

        assertIs<MdBlock.Heading>(blocks[0])
        assertIs<MdBlock.Paragraph>(blocks[1])
        assertIs<MdBlock.BlockQuote>(blocks[2])
        assertIs<MdBlock.UnorderedListItem>(blocks[3])
        assertIs<MdBlock.UnorderedListItem>(blocks[4])
        assertIs<MdBlock.OrderedListItem>(blocks[5])
        assertIs<MdBlock.HorizontalRule>(blocks[6])
        assertIs<MdBlock.Paragraph>(blocks[7])
        assertEquals("Final paragraph.", (blocks[7] as MdBlock.Paragraph).content)
    }

    @Test
    fun blockquote_then_hr_then_text_all_visible() {
        // Regression: blockquote must not consume subsequent content.
        val input = "> Quote\n\nHorizontal Rule:\n---\nAfter HR"
        val blocks = parseBlocks(input)

        val afterHr = blocks.last()
        assertIs<MdBlock.Paragraph>(afterHr)
        assertEquals("After HR", afterHr.content)
    }

    // ═════════════════════════════════════════════════════════════════════
    // Inline Parser — Bold (asterisk and underscore)
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun inline_bold_asterisk() {
        val result = parseInline("Hello **world**", testColors)
        assertEquals("Hello world", result.text)
        val boldSpan = result.spanStyles.find { it.item.fontWeight == FontWeight.Bold }
        assertTrue(boldSpan != null, "Expected bold span")
    }

    @Test
    fun inline_bold_underscore() {
        val result = parseInline("Hello __world__", testColors)
        assertEquals("Hello world", result.text)
        val boldSpan = result.spanStyles.find { it.item.fontWeight == FontWeight.Bold }
        assertTrue(boldSpan != null, "Expected bold span for __")
    }

    // ═════════════════════════════════════════════════════════════════════
    // Inline Parser — Italic (asterisk and underscore)
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun inline_italic_asterisk() {
        val result = parseInline("Hello *world*", testColors)
        assertEquals("Hello world", result.text)
        val italicSpan = result.spanStyles.find { it.item.fontStyle == FontStyle.Italic }
        assertTrue(italicSpan != null, "Expected italic span")
    }

    @Test
    fun inline_italic_underscore() {
        val result = parseInline("Hello _world_", testColors)
        assertEquals("Hello world", result.text)
        val italicSpan = result.spanStyles.find { it.item.fontStyle == FontStyle.Italic }
        assertTrue(italicSpan != null, "Expected italic span for _")
    }

    // ═════════════════════════════════════════════════════════════════════
    // Inline Parser — Bold-Italic
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun inline_bold_italic_asterisk() {
        val result = parseInline("Hello ***world***", testColors)
        assertEquals("Hello world", result.text)
        val biSpan = result.spanStyles.find {
            it.item.fontWeight == FontWeight.Bold && it.item.fontStyle == FontStyle.Italic
        }
        assertTrue(biSpan != null, "Expected bold+italic span for ***")
    }

    @Test
    fun inline_bold_italic_underscore() {
        val result = parseInline("Hello ___world___", testColors)
        assertEquals("Hello world", result.text)
        val biSpan = result.spanStyles.find {
            it.item.fontWeight == FontWeight.Bold && it.item.fontStyle == FontStyle.Italic
        }
        assertTrue(biSpan != null, "Expected bold+italic span for ___")
    }

    // ═════════════════════════════════════════════════════════════════════
    // Inline Parser — Code
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun inline_code() {
        val result = parseInline("Use `println` here", testColors)
        // Non-breaking spaces surround the code
        assertTrue(result.text.contains("println"), "Expected code text")
        val codeSpan = result.spanStyles.find { it.item.background == Color.LightGray }
        assertTrue(codeSpan != null, "Expected code background span")
    }

    @Test
    fun unmatched_backtick_is_literal() {
        val result = parseInline("A ` lonely backtick", testColors)
        assertEquals("A ` lonely backtick", result.text)
    }

    // ═════════════════════════════════════════════════════════════════════
    // Inline Parser — Links
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun inline_link() {
        val result = parseInline("Click [here](https://example.com) now", testColors)
        assertEquals("Click here now", result.text)
        val linkSpan = result.spanStyles.find { it.item.textDecoration == TextDecoration.Underline }
        assertTrue(linkSpan != null, "Expected underlined link span")
    }

    @Test
    fun unmatched_bracket_is_literal() {
        val result = parseInline("Just a [bracket", testColors)
        assertEquals("Just a [bracket", result.text)
    }

    // ═════════════════════════════════════════════════════════════════════
    // Inline Parser — Strikethrough
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun inline_strikethrough() {
        val result = parseInline("This is ~~deleted~~ text", testColors)
        assertEquals("This is deleted text", result.text)
        val strikeSpan = result.spanStyles.find { it.item.textDecoration == TextDecoration.LineThrough }
        assertTrue(strikeSpan != null, "Expected strikethrough span")
    }

    // ═════════════════════════════════════════════════════════════════════
    // Inline Parser — Edge Cases
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun plain_text_has_no_spans() {
        val result = parseInline("Just plain text", testColors)
        assertEquals("Just plain text", result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun unmatched_bold_markers_are_literal() {
        val result = parseInline("Hello ** world", testColors)
        assertEquals("Hello ** world", result.text)
    }

    @Test
    fun nested_bold_and_italic() {
        val result = parseInline("**bold and *italic* here**", testColors)
        assertEquals("bold and italic here", result.text)
        assertTrue(result.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(result.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun empty_input() {
        val result = parseInline("", testColors)
        assertEquals("", result.text)
    }
}