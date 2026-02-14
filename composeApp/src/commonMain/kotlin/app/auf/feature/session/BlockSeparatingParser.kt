package app.auf.feature.session

/**
 * A robust, state-machine-based parser for the AUF's tagged text format.
 * This class's sole responsibility is to convert a raw string from an AI or User into a structured
 * list of `ContentBlock`s. It is the single source of truth for interpreting the AUF's
 * simplified markdown-based data contract.
 *
 * This version uses a nesting-aware fence matcher to correctly handle nested code fences,
 * inline fences, and newline preservation as per the established contract.
 *
 * Nesting rule: A ``` followed immediately by a letter, digit, or underscore (e.g. ```kotlin)
 * is treated as an **opening** fence that increments nesting depth. A ``` followed by whitespace,
 * a newline, or end-of-string is treated as a **closing** fence that decrements depth. The true
 * closing fence for an outer block is the one that brings the depth back to zero.
 */
class BlockSeparatingParser {

    fun parse(rawText: String): List<ContentBlock> {
        if (rawText.isBlank()) return emptyList()

        val blocks = mutableListOf<ContentBlock>()
        var currentIndex = 0

        while (currentIndex < rawText.length) {
            // --- SCANNING FOR TEXT ---
            val fenceStart = rawText.indexOf("```", currentIndex)
            if (fenceStart == -1) {
                // No more fences found, add the rest as a text block.
                blocks.add(ContentBlock.Text(rawText.substring(currentIndex)))
                break
            }

            // Add the text before the fence.
            val textBefore = rawText.substring(currentIndex, fenceStart)
            if (textBefore.isNotEmpty()) {
                blocks.add(ContentBlock.Text(textBefore))
            }

            // --- SCANNING FOR CODE (nesting-aware) ---
            val fenceEnd = findClosingFence(rawText, fenceStart + 3)
            if (fenceEnd == -1) {
                // Unterminated fence – the rest of the string is a greedy code block.
                val innerContent = rawText.substring(fenceStart + 3)
                blocks.add(parseInnerCodeBlock(innerContent))
                break
            }

            // Found a correctly-matched closing fence.
            val innerContent = rawText.substring(fenceStart + 3, fenceEnd)
            blocks.add(parseInnerCodeBlock(innerContent))
            currentIndex = fenceEnd + 3
        }
        return blocks
    }

    // ----- private helpers -----------------------------------------------------------------------

    /**
     * Starting from [searchFrom] (the index immediately after the opening ```), scans forward for
     * the matching closing fence while tracking nesting depth.
     *
     * @return the index of the first backtick of the matching closing ```, or -1 if unterminated.
     */
    private fun findClosingFence(rawText: String, searchFrom: Int): Int {
        var depth = 1
        var i = searchFrom

        while (i < rawText.length) {
            val nextFence = rawText.indexOf("```", i)
            if (nextFence == -1) return -1 // No more fences at all – unterminated.

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

        // First char must be a valid identifier start.
        val firstChar = rawText[afterFence]
        if (!(firstChar.isLetterOrDigit() || firstChar == '_')) return false

        // Scan forward: a valid opening fence has an identifier that ends at a newline.
        var i = afterFence
        while (i < rawText.length) {
            val ch = rawText[i]
            if (ch == '\n') return true  // Identifier followed by newline → genuine opener.
            if (!(ch.isLetterOrDigit() || ch == '_' || ch == '.' || ch == '-')) return false
            i++
        }
        // Reached EOF without a newline → this is a closer (e.g. ```here at end of string).
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
            // Multi-line block: language is on the first line, code is everything after.
            language = innerContent.substring(0, firstNewline).trim()
            code = innerContent.substring(firstNewline + 1)
        } else {
            // Single-line block (e.g. ```auf_toastMessage Hello!```).
            val parts = innerContent.trim().split(Regex("\\s+"), 2)
            language = parts.getOrNull(0) ?: ""
            code = parts.getOrNull(1) ?: ""
        }
        return ContentBlock.CodeBlock(language.ifBlank { "text" }, code)
    }
}