package asareon.raam.feature.session

/**
 * A robust, state-machine-based parser for the AUF's tagged text format.
 * This class's sole responsibility is to convert a raw string from an AI or User into a structured
 * list of `ContentBlock`s. It is the single source of truth for interpreting the AUF's
 * simplified markdown-based data contract.
 *
 * This version uses a nesting-aware fence matcher to correctly handle nested code fences,
 * inline fences, and newline preservation as per the established contract.
 *
 * Additionally, it recognises XML-wrapped blocks (e.g. `<think>...</think>`,
 * `<reasoning>...</reasoning>`) and emits them as [ContentBlock.CodeBlock] instances
 * with `language = "xml"` and the full tagged content (including opening and closing
 * tags) as the code body. This allows the UI to render these as collapsible blocks
 * by detecting the `"xml"` language and extracting the tag name from the content.
 *
 * Nesting rule: A ``` followed immediately by a letter, digit, or underscore (e.g. ```kotlin)
 * is treated as an **opening** fence that increments nesting depth. A ``` followed by whitespace,
 * a newline, or end-of-string is treated as a **closing** fence that decrements depth. The true
 * closing fence for an outer block is the one that brings the depth back to zero.
 *
 * Line-start rule: Inside an already-opened code block, only ``` sequences that appear at the
 * start of a line OR at the end of a line are considered as fence boundaries. A ``` that has
 * non-whitespace content on BOTH sides of it on the same line is treated as noise and skipped.
 * This prevents ``` embedded mid-line (e.g. inside JSON string values in tool-call payloads)
 * from being misidentified as code fence delimiters. Exception: on the opening fence's own line
 * (before the first newline), all fences are considered so that single-line blocks like
 * ```raam_toast Hello!``` continue to work.
 */
class BlockSeparatingParser {

    companion object {
        /**
         * Pattern matching the opening of an XML tag block.
         *
         * Matches `<tagname>` where tagname is a lowercase identifier: `[a-z_][a-z0-9_-]*`.
         * Only tags that appear at the very start of the remaining text or immediately after
         * a newline are considered — this prevents matching stray `<` characters inside prose
         * or HTML fragments.
         *
         * The captured group 1 is the tag name.
         */
        private val XML_TAG_OPEN = Regex("""<([a-z_][a-z0-9_-]*)>""")
    }

    fun parse(rawText: String): List<ContentBlock> {
        if (rawText.isBlank()) return emptyList()

        val blocks = mutableListOf<ContentBlock>()
        var currentIndex = 0

        while (currentIndex < rawText.length) {
            val remaining = rawText.substring(currentIndex)

            // Find the next structural boundary: code fence or XML tag opener.
            val fenceStart = rawText.indexOf("```", currentIndex)
            val xmlMatch = findNextXmlTagOpen(rawText, currentIndex)

            // Determine which comes first.
            val fencePos = if (fenceStart != -1) fenceStart else Int.MAX_VALUE
            val xmlPos = xmlMatch?.first ?: Int.MAX_VALUE

            if (fencePos == Int.MAX_VALUE && xmlPos == Int.MAX_VALUE) {
                // No more structural elements — rest is text.
                blocks.add(ContentBlock.Text(remaining))
                break
            }

            if (fencePos <= xmlPos) {
                // --- CODE FENCE comes first ---
                val textBefore = rawText.substring(currentIndex, fenceStart)
                if (textBefore.isNotEmpty()) {
                    blocks.add(ContentBlock.Text(textBefore))
                }

                val fenceEnd = findClosingFence(rawText, fenceStart + 3)
                if (fenceEnd == -1) {
                    val innerContent = rawText.substring(fenceStart + 3)
                    blocks.add(parseInnerCodeBlock(innerContent))
                    break
                }

                val innerContent = rawText.substring(fenceStart + 3, fenceEnd)
                blocks.add(parseInnerCodeBlock(innerContent))
                currentIndex = fenceEnd + 3
            } else {
                // --- XML TAG comes first ---
                val (tagStart, tagName) = xmlMatch!!
                val closingTag = "</$tagName>"
                val closingPos = rawText.indexOf(closingTag, tagStart)

                if (closingPos == -1) {
                    // Unterminated XML tag — treat from here to end as text (not a tag block).
                    blocks.add(ContentBlock.Text(remaining))
                    break
                }

                // Add text before the XML tag, trimming one trailing newline
                // to prevent double-spacing between the text and the collapsible block.
                val textBefore = rawText.substring(currentIndex, tagStart).removeSuffix("\n")
                if (textBefore.isNotEmpty()) {
                    blocks.add(ContentBlock.Text(textBefore))
                }

                // Capture the full tagged content including opening and closing tags.
                val fullTaggedContent = rawText.substring(tagStart, closingPos + closingTag.length)
                blocks.add(ContentBlock.CodeBlock("xml", fullTaggedContent))
                currentIndex = closingPos + closingTag.length

                // Skip one leading newline after the closing tag to prevent double-spacing.
                if (currentIndex < rawText.length && rawText[currentIndex] == '\n') {
                    currentIndex++
                }
            }
        }
        return blocks
    }

    // ----- XML tag helpers -----------------------------------------------------------------------

    /**
     * Finds the next XML tag opener at or after [fromIndex] that appears at a valid position:
     * at the start of the string, or immediately after a newline (with optional whitespace).
     *
     * @return A pair of (position, tagName), or null if no valid XML tag opener is found.
     */
    private fun findNextXmlTagOpen(rawText: String, fromIndex: Int): Pair<Int, String>? {
        var searchFrom = fromIndex
        while (searchFrom < rawText.length) {
            val match = XML_TAG_OPEN.find(rawText, searchFrom) ?: return null
            val pos = match.range.first
            val tagName = match.groupValues[1]

            // Only accept tags at line-start positions to avoid matching HTML in prose.
            if (isAtLineStart(rawText, pos)) {
                return Pair(pos, tagName)
            }
            searchFrom = match.range.last + 1
        }
        return null
    }

    // ----- code fence helpers (unchanged) --------------------------------------------------------

    /**
     * Starting from [searchFrom] (the index immediately after the opening ```), scans forward for
     * the matching closing fence while tracking nesting depth.
     *
     * A ``` is considered a real fence only if it is at the start of a line (preceded by newline
     * or start-of-string, with optional whitespace) **or** at the end of a line (followed only by
     * whitespace until the next newline or end-of-string). A ``` with non-whitespace on both
     * sides of it on the same line is noise (e.g. inside a JSON string) and is skipped.
     *
     * Exception: on the **first line** (before any newline after the opening fence), all fences
     * are considered so that single-line blocks like ```raam_toast Hello!``` work.
     *
     * @return the index of the first backtick of the matching closing ```, or -1 if unterminated.
     */
    private fun findClosingFence(rawText: String, searchFrom: Int): Int {
        var depth = 1
        var i = searchFrom

        val firstNewline = rawText.indexOf('\n', searchFrom)

        while (i < rawText.length) {
            val nextFence = rawText.indexOf("```", i)
            if (nextFence == -1) return -1

            val pastFirstLine = firstNewline != -1 && nextFence > firstNewline
            if (pastFirstLine && !isAtLineStart(rawText, nextFence) && !isAtLineEnd(rawText, nextFence)) {
                i = nextFence + 3
                continue
            }

            if (isOpeningFence(rawText, nextFence)) {
                depth++
            } else {
                depth--
                if (depth == 0) return nextFence
            }
            i = nextFence + 3
        }
        return -1
    }

    /**
     * Returns true if the position [pos] is at the start of a line: either at position 0,
     * or preceded only by whitespace back to the nearest newline or start-of-string.
     */
    private fun isAtLineStart(rawText: String, pos: Int): Boolean {
        if (pos == 0) return true
        var j = pos - 1
        while (j >= 0) {
            val ch = rawText[j]
            if (ch == '\n') return true
            if (ch != ' ' && ch != '\t') return false
            j--
        }
        return true
    }

    /**
     * Returns true if the ``` at [fencePos] is at the end of a line: followed only by
     * whitespace until the next newline or end-of-string.
     */
    private fun isAtLineEnd(rawText: String, fencePos: Int): Boolean {
        var j = fencePos + 3
        while (j < rawText.length) {
            val ch = rawText[j]
            if (ch == '\n') return true
            if (ch != ' ' && ch != '\t') return false
            j++
        }
        return true
    }

    /**
     * Determines whether the ``` at [fencePos] is an **opening** fence (has a language tag
     * immediately after it) or a **closing** fence.
     *
     * Opening:  ``` followed by a valid identifier AND then a newline (e.g. ```kotlin\n)
     * Closing:  everything else — ``` at EOF, ``` followed by whitespace/newline immediately,
     *           or ``` followed by text that hits EOF or a space before a newline (LLM trailing junk).
     *
     * This ensures that ```here at EOF or ```and then are correctly treated as closers,
     * while ```kotlin\n inside a block is a genuine nested opener.
     */
    private fun isOpeningFence(rawText: String, fencePos: Int): Boolean {
        val afterFence = fencePos + 3
        if (afterFence >= rawText.length) return false

        val firstChar = rawText[afterFence]
        if (!(firstChar.isLetterOrDigit() || firstChar == '_')) return false

        var i = afterFence
        while (i < rawText.length) {
            val ch = rawText[i]
            if (ch == '\n') return true
            if (!(ch.isLetterOrDigit() || ch == '_' || ch == '.' || ch == '-')) return false
            i++
        }
        return false
    }

    /**
     * Parses the raw inner content of a code block (everything between the opening and closing
     * fences, exclusive of the ``` delimiters themselves) into a [ContentBlock.CodeBlock].
     */
    private fun parseInnerCodeBlock(innerContent: String): ContentBlock.CodeBlock {
        val firstNewline = innerContent.indexOf('\n')

        val language: String
        val code: String

        if (firstNewline != -1) {
            language = innerContent.substring(0, firstNewline).trim()
            code = innerContent.substring(firstNewline + 1)
        } else {
            val parts = innerContent.trim().split(Regex("\\s+"), 2)
            language = parts.getOrNull(0) ?: ""
            code = parts.getOrNull(1) ?: ""
        }
        return ContentBlock.CodeBlock(language.ifBlank { "text" }, code)
    }
}