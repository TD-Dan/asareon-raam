package app.auf.feature.session

import app.auf.core.CodeBlock
import app.auf.core.ContentBlock
import app.auf.core.TextBlock

/**
 * A robust, state-machine-based parser for the AUF's tagged text format.
 *
 * ---
 * ## Mandate
 * This class's sole responsibility is to convert a raw string from an AI or User into a structured
 * list of `ContentBlock`s. It is the single source of truth for interpreting the AUF's
 * simplified markdown-based data contract. It correctly handles code fences that may appear
 * inside string literals or comments, preventing false positives.
 */
open class BlockSeparatingParser {

    private val debug = false // Set to true to enable logging

    private fun log(message: String) {
        if (debug) println("[Parser] $message")
    }

    open fun parse(rawText: String): List<ContentBlock> {
        if (rawText.isBlank()) return emptyList()

        val blocks = mutableListOf<ContentBlock>()
        var currentIndex = 0

        log("--- STARTING PARSE ---")
        while (currentIndex < rawText.length) {
            val textBeforeBlock = StringBuilder()
            var blockStartIndex = -1

            // Phase 1: Scan for the next code block, respecting comments and strings
            var scanIndex = currentIndex
            while (scanIndex < rawText.length) {
                when {
                    rawText.startsWith("```", scanIndex) -> {
                        blockStartIndex = scanIndex
                        break // Found it, exit scan loop
                    }
                    rawText.startsWith("//", scanIndex) -> {
                        val lineEnd = rawText.indexOf('\n', scanIndex)
                        if (lineEnd == -1) {
                            textBeforeBlock.append(rawText.substring(scanIndex))
                            scanIndex = rawText.length
                        } else {
                            textBeforeBlock.append(rawText.substring(scanIndex, lineEnd + 1))
                            scanIndex = lineEnd + 1
                        }
                    }
                    rawText.startsWith("/*", scanIndex) -> {
                        val commentEnd = rawText.indexOf("*/", scanIndex + 2)
                        if (commentEnd == -1) {
                            textBeforeBlock.append(rawText.substring(scanIndex))
                            scanIndex = rawText.length
                        } else {
                            textBeforeBlock.append(rawText.substring(scanIndex, commentEnd + 2))
                            scanIndex = commentEnd + 2
                        }
                    }
                    rawText[scanIndex] in setOf('"', '\'', '`') -> {
                        val quote = rawText[scanIndex]
                        textBeforeBlock.append(quote)
                        val stringEnd = rawText.indexOf(quote, scanIndex + 1) // Simple version, no escape handling
                        if (stringEnd == -1) {
                            textBeforeBlock.append(rawText.substring(scanIndex + 1))
                            scanIndex = rawText.length
                        } else {
                            textBeforeBlock.append(rawText.substring(scanIndex + 1, stringEnd + 1))
                            scanIndex = stringEnd + 1
                        }
                    }
                    else -> {
                        textBeforeBlock.append(rawText[scanIndex])
                        scanIndex++
                    }
                }
            }
            log("Scan Phase: Found text: '${textBeforeBlock.toString().replace("\n", "\\n")}', fence at $blockStartIndex")

            // Phase 2: Process findings
            val trimmedTextBefore = textBeforeBlock.toString().trim()
            if (trimmedTextBefore.isNotEmpty()) {
                blocks.add(TextBlock(trimmedTextBefore))
            }

            if (blockStartIndex != -1) {
                val blockEndIndex = rawText.indexOf("```", blockStartIndex + 3)
                if (blockEndIndex != -1) {
                    // This is a complete, well-formed block
                    val innerBlock = rawText.substring(blockStartIndex + 3, blockEndIndex)
                    val firstNewline = innerBlock.indexOf('\n')

                    val language: String
                    val content: String

                    if (firstNewline != -1) {
                        language = innerBlock.substring(0, firstNewline).trim()
                        content = innerBlock.substring(firstNewline + 1).trim()
                    } else {
                        val parts = innerBlock.trim().split(Regex("\\s+"), 2)
                        language = parts.getOrNull(0) ?: ""
                        content = parts.getOrNull(1) ?: ""
                    }
                    log("  -> CREATED CodeBlock(lang='${language.ifBlank { "text" }}')")
                    blocks.add(CodeBlock(language.ifBlank { "text" }, content))
                    currentIndex = blockEndIndex + 3
                } else {
                    // --- FIX IS HERE ---
                    // Unterminated block. Treat it as a valid, greedy block that consumes the rest of the string.
                    val innerBlock = rawText.substring(blockStartIndex + 3)
                    val firstNewline = innerBlock.indexOf('\n')

                    val language: String
                    val content: String

                    if (firstNewline != -1) {
                        language = innerBlock.substring(0, firstNewline).trim()
                        content = innerBlock.substring(firstNewline + 1).trim()
                    } else {
                        val parts = innerBlock.trim().split(Regex("\\s+"), 2)
                        language = parts.getOrNull(0) ?: ""
                        content = parts.getOrNull(1) ?: ""
                    }
                    log("  -> CREATED UNTERMINATED CodeBlock(lang='${language.ifBlank { "text" }}')")
                    blocks.add(CodeBlock(language.ifBlank { "text" }, content))
                    currentIndex = rawText.length // We've consumed the rest of the string
                }
            } else {
                // No more fences found
                currentIndex = rawText.length
            }
        }
        log("--- PARSE COMPLETE: ${blocks.size} blocks found. ---")
        return blocks
    }
}