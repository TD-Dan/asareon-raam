package app.auf.service

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
open class AufTextParser {

    private enum class State {
        SCANNING,
        IN_STRING,
        IN_SINGLE_LINE_COMMENT,
        IN_MULTI_LINE_COMMENT
    }

    private val allQuoteChars = setOf('\'', '`', '"')

    open fun parse(rawText: String): List<ContentBlock> {
        if (rawText.isBlank()) return emptyList()

        val blocks = mutableListOf<ContentBlock>()
        val currentText = StringBuilder()
        var currentState = State.SCANNING
        var currentIndex = 0
        var expectedStringDelimiter = ' '

        while (currentIndex < rawText.length) {
            when (currentState) {
                State.SCANNING -> {
                    val nextCodeFenceIndex = rawText.indexOf("```", currentIndex)
                    val nextSingleLineCommentIndex = rawText.indexOf("//", currentIndex)
                    val nextMultiLineCommentIndex = rawText.indexOf("/*", currentIndex)
                    val nextQuoteIndex = rawText.indexOfAny(allQuoteChars.toCharArray(), currentIndex)

                    val firstInterestingIndex = findFirstOf(
                        nextCodeFenceIndex,
                        nextQuoteIndex,
                        nextSingleLineCommentIndex,
                        nextMultiLineCommentIndex
                    )

                    // No special characters left, append the rest and finish.
                    if (firstInterestingIndex == -1) {
                        currentText.append(rawText.substring(currentIndex))
                        currentIndex = rawText.length
                        continue
                    }

                    // Append the plain text between the current position and the special character.
                    currentText.append(rawText.substring(currentIndex, firstInterestingIndex))

                    when (firstInterestingIndex) {
                        nextQuoteIndex -> {
                            val quoteChar = rawText[firstInterestingIndex]
                            currentText.append(quoteChar)
                            expectedStringDelimiter = quoteChar
                            currentIndex = firstInterestingIndex + 1
                            currentState = State.IN_STRING
                        }
                        nextSingleLineCommentIndex -> {
                            currentText.append("//")
                            currentIndex = firstInterestingIndex + 2
                            currentState = State.IN_SINGLE_LINE_COMMENT
                        }
                        nextMultiLineCommentIndex -> {
                            currentText.append("/*")
                            currentIndex = firstInterestingIndex + 2
                            currentState = State.IN_MULTI_LINE_COMMENT
                        }
                        nextCodeFenceIndex -> {
                            // A code fence was found. Commit any preceding text.
                            if (currentText.isNotEmpty()) {
                                blocks.add(TextBlock(currentText.toString().trim()))
                                currentText.clear()
                            }

                            val languageLineEndIndex = rawText.indexOf('\n', firstInterestingIndex + 3)
                            val blockEndIndex = rawText.indexOf("```", (languageLineEndIndex.takeIf { it != -1 } ?: (firstInterestingIndex + 3)))

                            if (blockEndIndex != -1) {
                                val language = if (languageLineEndIndex != -1 && languageLineEndIndex < blockEndIndex) {
                                    rawText.substring(firstInterestingIndex + 3, languageLineEndIndex).trim()
                                } else {
                                    // Handle case where language is on the same line as the fence, or no language
                                    rawText.substring(firstInterestingIndex + 3, blockEndIndex).split('\n').first().trim()
                                }

                                val contentStartIndex = if (languageLineEndIndex != -1 && languageLineEndIndex < blockEndIndex) {
                                    languageLineEndIndex + 1
                                } else {
                                    // If no newline, content starts after the language part
                                    firstInterestingIndex + 3 + language.length
                                }

                                val content = rawText.substring(contentStartIndex, blockEndIndex).trim()
                                blocks.add(CodeBlock(language = language.ifBlank { "text" }, content = content))
                                currentIndex = blockEndIndex + 3
                            } else {
                                // Unterminated code block, treat it as plain text.
                                currentText.append("```")
                                currentIndex = firstInterestingIndex + 3
                            }
                        }
                    }
                }

                State.IN_STRING -> {
                    val closingQuoteIndex = findClosingDelimiter(rawText, currentIndex, expectedStringDelimiter)
                    if (closingQuoteIndex != -1) {
                        currentText.append(rawText.substring(currentIndex, closingQuoteIndex + 1))
                        currentIndex = closingQuoteIndex + 1
                        currentState = State.SCANNING
                    } else { // Unterminated string
                        currentText.append(rawText.substring(currentIndex))
                        currentIndex = rawText.length
                    }
                }

                State.IN_SINGLE_LINE_COMMENT -> {
                    val endOfLineIndex = rawText.indexOf('\n', currentIndex)
                    if (endOfLineIndex != -1) {
                        currentText.append(rawText.substring(currentIndex, endOfLineIndex + 1))
                        currentIndex = endOfLineIndex + 1
                        currentState = State.SCANNING
                    } else {
                        currentText.append(rawText.substring(currentIndex))
                        currentIndex = rawText.length
                    }
                }

                State.IN_MULTI_LINE_COMMENT -> {
                    val endCommentIndex = rawText.indexOf("*/", currentIndex)
                    if (endCommentIndex != -1) {
                        currentText.append(rawText.substring(currentIndex, endCommentIndex + 2))
                        currentIndex = endCommentIndex + 2
                        currentState = State.SCANNING
                    } else {
                        currentText.append(rawText.substring(currentIndex))
                        currentIndex = rawText.length
                    }
                }
            }
        }
        if (currentText.isNotEmpty()) {
            blocks.add(TextBlock(currentText.toString().trim()))
        }
        return blocks.filter { (it as? TextBlock)?.text?.isNotBlank() ?: true }
    }

    private fun findFirstOf(vararg indices: Int): Int {
        return indices.filter { it != -1 }.minOrNull() ?: -1
    }

    private fun findClosingDelimiter(text: String, startIndex: Int, delimiter: Char): Int {
        var i = startIndex
        while (i < text.length) {
            if (text[i] == delimiter) return i
            if (text[i] == '\\' && i + 1 < text.length) {
                i += 2 // Skip escaped character
                continue
            }
            i++
        }
        return -1 // Not found
    }
}