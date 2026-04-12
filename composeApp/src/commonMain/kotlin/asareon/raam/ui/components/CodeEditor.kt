package asareon.raam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The syntax highlighting mode for [CodeEditor].
 */
enum class SyntaxMode { NONE, XML, JSON, MARKDOWN, LUA }

/**
 * A reusable monospace text editor with optional syntax highlighting.
 *
 * Used across the app for: ledger code blocks (read-only), agent resource editing,
 * knowledge graph holon viewing/editing, and anywhere code or structured text appears.
 *
 * @param syntax The highlighting mode. Auto-detection is left to the caller.
 * @param bordered When false, suppresses the outline border (e.g. when hosted inside
 *   a Surface that already provides visual containment like ledger code blocks).
 */
@Composable
fun CodeEditor(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    syntax: SyntaxMode = SyntaxMode.NONE,
    bordered: Boolean = true
) {
    val depthColors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary
    )
    val outlineColor = MaterialTheme.colorScheme.outline
    val outlineVariantColor = MaterialTheme.colorScheme.outlineVariant
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant

    val transformation = remember(
        syntax, depthColors, outlineColor, outlineVariantColor,
        tertiaryColor, onSurfaceVariantColor
    ) {
        when (syntax) {
            SyntaxMode.NONE -> VisualTransformation.None
            SyntaxMode.XML -> XmlHighlightTransformation(depthColors, outlineColor, tertiaryColor, outlineVariantColor)
            SyntaxMode.JSON -> JsonHighlightTransformation(depthColors, outlineColor)
            SyntaxMode.MARKDOWN -> MarkdownHighlightTransformation(depthColors, outlineColor, onSurfaceVariantColor)
            SyntaxMode.LUA -> LuaHighlightTransformation(depthColors, outlineColor, tertiaryColor, onSurfaceVariantColor)
        }
    }

    val borderMod = if (bordered) {
        Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
    } else Modifier

    val lineCount = remember(value) { value.count { it == '\n' } + 1 }
    val lineNumberColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)

    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(borderMod)
            .background(Color.Transparent, MaterialTheme.shapes.small)
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(verticalScrollState)
        ) {
            // Line number gutter — scrolls vertically with the text
            Column(
                modifier = Modifier.padding(end = 8.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.End
            ) {
                for (i in 1..lineCount) {
                    Text(
                        text = i.toString(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 20.sp,
                        color = lineNumberColor,
                        modifier = Modifier.height(20.dp)
                    )
                }
            }

            // Editor — no soft-wrap, scrolls horizontally for long lines
            Box(modifier = Modifier.weight(1f).horizontalScroll(horizontalScrollState)) {
                BasicTextField(
                    value = value,
                    onValueChange = { if (!readOnly) onValueChange(it) },
                    modifier = Modifier
                        .testTag("code_editor_input"),
                    readOnly = readOnly,
                    softWrap = false,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 20.sp
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = transformation
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// XML Syntax Highlighting
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Single-pass XML tokenizer. Tag names/brackets colored by nesting depth,
 * cycling through [depthColors] with fading alpha on repeat cycles.
 * Attribute names get [attrNameColor], quoted values get [attrValueColor],
 * comments/CDATA/PIs get [commentColor] italic.
 */
private class XmlHighlightTransformation(
    private val depthColors: List<Color>,
    private val attrNameColor: Color,
    private val attrValueColor: Color,
    private val commentColor: Color
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val src = text.text
        if (src.isEmpty()) return TransformedText(text, OffsetMapping.Identity)

        val builder = AnnotatedString.Builder(src)
        var i = 0
        var depth = 0

        while (i < src.length) {
            when {
                src.startsWith("<!--", i) -> {
                    val end = src.indexOf("-->", i + 4)
                    val commentEnd = if (end == -1) src.length else end + 3
                    builder.addStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic), i, commentEnd)
                    i = commentEnd
                }
                src.startsWith("<![CDATA[", i) -> {
                    val end = src.indexOf("]]>", i + 9)
                    val cdataEnd = if (end == -1) src.length else end + 3
                    builder.addStyle(SpanStyle(color = commentColor), i, cdataEnd)
                    i = cdataEnd
                }
                src.startsWith("<?", i) -> {
                    val end = src.indexOf("?>", i + 2)
                    val piEnd = if (end == -1) src.length else end + 2
                    builder.addStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic), i, piEnd)
                    i = piEnd
                }
                src.startsWith("</", i) -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    val tagColor = colorForDepth(depth)
                    val gt = src.indexOf('>', i + 2)
                    val tagEnd = if (gt == -1) src.length else gt + 1
                    builder.addStyle(SpanStyle(color = tagColor), i, tagEnd)
                    i = tagEnd
                }
                src[i] == '<' && i + 1 < src.length && (src[i + 1].isLetter() || src[i + 1] == '_') -> {
                    val tagColor = colorForDepth(depth)
                    builder.addStyle(SpanStyle(color = tagColor), i, i + 1)
                    i++
                    val nameStart = i
                    while (i < src.length && (src[i].isLetterOrDigit() || src[i] in "_-.:")) i++
                    builder.addStyle(SpanStyle(color = tagColor), nameStart, i)
                    var selfClosing = false
                    while (i < src.length && src[i] != '>') {
                        when {
                            src[i] == '/' && i + 1 < src.length && src[i + 1] == '>' -> {
                                selfClosing = true
                                builder.addStyle(SpanStyle(color = tagColor), i, i + 2)
                                i += 2; break
                            }
                            src[i].isWhitespace() -> i++
                            src[i].isLetter() || src[i] == '_' -> {
                                val aStart = i
                                while (i < src.length && (src[i].isLetterOrDigit() || src[i] in "_-.:")) i++
                                builder.addStyle(SpanStyle(color = attrNameColor), aStart, i)
                                while (i < src.length && src[i].isWhitespace()) i++
                                if (i < src.length && src[i] == '=') {
                                    i++
                                    while (i < src.length && src[i].isWhitespace()) i++
                                    if (i < src.length && (src[i] == '"' || src[i] == '\'')) {
                                        val quote = src[i]; val vStart = i; i++
                                        while (i < src.length && src[i] != quote) i++
                                        if (i < src.length) i++
                                        builder.addStyle(SpanStyle(color = attrValueColor), vStart, i)
                                    }
                                }
                            }
                            else -> i++
                        }
                    }
                    if (i < src.length && src[i] == '>') {
                        builder.addStyle(SpanStyle(color = tagColor), i, i + 1); i++
                    }
                    if (!selfClosing) depth++
                }
                else -> i++
            }
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }

    private fun colorForDepth(depth: Int): Color {
        val base = depthColors[depth % depthColors.size]
        val cycle = depth / depthColors.size
        return if (cycle == 0) base else base.copy(alpha = (0.85f - (cycle - 1) * 0.15f).coerceAtLeast(0.4f))
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// JSON Syntax Highlighting
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Single-pass JSON tokenizer with depth-based coloring.
 * - **Object keys**: colored by nesting depth, cycling through [depthColors].
 * - **Structural chars** (`{}[]:,`): colored by current depth.
 * - **All values** (strings, numbers, booleans, null): [valueColor].
 */
private class JsonHighlightTransformation(
    private val depthColors: List<Color>,
    private val valueColor: Color
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val src = text.text
        if (src.isEmpty()) return TransformedText(text, OffsetMapping.Identity)

        val builder = AnnotatedString.Builder(src)
        var i = 0
        var depth = 0

        while (i < src.length) {
            when {
                src[i].isWhitespace() -> i++

                src[i] == '{' || src[i] == '[' -> {
                    builder.addStyle(SpanStyle(color = colorForDepth(depth)), i, i + 1)
                    depth++; i++
                }
                src[i] == '}' || src[i] == ']' -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    builder.addStyle(SpanStyle(color = colorForDepth(depth)), i, i + 1)
                    i++
                }
                src[i] == ':' || src[i] == ',' -> {
                    builder.addStyle(SpanStyle(color = colorForDepth(depth)), i, i + 1); i++
                }

                src[i] == '"' -> {
                    val start = i; i++
                    while (i < src.length && src[i] != '"') {
                        if (src[i] == '\\' && i + 1 < src.length) i += 2 else i++
                    }
                    if (i < src.length) i++ // closing quote
                    // Peek ahead past whitespace for ':' to distinguish key from value
                    var peek = i
                    while (peek < src.length && src[peek].isWhitespace()) peek++
                    val color = if (peek < src.length && src[peek] == ':') colorForDepth(depth) else valueColor
                    builder.addStyle(SpanStyle(color = color), start, i)
                }

                src[i] == '-' || src[i].isDigit() -> {
                    val start = i
                    if (src[i] == '-') i++
                    while (i < src.length && src[i].isDigit()) i++
                    if (i < src.length && src[i] == '.') { i++; while (i < src.length && src[i].isDigit()) i++ }
                    if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
                        i++
                        if (i < src.length && (src[i] == '+' || src[i] == '-')) i++
                        while (i < src.length && src[i].isDigit()) i++
                    }
                    builder.addStyle(SpanStyle(color = valueColor), start, i)
                }

                src.startsWith("true", i) && (i + 4 >= src.length || !src[i + 4].isLetterOrDigit()) -> {
                    builder.addStyle(SpanStyle(color = valueColor, fontWeight = FontWeight.Bold), i, i + 4); i += 4
                }
                src.startsWith("false", i) && (i + 5 >= src.length || !src[i + 5].isLetterOrDigit()) -> {
                    builder.addStyle(SpanStyle(color = valueColor, fontWeight = FontWeight.Bold), i, i + 5); i += 5
                }
                src.startsWith("null", i) && (i + 4 >= src.length || !src[i + 4].isLetterOrDigit()) -> {
                    builder.addStyle(SpanStyle(color = valueColor, fontStyle = FontStyle.Italic), i, i + 4); i += 4
                }

                else -> i++
            }
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }

    private fun colorForDepth(depth: Int): Color {
        val base = depthColors[depth % depthColors.size]
        val cycle = depth / depthColors.size
        return if (cycle == 0) base else base.copy(alpha = (0.85f - (cycle - 1) * 0.15f).coerceAtLeast(0.4f))
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Markdown Syntax Highlighting
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Line-oriented markdown tokenizer. Processes each line for:
 * - **Headings** (`# ` … `###### `): depth-based coloring via [depthColors], bold.
 * - **Bold** (`**…**` / `__…__`): bold weight only, no color change.
 * - **Italic** (`*…*` / `_…_`, single): italic style only, no color change.
 * - **Inline code** (`` `…` ``): [codeColor].
 * - **Block quotes** (`> `): [mutedColor].
 * - **List markers** (`- `, `* `, `1. `): depth 0 color from [depthColors].
 * - **Links** (`[text](url)`): text in depth 0 color, url underlined in [mutedColor].
 * - **Horizontal rules** (`---`, `***`, `___`): [mutedColor].
 */
private class MarkdownHighlightTransformation(
    private val depthColors: List<Color>,
    private val codeColor: Color,
    private val mutedColor: Color
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val src = text.text
        if (src.isEmpty()) return TransformedText(text, OffsetMapping.Identity)

        val builder = AnnotatedString.Builder(src)
        var lineStart = 0

        while (lineStart < src.length) {
            val lineEnd = src.indexOf('\n', lineStart).let { if (it == -1) src.length else it }
            val line = src.substring(lineStart, lineEnd)

            when {
                // Heading
                line.startsWith("######") || line.startsWith("#####") ||
                        line.startsWith("####") || line.startsWith("###") ||
                        line.startsWith("##") || line.startsWith("#") -> {
                    val hashEnd = line.indexOfFirst { it != '#' }
                    if (hashEnd > 0 && hashEnd <= 6 && (hashEnd >= line.length || line[hashEnd] == ' ')) {
                        val headingDepth = (hashEnd - 1).coerceAtLeast(0) // # = 0, ## = 1, ...
                        builder.addStyle(SpanStyle(color = colorForDepth(headingDepth), fontWeight = FontWeight.Bold), lineStart, lineEnd)
                    } else {
                        highlightInline(builder, src, lineStart, lineEnd)
                    }
                }
                // Block quote
                line.trimStart().startsWith(">") -> {
                    builder.addStyle(SpanStyle(color = mutedColor, fontStyle = FontStyle.Italic), lineStart, lineEnd)
                }
                // Horizontal rule
                line.trim().let { t -> t.length >= 3 && t.all { it == '-' || it == '*' || it == '_' } && t.count { it == t[0] } >= 3 } -> {
                    builder.addStyle(SpanStyle(color = mutedColor), lineStart, lineEnd)
                }
                // Unordered list marker
                line.trimStart().let { it.startsWith("- ") || it.startsWith("* ") || it.startsWith("+ ") } -> {
                    val markerOffset = line.length - line.trimStart().length
                    builder.addStyle(SpanStyle(color = colorForDepth(0), fontWeight = FontWeight.Bold), lineStart + markerOffset, lineStart + markerOffset + 2)
                    highlightInline(builder, src, lineStart + markerOffset + 2, lineEnd)
                }
                // Ordered list marker
                line.trimStart().let { t -> t.isNotEmpty() && t[0].isDigit() && t.contains(". ") && t.indexOf(". ") <= 4 } -> {
                    val markerOffset = line.length - line.trimStart().length
                    val dotSpace = line.indexOf(". ", markerOffset)
                    if (dotSpace != -1) {
                        builder.addStyle(SpanStyle(color = colorForDepth(0), fontWeight = FontWeight.Bold), lineStart + markerOffset, lineStart + dotSpace + 2)
                        highlightInline(builder, src, lineStart + dotSpace + 2, lineEnd)
                    } else {
                        highlightInline(builder, src, lineStart, lineEnd)
                    }
                }
                else -> highlightInline(builder, src, lineStart, lineEnd)
            }

            lineStart = lineEnd + 1 // skip the \n
        }

        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }

    /** Highlights inline spans (bold, italic, code, links) within [start, end). */
    private fun highlightInline(builder: AnnotatedString.Builder, src: String, start: Int, end: Int) {
        var i = start
        while (i < end) {
            when {
                // Inline code
                src[i] == '`' -> {
                    val codeStart = i; i++
                    while (i < end && src[i] != '`') i++
                    if (i < end) i++ // closing backtick
                    builder.addStyle(SpanStyle(color = codeColor), codeStart, i)
                }
                // Bold (**…** or __…__)
                i + 1 < end && ((src[i] == '*' && src[i + 1] == '*') || (src[i] == '_' && src[i + 1] == '_')) -> {
                    val marker = src.substring(i, i + 2)
                    val contentStart = i; i += 2
                    val closeIdx = src.indexOf(marker, i)
                    if (closeIdx != -1 && closeIdx < end) {
                        builder.addStyle(SpanStyle(fontWeight = FontWeight.Bold), contentStart, closeIdx + 2)
                        i = closeIdx + 2
                    }
                }
                // Italic (*…* or _…_) — single marker, must not be followed by space
                (src[i] == '*' || src[i] == '_') && i + 1 < end && src[i + 1] != ' ' -> {
                    val marker = src[i]
                    val contentStart = i; i++
                    while (i < end && src[i] != marker) i++
                    if (i < end) { i++; builder.addStyle(SpanStyle(fontStyle = FontStyle.Italic), contentStart, i) }
                }
                // Link: [text](url)
                src[i] == '[' -> {
                    val bracketStart = i; i++
                    val closeBracket = src.indexOf(']', i)
                    if (closeBracket != -1 && closeBracket < end && closeBracket + 1 < end && src[closeBracket + 1] == '(') {
                        val closeParen = src.indexOf(')', closeBracket + 2)
                        if (closeParen != -1 && closeParen <= end) {
                            builder.addStyle(SpanStyle(color = colorForDepth(0)), bracketStart, closeBracket + 1)
                            builder.addStyle(SpanStyle(color = mutedColor, textDecoration = TextDecoration.Underline), closeBracket + 1, closeParen + 1)
                            i = closeParen + 1
                        } else i = bracketStart + 1
                    } else i = bracketStart + 1
                }
                else -> i++
            }
        }
    }

    private fun colorForDepth(depth: Int): Color {
        val base = depthColors[depth % depthColors.size]
        val cycle = depth / depthColors.size
        return if (cycle == 0) base else base.copy(alpha = (0.85f - (cycle - 1) * 0.15f).coerceAtLeast(0.4f))
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Lua Syntax Highlighting
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Single-pass Lua tokenizer. Keywords get bold keyword color, strings get string color,
 * comments get italic comment color, numbers get secondary color.
 */
private class LuaHighlightTransformation(
    private val depthColors: List<Color>,
    private val commentColor: Color,
    private val keywordColor: Color,
    private val stringColor: Color
) : VisualTransformation {

    companion object {
        private val KEYWORDS = setOf(
            "and", "break", "do", "else", "elseif", "end", "false", "for",
            "function", "goto", "if", "in", "local", "nil", "not", "or",
            "repeat", "return", "then", "true", "until", "while"
        )
        private val BUILTINS = setOf(
            "print", "type", "tostring", "tonumber", "pairs", "ipairs",
            "select", "unpack", "error", "pcall", "xpcall", "assert",
            "setmetatable", "getmetatable", "rawget", "rawset", "next"
        )
        private val WORD_REGEX = Regex("[a-zA-Z_][a-zA-Z0-9_]*")
    }

    override fun filter(text: AnnotatedString): TransformedText {
        val src = text.text
        if (src.isEmpty()) return TransformedText(text, OffsetMapping.Identity)

        val builder = AnnotatedString.Builder(src)
        var i = 0

        while (i < src.length) {
            when {
                // Block comment: --[[ ... ]]
                src.startsWith("--[[", i) -> {
                    val end = src.indexOf("]]", i + 4)
                    val commentEnd = if (end == -1) src.length else end + 2
                    builder.addStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic), i, commentEnd)
                    i = commentEnd
                }
                // Line comment: -- ...
                src.startsWith("--", i) -> {
                    val lineEnd = src.indexOf('\n', i)
                    val commentEnd = if (lineEnd == -1) src.length else lineEnd
                    builder.addStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic), i, commentEnd)
                    i = commentEnd
                }
                // Long string: [[ ... ]]
                src.startsWith("[[", i) && (i == 0 || src[i - 1] != '-') -> {
                    val end = src.indexOf("]]", i + 2)
                    val strEnd = if (end == -1) src.length else end + 2
                    builder.addStyle(SpanStyle(color = stringColor), i, strEnd)
                    i = strEnd
                }
                // Double-quoted string
                src[i] == '"' -> {
                    val strEnd = findStringEnd(src, i, '"')
                    builder.addStyle(SpanStyle(color = stringColor), i, strEnd)
                    i = strEnd
                }
                // Single-quoted string
                src[i] == '\'' -> {
                    val strEnd = findStringEnd(src, i, '\'')
                    builder.addStyle(SpanStyle(color = stringColor), i, strEnd)
                    i = strEnd
                }
                // Numbers
                src[i].isDigit() || (src[i] == '.' && i + 1 < src.length && src[i + 1].isDigit()) -> {
                    val numEnd = findNumberEnd(src, i)
                    builder.addStyle(SpanStyle(color = depthColors[1 % depthColors.size]), i, numEnd)
                    i = numEnd
                }
                // Words (keywords, builtins, identifiers)
                src[i].isLetter() || src[i] == '_' -> {
                    val match = WORD_REGEX.find(src, i)
                    if (match != null && match.range.first == i) {
                        val word = match.value
                        val wordEnd = match.range.last + 1
                        when {
                            word in KEYWORDS -> builder.addStyle(
                                SpanStyle(color = keywordColor, fontWeight = FontWeight.Bold), i, wordEnd
                            )
                            word in BUILTINS -> builder.addStyle(
                                SpanStyle(color = depthColors[0 % depthColors.size]), i, wordEnd
                            )
                        }
                        i = wordEnd
                    } else {
                        i++
                    }
                }
                else -> i++
            }
        }

        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }

    private fun findStringEnd(src: String, start: Int, quote: Char): Int {
        var i = start + 1
        while (i < src.length) {
            if (src[i] == '\\') { i += 2; continue }
            if (src[i] == quote) return i + 1
            if (src[i] == '\n') return i
            i++
        }
        return src.length
    }

    private fun findNumberEnd(src: String, start: Int): Int {
        var i = start
        if (i + 1 < src.length && src[i] == '0' && (src[i + 1] == 'x' || src[i + 1] == 'X')) {
            i += 2
            while (i < src.length && src[i].isLetterOrDigit()) i++
            return i
        }
        while (i < src.length && src[i].isDigit()) i++
        if (i < src.length && src[i] == '.') {
            i++
            while (i < src.length && src[i].isDigit()) i++
        }
        if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
            i++
            if (i < src.length && (src[i] == '+' || src[i] == '-')) i++
            while (i < src.length && src[i].isDigit()) i++
        }
        return i
    }
}