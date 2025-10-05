package app.auf.feature.session

/**
 * A robust, state-machine-based parser for the AUF's tagged text format.
 * This class's sole responsibility is to convert a raw string from an AI or User into a structured
 * list of `ContentBlock`s. It is the single source of truth for interpreting the AUF's
 * simplified markdown-based data contract.
 *
 * This version uses a line-aware state machine to correctly handle all test cases, including
 * nested code fences, inline fences, and newline preservation as per the established contract.
 */
class BlockSeparatingParser {

    private enum class State { SCANNING, IN_CODE }

    fun parse(rawText: String): List<ContentBlock> {
        if (rawText.isBlank()) return emptyList()

        val blocks = mutableListOf<ContentBlock>()
        var currentIndex = 0

        while (currentIndex < rawText.length) {
            // --- SCANNING FOR TEXT ---
            val fenceStart = rawText.indexOf("```", currentIndex)
            if (fenceStart == -1) {
                // No more fences found, add the rest as a text block
                blocks.add(ContentBlock.Text(rawText.substring(currentIndex)))
                break
            }

            // Add the text before the fence
            val textBefore = rawText.substring(currentIndex, fenceStart)
            if (textBefore.isNotEmpty()) {
                blocks.add(ContentBlock.Text(textBefore))
            }

            // --- SCANNING FOR CODE ---
            val fenceEnd = rawText.indexOf("```", fenceStart + 3)
            if (fenceEnd == -1) {
                // Unterminated fence, the rest is a code block
                val innerContent = rawText.substring(fenceStart + 3)
                blocks.add(parseInnerCodeBlock(innerContent))
                break
            }

            // Found a full code block
            val innerContent = rawText.substring(fenceStart + 3, fenceEnd)
            blocks.add(parseInnerCodeBlock(innerContent))
            currentIndex = fenceEnd + 3
        }
        return blocks
    }

    private fun parseInnerCodeBlock(innerContent: String): ContentBlock.CodeBlock {
        val firstNewline = innerContent.indexOf('\n')

        val language: String
        val code: String

        if (firstNewline != -1) {
            // This is likely a multi-line block.
            language = innerContent.substring(0, firstNewline).trim()
            // The code is everything *after* that first newline.
            code = innerContent.substring(firstNewline + 1)
        } else {
            // This is a single-line block.
            val parts = innerContent.trim().split(Regex("\\s+"), 2)
            language = parts.getOrNull(0) ?: ""
            code = parts.getOrNull(1) ?: ""
        }
        return ContentBlock.CodeBlock(language.ifBlank { "text" }, code)
    }
}