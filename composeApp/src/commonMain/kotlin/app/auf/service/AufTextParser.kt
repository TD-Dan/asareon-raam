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
 * A robust, non-regex, state-machine-based parser for the AUF's tagged text format.
 *
 * ---
 * ## Mandate
 * This class's sole responsibility is to convert a raw string from an AI into a structured
 * list of `ContentBlock`s. It is the single source of truth for interpreting the AUF
 * tagged-text data contract.
 *
 * ---
 * ## Dependencies
 * - `app.auf.model.ToolDefinition`: The schema for available tools.
 * - `kotlinx.serialization.json.Json`: For parsing JSON payloads in specific blocks.
 *
 * @version 3.1
 * @since 2025-08-24
 */
open class AufTextParser(
    private val jsonParser: Json,
    private val toolRegistry: List<ToolDefinition>
) {

    private enum class State { SCANNING, TOOL_ACTIVE }

    open fun parse(rawText: String): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        var currentState = State.SCANNING
        var currentIndex = 0

        var activeTool: ToolDefinition? = null
        var activeCommandForEndTag = ""
        var activeParams: Map<String, String> = emptyMap()

        // Handle initial text before any tag
        val firstTagStart = rawText.indexOf("[AUF_")
        if (firstTagStart > 0) {
            val leadingText = rawText.substring(0, firstTagStart).trim()
            if (leadingText.isNotBlank()) {
                blocks.add(TextBlock(leadingText))
            }
            currentIndex = firstTagStart
        } else if (firstTagStart == -1) { // No tags at all, treat as single text block
            if (rawText.isNotBlank()) {
                blocks.add(TextBlock(rawText.trim()))
            }
            return blocks.filter { (it as? TextBlock)?.text?.isNotBlank() ?: true }
        }


        while (currentIndex < rawText.length) {
            when (currentState) {
                State.SCANNING -> {
                    val tagContentStart = rawText.indexOf("[AUF_", startIndex = currentIndex)
                    if (tagContentStart == -1) {
                        // No more start tags, add remaining as TextBlock if any
                        val remainingText = rawText.substring(currentIndex).trim()
                        if (remainingText.isNotBlank()) {
                            blocks.add(TextBlock(remainingText))
                        }
                        currentIndex = rawText.length // End parsing
                        continue
                    }

                    // Add any text found before the current tag as a TextBlock
                    if (tagContentStart > currentIndex) {
                        val leadingText = rawText.substring(currentIndex, tagContentStart).trim()
                        if (leadingText.isNotBlank()) {
                            blocks.add(TextBlock(leadingText))
                        }
                    }

                    val tagContentEnd = rawText.indexOf("]", startIndex = tagContentStart)
                    if (tagContentEnd == -1) {
                        blocks.add(ParseErrorBlock("UNKNOWN", rawText.substring(tagContentStart), "Unclosed start tag found."))
                        currentIndex = rawText.length
                        continue
                    }

                    val fullTagContent = rawText.substring(tagContentStart + 5, tagContentEnd) // +5 for "[AUF_"
                    val paramStartIndex = fullTagContent.indexOf('(')

                    val commandString = (if (paramStartIndex != -1) {
                        fullTagContent.substring(0, paramStartIndex)
                    } else {
                        fullTagContent
                    }).trim()

                    activeCommandForEndTag = commandString // Store exact command for end tag

                    val normalizedCommand = commandString.replace("_", "").replace(" ", "").uppercase()
                    val tool = toolRegistry.find { it.command.replace("_", "").uppercase() == normalizedCommand }

                    if (tool == null) {
                        blocks.add(ParseErrorBlock(commandString, rawText.substring(tagContentStart, tagContentEnd + 1), "Unknown tool command."))
                        currentIndex = tagContentEnd + 1
                        continue
                    }

                    var params: Map<String, String>?
                    if (paramStartIndex != -1) {
                        val paramEndIndex = fullTagContent.lastIndexOf(')')
                        if (paramEndIndex == -1 || paramEndIndex < paramStartIndex) {
                            blocks.add(ParseErrorBlock(commandString, fullTagContent, "Malformed parameters: Unclosed parenthesis."))
                            currentIndex = tagContentEnd + 1
                            continue
                        }
                        val paramContent = fullTagContent.substring(paramStartIndex + 1, paramEndIndex)
                        params = parseParameters(paramContent, tool)
                        if (params == null) {
                            blocks.add(ParseErrorBlock(commandString, paramContent, "Failed to parse parameters. Check syntax (key=\"value\")."))
                            currentIndex = tagContentEnd + 1
                            continue
                        }
                    } else {
                        // If no explicit parameters are provided, apply defaults if any
                        params = tool.parameters.filter { it.defaultValue != null }
                            .associate { it.name to it.defaultValue.toString() }
                    }

                    activeTool = tool
                    activeParams = params
                    currentState = State.TOOL_ACTIVE
                    currentIndex = tagContentEnd + 1
                }

                State.TOOL_ACTIVE -> {
                    val tool = activeTool ?: break
                    val endTag = "[/AUF_${activeCommandForEndTag}]" // Use activeCommandForEndTag
                    val endTagIndex = rawText.indexOf(endTag, startIndex = currentIndex, ignoreCase = true)

                    if (endTagIndex == -1) {
                        val remainingContent = rawText.substring(currentIndex)
                        blocks.add(ParseErrorBlock(tool.command, remainingContent, "Closing tag '$endTag' not found."))
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
        return blocks.filter { (it as? TextBlock)?.text?.isNotBlank() ?: true }
    }


    private fun parseParameters(paramString: String, tool: ToolDefinition): Map<String, String>? {
        val parsed = mutableMapOf<String, String>()
        if (paramString.isBlank()) {
            // Still need to apply defaults even if params are empty
        } else {
            try {
                paramString.split(',').forEach { pair ->
                    if (pair.isBlank()) return@forEach
                    val parts = pair.split('=', limit = 2)
                    if (parts.size != 2) return null

                    val key = parts[0].trim()
                    val valueWithQuotes = parts[1].trim()

                    if (!valueWithQuotes.startsWith('"') || !valueWithQuotes.endsWith('"')) return null
                    parsed[key] = valueWithQuotes.substring(1, valueWithQuotes.length - 1)
                }
            } catch (_: Exception) {
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