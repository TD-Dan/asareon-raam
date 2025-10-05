package app.auf.feature.session

/**
 * A robust, state-machine-based parser for the AUF's tagged text format.
 * This class's sole responsibility is to convert a raw string from an AI or User into a structured
 * list of `ContentBlock`s. It is the single source of truth for interpreting the AUF's
 * simplified markdown-based data contract.
 *
 * This version uses an index-based scanning approach to robustly handle inline code fences,
 * single-line blocks, and unterminated blocks, while correctly ignoring nested fences and
 * preserving newlines as per the established test contract.
 */
class BlockSeparatingParser {

    fun parse(rawText: String): List<ContentBlock> {
        if (rawText.isBlank()) return emptyList()

        val blocks = mutableListOf<ContentBlock>()
        var currentIndex = 0

        while (currentIndex < rawText.length) {
            val fenceStart = rawText.indexOf("```", currentIndex)

            if (fenceStart == -1) {
                // No more fences, the rest of the string is a single text block.
                val remainingText = rawText.substring(currentIndex)
                if (remainingText.isNotEmpty()) {
                    blocks.add(ContentBlock.Text(remainingText))
                }
                break // End of parsing
            }

            // Add any text that came before this fence.
            val textBefore = rawText.substring(currentIndex, fenceStart)
            if (textBefore.isNotEmpty()) {
                blocks.add(ContentBlock.Text(textBefore))
            }

            val fenceEnd = rawText.indexOf("```", fenceStart + 3)

            if (fenceEnd == -1) {
                // Unterminated fence, treat the rest of the string as a code block.
                val innerContent = rawText.substring(fenceStart + 3)
                blocks.add(parseInnerCodeBlock(innerContent))
                break // End of parsing
            }

            // We have a complete, terminated code block.
            val innerContent = rawText.substring(fenceStart + 3, fenceEnd)
            blocks.add(parseInnerCodeBlock(innerContent))

            // Move the cursor past the closing fence to continue scanning.
            currentIndex = fenceEnd + 3
        }

        return blocks.filterNot { it is ContentBlock.Text && it.text.isBlank() }
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