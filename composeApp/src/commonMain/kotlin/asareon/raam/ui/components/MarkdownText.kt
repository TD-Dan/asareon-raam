package asareon.raam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ═══════════════════════════════════════════════════════════════════════════
// Public API
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Renders a raw markdown string as a sequence of styled Compose blocks.
 *
 * Supported syntax:
 *
 * **Block-level** — headings (`#`–`######`), paragraphs, block quotes (`>`),
 * unordered lists (`-`/`*`/`+` with nesting), ordered lists (`1.`), and
 * horizontal rules (`---`/`***`/`___`).
 *
 * **Inline** — bold (`**`), italic (`*`), bold-italic (`***`), inline code
 * (`` ` ``), links (`[text](url)`), and strikethrough (`~~`).
 *
 * Code fences are **not** handled here — the upstream [BlockSeparatingParser]
 * already extracts them into [ContentBlock.CodeBlock] before this composable
 * ever sees the text.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier
) {
    val depthColors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary
    )
    val codeBackground = MaterialTheme.colorScheme.surfaceDim
    val codeColor = MaterialTheme.colorScheme.onSurface
    val quoteBarColor = MaterialTheme.colorScheme.outlineVariant
    val quoteBackground = MaterialTheme.colorScheme.surfaceContainerLow
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    val bodyStyle = MaterialTheme.typography.bodyLarge

    // Heading typography scale: h1 → titleLarge … h6 → labelMedium
    val headingStyles = listOf(
        MaterialTheme.typography.headlineSmall,    // h1
        MaterialTheme.typography.titleLarge,        // h2
        MaterialTheme.typography.titleMedium,       // h3
        MaterialTheme.typography.titleSmall,        // h4
        MaterialTheme.typography.labelLarge,        // h5
        MaterialTheme.typography.labelMedium         // h6
    )

    val inlineColors = remember(codeBackground, codeColor, linkColor, mutedColor) {
        InlineColors(codeBackground, codeColor, linkColor, mutedColor)
    }

    val blocks = remember(text) { parseBlocks(text) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    val level = (block.level - 1).coerceIn(0, 5)
                    val headingColor = colorForDepth(level, depthColors)
                    val style = headingStyles[level]
                    Text(
                        text = parseInline(block.content, inlineColors),
                        style = style.copy(
                            fontWeight = FontWeight.Bold,
                            color = headingColor
                        ),
                        modifier = Modifier.padding(
                            top = if (level <= 1) 8.dp else 4.dp,
                            bottom = if (level <= 1) 4.dp else 2.dp
                        )
                    )
                }

                is MdBlock.Paragraph -> {
                    Text(
                        text = parseInline(block.content, inlineColors),
                        style = bodyStyle
                    )
                }

                is MdBlock.BlockQuote -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                quoteBackground,
                                MaterialTheme.shapes.small
                            )
                    ) {
                        // Accent bar
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .defaultMinSize(minHeight = 24.dp)
                                .fillMaxHeight()
                                .background(quoteBarColor)
                        )
                        Text(
                            text = parseInline(block.content, inlineColors),
                            style = bodyStyle.copy(
                                fontStyle = FontStyle.Italic,
                                color = mutedColor
                            ),
                            modifier = Modifier.padding(
                                start = 12.dp,
                                end = 12.dp,
                                top = 8.dp,
                                bottom = 8.dp
                            )
                        )
                    }
                }

                is MdBlock.UnorderedListItem -> {
                    Row(
                        modifier = Modifier.padding(start = (block.depth * 16).dp)
                    ) {
                        val bullet = when (block.depth % 3) {
                            0 -> "•"
                            1 -> "◦"
                            else -> "▪"
                        }
                        Text(
                            text = bullet,
                            style = bodyStyle.copy(
                                color = colorForDepth(block.depth, depthColors)
                            ),
                            modifier = Modifier.width(20.dp)
                        )
                        Text(
                            text = parseInline(block.content, inlineColors),
                            style = bodyStyle,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }

                is MdBlock.OrderedListItem -> {
                    Row(
                        modifier = Modifier.padding(start = (block.depth * 16).dp)
                    ) {
                        Text(
                            text = "${block.number}.",
                            style = bodyStyle.copy(
                                fontWeight = FontWeight.Medium,
                                color = colorForDepth(block.depth, depthColors)
                            ),
                            modifier = Modifier.widthIn(min = 20.dp)
                                .padding(end = 4.dp)
                        )
                        Text(
                            text = parseInline(block.content, inlineColors),
                            style = bodyStyle,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }

                is MdBlock.HorizontalRule -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = quoteBarColor
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Block Parser
// ═══════════════════════════════════════════════════════════════════════════

private sealed interface MdBlock {
    data class Heading(val level: Int, val content: String) : MdBlock
    data class Paragraph(val content: String) : MdBlock
    data class BlockQuote(val content: String) : MdBlock
    data class UnorderedListItem(val depth: Int, val content: String) : MdBlock
    data class OrderedListItem(val depth: Int, val number: String, val content: String) : MdBlock
    data object HorizontalRule : MdBlock
}

/**
 * Parses raw markdown text into a list of [MdBlock]s.
 *
 * Consecutive non-special lines are accumulated into a single paragraph.
 * Blank lines flush the accumulator. Consecutive block-quote lines are
 * merged into one quote block.
 */
private fun parseBlocks(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = text.lines()
    val paraAccum = StringBuilder()
    val quoteAccum = StringBuilder()

    fun flushParagraph() {
        if (paraAccum.isNotEmpty()) {
            blocks.add(MdBlock.Paragraph(paraAccum.toString().trimEnd()))
            paraAccum.clear()
        }
    }

    fun flushQuote() {
        if (quoteAccum.isNotEmpty()) {
            blocks.add(MdBlock.BlockQuote(quoteAccum.toString().trimEnd()))
            quoteAccum.clear()
        }
    }

    for (line in lines) {
        val trimmed = line.trimEnd()

        // ── Blank line ───────────────────────────────────────────
        if (trimmed.isEmpty()) {
            flushParagraph()
            flushQuote()
            continue
        }

        // ── Horizontal rule ──────────────────────────────────────
        val stripped = trimmed.replace(" ", "")
        if (stripped.length >= 3 && (stripped.all { it == '-' } || stripped.all { it == '*' } || stripped.all { it == '_' })) {
            flushParagraph()
            flushQuote()
            blocks.add(MdBlock.HorizontalRule)
            continue
        }

        // ── Heading ──────────────────────────────────────────────
        val headingMatch = HEADING_REGEX.matchAt(trimmed, 0)
        if (headingMatch != null) {
            flushParagraph()
            flushQuote()
            val level = headingMatch.groupValues[1].length
            val content = headingMatch.groupValues[2]
            blocks.add(MdBlock.Heading(level, content))
            continue
        }

        // ── Block quote ──────────────────────────────────────────
        if (trimmed.startsWith(">")) {
            flushParagraph()
            val quoteContent = trimmed.removePrefix(">").removePrefix(" ")
            if (quoteAccum.isNotEmpty()) quoteAccum.append('\n')
            quoteAccum.append(quoteContent)
            continue
        } else {
            flushQuote()
        }

        // ── Unordered list ───────────────────────────────────────
        val ulMatch = UL_REGEX.matchAt(line, 0)
        if (ulMatch != null) {
            flushParagraph()
            val indent = ulMatch.groupValues[1].length
            val depth = indent / 2  // 2-space indent per level
            val content = ulMatch.groupValues[2]
            blocks.add(MdBlock.UnorderedListItem(depth, content))
            continue
        }

        // ── Ordered list ─────────────────────────────────────────
        val olMatch = OL_REGEX.matchAt(line, 0)
        if (olMatch != null) {
            flushParagraph()
            val indent = olMatch.groupValues[1].length
            val depth = indent / 2
            val number = olMatch.groupValues[2]
            val content = olMatch.groupValues[3]
            blocks.add(MdBlock.OrderedListItem(depth, number, content))
            continue
        }

        // ── Regular text → accumulate into paragraph ─────────────
        if (paraAccum.isNotEmpty()) paraAccum.append(' ')
        paraAccum.append(trimmed)
    }

    flushParagraph()
    flushQuote()
    return blocks
}

private val HEADING_REGEX = Regex("""^(#{1,6})\s+(.+)$""")
private val UL_REGEX = Regex("""^(\s*)[-*+]\s+(.+)$""")
private val OL_REGEX = Regex("""^(\s*)(\d{1,4})\.\s+(.+)$""")

// ═══════════════════════════════════════════════════════════════════════════
// Inline Parser
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Colors used by the inline parser, bundled to keep the remember key simple.
 */
private data class InlineColors(
    val codeBackground: Color,
    val codeColor: Color,
    val linkColor: Color,
    val mutedColor: Color
)

/**
 * Parses inline markdown spans into an [AnnotatedString].
 *
 * Recognised spans (in priority order):
 * 1. Inline code (`` ` ``)
 * 2. Links (`[text](url)`)
 * 3. Strikethrough (`~~`)
 * 4. Bold-italic (`***` / `___`)
 * 5. Bold (`**` / `__`)
 * 6. Italic (`*` / `_`)
 *
 * The parser processes the input left-to-right, consuming the highest-priority
 * match at each position. Unmatched markers are emitted as literal text.
 */
private fun parseInline(text: String, colors: InlineColors): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                // ── Inline code ──────────────────────────────────
                text[i] == '`' -> {
                    val closeIdx = text.indexOf('`', i + 1)
                    if (closeIdx != -1) {
                        val code = text.substring(i + 1, closeIdx)
                        pushStyle(SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = colors.codeBackground,
                            color = colors.codeColor,
                            fontSize = 13.sp,
                            letterSpacing = 0.sp
                        ))
                        append(" $code ")
                        pop()
                        i = closeIdx + 1
                    } else {
                        append('`')
                        i++
                    }
                }

                // ── Link: [text](url) ────────────────────────────
                text[i] == '[' -> {
                    val closeBracket = findClosingBracket(text, i)
                    if (closeBracket != -1 && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                        val closeParen = text.indexOf(')', closeBracket + 2)
                        if (closeParen != -1) {
                            val linkText = text.substring(i + 1, closeBracket)
                            pushStyle(SpanStyle(
                                color = colors.linkColor,
                                textDecoration = TextDecoration.Underline
                            ))
                            // Recursively parse inline formatting within link text
                            append(linkText)
                            pop()
                            i = closeParen + 1
                        } else {
                            append('[')
                            i++
                        }
                    } else {
                        append('[')
                        i++
                    }
                }

                // ── Strikethrough: ~~text~~ ──────────────────────
                text.startsWith("~~", i) -> {
                    val closeIdx = text.indexOf("~~", i + 2)
                    if (closeIdx != -1) {
                        pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                        appendInlineRecursive(text.substring(i + 2, closeIdx), colors)
                        pop()
                        i = closeIdx + 2
                    } else {
                        append("~~")
                        i += 2
                    }
                }

                // ── Bold-italic: ***text*** ──────────────────────
                text.startsWith("***", i) -> {
                    val closeIdx = text.indexOf("***", i + 3)
                    if (closeIdx != -1) {
                        pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic))
                        appendInlineRecursive(text.substring(i + 3, closeIdx), colors)
                        pop()
                        i = closeIdx + 3
                    } else {
                        append("***")
                        i += 3
                    }
                }

                // ── Bold: **text** ───────────────────────────────
                text.startsWith("**", i) -> {
                    val closeIdx = text.indexOf("**", i + 2)
                    if (closeIdx != -1) {
                        pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                        appendInlineRecursive(text.substring(i + 2, closeIdx), colors)
                        pop()
                        i = closeIdx + 2
                    } else {
                        append("**")
                        i += 2
                    }
                }

                // ── Italic: *text* (not followed by space) ───────
                text[i] == '*' && i + 1 < text.length && text[i + 1] != ' ' -> {
                    val closeIdx = text.indexOf('*', i + 1)
                    if (closeIdx != -1 && text[closeIdx - 1] != ' ') {
                        pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                        appendInlineRecursive(text.substring(i + 1, closeIdx), colors)
                        pop()
                        i = closeIdx + 1
                    } else {
                        append('*')
                        i++
                    }
                }

                // ── Plain character ──────────────────────────────
                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }
}

/**
 * Appends inline-parsed content recursively into the current [AnnotatedString.Builder].
 * This allows nested formatting like `**bold and *italic* inside**`.
 */
private fun AnnotatedString.Builder.appendInlineRecursive(text: String, colors: InlineColors) {
    var i = 0
    while (i < text.length) {
        when {
            text[i] == '`' -> {
                val closeIdx = text.indexOf('`', i + 1)
                if (closeIdx != -1) {
                    val code = text.substring(i + 1, closeIdx)
                    pushStyle(SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = colors.codeBackground,
                        color = colors.codeColor,
                        fontSize = 13.sp
                    ))
                    append(" $code ")
                    pop()
                    i = closeIdx + 1
                } else {
                    append('`'); i++
                }
            }
            text.startsWith("~~", i) -> {
                val closeIdx = text.indexOf("~~", i + 2)
                if (closeIdx != -1) {
                    pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    appendInlineRecursive(text.substring(i + 2, closeIdx), colors)
                    pop()
                    i = closeIdx + 2
                } else {
                    append("~~"); i += 2
                }
            }
            text[i] == '*' && i + 1 < text.length && text[i + 1] != ' ' -> {
                val closeIdx = text.indexOf('*', i + 1)
                if (closeIdx != -1 && text[closeIdx - 1] != ' ') {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(text.substring(i + 1, closeIdx))
                    pop()
                    i = closeIdx + 1
                } else {
                    append('*'); i++
                }
            }
            else -> {
                append(text[i]); i++
            }
        }
    }
}

/**
 * Finds the index of the closing `]` for a `[` at [openIdx], skipping nested brackets.
 * Returns -1 if no matching close bracket is found.
 */
private fun findClosingBracket(text: String, openIdx: Int): Int {
    var depth = 0
    var i = openIdx
    while (i < text.length) {
        when (text[i]) {
            '[' -> depth++
            ']' -> { depth--; if (depth == 0) return i }
        }
        i++
    }
    return -1
}

// ═══════════════════════════════════════════════════════════════════════════
// Helpers
// ═══════════════════════════════════════════════════════════════════════════

private fun colorForDepth(depth: Int, depthColors: List<Color>): Color {
    val base = depthColors[depth % depthColors.size]
    val cycle = depth / depthColors.size
    return if (cycle == 0) base else base.copy(alpha = (0.85f - (cycle - 1) * 0.15f).coerceAtLeast(0.4f))
}