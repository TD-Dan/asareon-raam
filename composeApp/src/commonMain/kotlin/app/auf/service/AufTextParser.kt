// --- FILE: commonMain/kotlin/app/auf/service/AufTextParser.kt ---
package app.auf.service

import app.auf.core.ActionBlock
import app.auf.core.AnchorBlock
import app.auf.core.AppRequestBlock
import app.auf.core.ContentBlock
import app.auf.core.FileContentBlock
import app.auf.core.ParseErrorBlock
import app.auf.core.TextBlock
import app.auf.model.Action
import app.auf.model.ToolDefinition
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A robust, state-machine-based parser for the AUF's tagged text format.
 *
 * ---
 * ## Mandate
 * This class's sole responsibility is to convert a raw string from an AI into a structured
 * list of `ContentBlock`s. It is the single source of truth for interpreting the AUF
 * tagged-text data contract. It is now aware of various string literals and comment styles
 * to avoid incorrectly parsing tags within them.
 *
 * ---
 * ## Dependencies
 * - `app.auf.model.ToolDefinition`: The schema for available tools.
 * - `kotlinx.serialization.json.Json`: For parsing JSON payloads in specific blocks.
 *
 * @version 3.4
 * @since 2025-08-29
 */
open class AufTextParser(
    private val jsonParser: Json,
    private val toolRegistry: List<ToolDefinition>
) {

    // --- MODIFICATION START: Expanded State enum for comments ---
    private enum class State {
        SCANNING,
        IN_STRING,
        IN_SINGLE_LINE_COMMENT,
        IN_MULTI_LINE_COMMENT,
        TOOL_ACTIVE
    }
    // --- MODIFICATION END ---

    // --- MODIFICATION START: Added delimiter map for paired quotes ---
    private val quoteDelimiterMap = mapOf(
        '‘' to '’',
        '“' to '”'
    )
    private val allQuoteChars = setOf('\'', '`', '´', '‘', '’', '"', '“', '”')
    // --- MODIFICATION END ---


    open fun parse(rawText: String): List<ContentBlock> {
        if (rawText.isBlank()) return emptyList()

        val blocks = mutableListOf<ContentBlock>()
        val currentText = StringBuilder()
        var currentState = State.SCANNING
        var currentIndex = 0

        var activeTool: ToolDefinition? = null
        var activeCommandForEndTag = ""
        var activeParams: Map<String, String> = emptyMap()
        var expectedStringDelimiter = ' '

        while (currentIndex < rawText.length) {
            when (currentState) {
                State.SCANNING -> {
                    // --- MODIFICATION START: Scan for all known delimiters ---
                    val nextTagIndex = rawText.indexOf("[AUF_", currentIndex)
                    val nextSingleLineCommentIndex = rawText.indexOf("//", currentIndex)
                    val nextMultiLineCommentIndex = rawText.indexOf("/*", currentIndex)
                    val nextQuoteIndices = allQuoteChars.map { rawText.indexOf(it, currentIndex) }
                    val nextQuoteIndex = nextQuoteIndices.filter { it != -1 }.minOrNull() ?: -1
                    // --- MODIFICATION END ---

                    val firstInterestingIndex = findFirstOf(
                        nextTagIndex,
                        nextQuoteIndex,
                        nextSingleLineCommentIndex,
                        nextMultiLineCommentIndex
                    )

                    if (firstInterestingIndex == -1) { // No more delimiters
                        currentText.append(rawText.substring(currentIndex))
                        currentIndex = rawText.length
                        continue
                    }

                    currentText.append(rawText.substring(currentIndex, firstInterestingIndex))

                    // --- MODIFICATION START: Handle transition to all new states ---
                    when (firstInterestingIndex) {
                        nextQuoteIndex -> {
                            val quoteChar = rawText[firstInterestingIndex]
                            currentText.append(quoteChar)
                            expectedStringDelimiter = quoteDelimiterMap[quoteChar] ?: quoteChar
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
                        nextTagIndex -> {
                            if (currentText.isNotEmpty()) {
                                blocks.add(TextBlock(currentText.toString().trim()))
                                currentText.clear()
                            }
                            val tagEndIndex = rawText.indexOf(']', firstInterestingIndex)
                            if (tagEndIndex == -1) {
                                blocks.add(ParseErrorBlock("UNKNOWN", rawText.substring(firstInterestingIndex), "Unclosed start tag."))
                                currentIndex = rawText.length
                                continue
                            }

                            val fullTagContent = rawText.substring(firstInterestingIndex + 5, tagEndIndex)
                            val paramStartIndex = fullTagContent.indexOf('(')
                            val commandString = (if (paramStartIndex != -1) fullTagContent.substring(0, paramStartIndex) else fullTagContent).trim()

                            activeCommandForEndTag = commandString
                            val tool = toolRegistry.find { it.command.equals(commandString, ignoreCase = true) }

                            if (tool == null) {
                                blocks.add(ParseErrorBlock(commandString, rawText.substring(firstInterestingIndex, tagEndIndex + 1), "Unknown tool command."))
                                currentIndex = tagEndIndex + 1
                                continue
                            }
                            val params = parseParameters(fullTagContent, tool)
                            if (params == null) {
                                blocks.add(ParseErrorBlock(commandString, fullTagContent, "Failed to parse parameters."))
                                currentIndex = tagEndIndex + 1
                                continue
                            }
                            activeTool = tool
                            activeParams = params
                            currentIndex = tagEndIndex + 1
                            currentState = State.TOOL_ACTIVE
                        }
                    }
                    // --- MODIFICATION END ---
                }

                // --- MODIFICATION START: Smarter IN_STRING state ---
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
                // --- MODIFICATION END ---

                // --- MODIFICATION START: New comment states ---
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
                // --- MODIFICATION END ---

                State.TOOL_ACTIVE -> {
                    val tool = activeTool ?: break
                    val endTag = "[/AUF_${activeCommandForEndTag}]"
                    val endTagIndex = rawText.indexOf(endTag, currentIndex, ignoreCase = true)

                    if (endTagIndex == -1) {
                        blocks.add(ParseErrorBlock(tool.command, rawText.substring(currentIndex), "Closing tag '$endTag' not found."))
                        currentIndex = rawText.length
                        continue
                    }
                    val payload = rawText.substring(currentIndex, endTagIndex).trim()
                    blocks.add(processBlock(tool, activeParams, payload))
                    currentIndex = endTagIndex + endTag.length
                    activeTool = null
                    activeParams = emptyMap()
                    activeCommandForEndTag = ""
                    currentState = State.SCANNING
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

    private fun parseParameters(paramString: String, tool: ToolDefinition): Map<String, String>? {
        val parsed = mutableMapOf<String, String>()
        val paramStartIndex = paramString.indexOf('(')
        if (paramStartIndex != -1) {
            val paramEndIndex = paramString.lastIndexOf(')')
            if (paramEndIndex == -1 || paramEndIndex < paramStartIndex) return null
            val paramContent = paramString.substring(paramStartIndex + 1, paramEndIndex)
            try {
                if (paramContent.isNotBlank()) {
                    paramContent.split(',').forEach { pair ->
                        if (pair.isBlank()) return@forEach
                        val parts = pair.split('=', limit = 2)
                        if (parts.size != 2) throw IllegalArgumentException("Invalid key-value pair")
                        val key = parts[0].trim()
                        val valueWithQuotes = parts[1].trim()
                        if (!allQuoteChars.any{ valueWithQuotes.startsWith(it)} || !allQuoteChars.any{ valueWithQuotes.endsWith(it)}) throw IllegalArgumentException("Value not quoted")
                        parsed[key] = valueWithQuotes.substring(1, valueWithQuotes.length - 1)
                    }
                }
            } catch (e: Exception) {
                println("Parameter parsing failed: ${e.message}")
                return null
            }
        }
        tool.parameters.forEach { paramDef ->
            if (!parsed.containsKey(paramDef.name) && paramDef.defaultValue != null) {
                parsed[paramDef.name] = paramDef.defaultValue.toString()
            }
        }
        return parsed
    }

    private fun processBlock(tool: ToolDefinition, params: Map<String, String>, content: String): ContentBlock {
        return try {
            when (tool.command.replace("_", "").uppercase()) {
                "ACTIONMANIFEST" -> {
                    val cleanContent = content.removePrefix("```json").removeSuffix("```").trim()
                    val actions = jsonParser.decodeFromString(ListSerializer(Action.serializer()), cleanContent)
                    ActionBlock(actions = actions)
                }
                "FILEVIEW" -> FileContentBlock(
                    fileName = params["path"] ?: "unknown file",
                    content = content,
                    language = params["language"]
                )
                "APPREQUEST" -> AppRequestBlock(requestType = content)
                "STATEANCHOR" -> {
                    val jsonObject = jsonParser.decodeFromString<JsonObject>(content)
                    val anchorId = jsonObject["anchorId"]?.jsonPrimitive?.content ?: "unknown-anchor"
                    AnchorBlock(anchorId, jsonObject)
                }
                else -> ParseErrorBlock(tool.command, content, "Unknown AUF Tag '${tool.command}'")
            }
        } catch (e: Exception) {
            ParseErrorBlock(tool.command, content, "A deserialization error occurred: ${e.message}")
        }
    }
}