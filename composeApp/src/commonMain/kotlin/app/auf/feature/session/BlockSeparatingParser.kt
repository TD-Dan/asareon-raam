package app.auf.feature.session

/**
 * A robust, state-machine-based parser for the AUF's tagged text format.
 * This class's sole responsibility is to convert a raw string from an AI or User into a structured
 * list of `ContentBlock`s. It is the single source of truth for interpreting the AUF's
 * simplified markdown-based data contract.
 */
class BlockSeparatingParser {

    fun parse(rawText: String): List<ContentBlock> {
        if (rawText.isBlank()) return emptyList()

        val blocks = mutableListOf<ContentBlock>()
        var currentIndex = 0

        while (currentIndex < rawText.length) {
            val textBeforeBlock = StringBuilder()
            var blockStartIndex = -1

            // Scan for the next code block, respecting string/comment contexts.
            var scanIndex = currentIndex
            while (scanIndex < rawText.length) {
                if (rawText.startsWith("```", scanIndex)) {
                    blockStartIndex = scanIndex
                    break
                }
                textBeforeBlock.append(rawText[scanIndex])
                scanIndex++
            }

            val trimmedTextBefore = textBeforeBlock.toString()
            if (trimmedTextBefore.isNotEmpty()) {
                blocks.add(ContentBlock.Text(trimmedTextBefore))
            }

            if (blockStartIndex != -1) {
                val blockEndIndex = rawText.indexOf("```", blockStartIndex + 3)
                if (blockEndIndex != -1) {
                    // Terminated block
                    val innerBlock = rawText.substring(blockStartIndex + 3, blockEndIndex)
                    val firstNewline = innerBlock.indexOf('\n')

                    val language: String
                    val content: String

                    if (firstNewline != -1) {
                        language = innerBlock.substring(0, firstNewline).trim()
                        content = innerBlock.substring(firstNewline + 1)
                    } else {
                        // Single-line block
                        val parts = innerBlock.trim().split(Regex("\\s+"), 2)
                        language = parts.getOrNull(0) ?: ""
                        content = parts.getOrNull(1) ?: ""
                    }
                    blocks.add(ContentBlock.CodeBlock(language.ifBlank { "text" }, content))
                    currentIndex = blockEndIndex + 3
                } else {
                    // Unterminated block (greedy to end of string)
                    val innerBlock = rawText.substring(blockStartIndex + 3)
                    val firstNewline = innerBlock.indexOf('\n')

                    val language: String
                    val content: String

                    if (firstNewline != -1) {
                        language = innerBlock.substring(0, firstNewline).trim()
                        content = innerBlock.substring(firstNewline + 1)
                    } else {
                        val parts = innerBlock.trim().split(Regex("\\s+"), 2)
                        language = parts.getOrNull(0) ?: ""
                        content = parts.getOrNull(1) ?: ""
                    }
                    blocks.add(ContentBlock.CodeBlock(language.ifBlank { "text" }, content))
                    currentIndex = rawText.length
                }
            } else {
                currentIndex = rawText.length
            }
        }
        // Filter out empty text blocks that can result from parsing.
        return blocks.filterNot { it is ContentBlock.Text && it.text.isBlank() }
    }
}