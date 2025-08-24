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
 * list of `ContentBlock`s. It implements a state machine to handle the `[AUF_COMMAND(...)]...[/AUF]`
 * syntax, validating against a provided tool registry. It must be resilient to syntax variations
 * and provide clear error reporting.
 *
 * ---
 * ## Dependencies
 * - `app.auf.model.ToolDefinition`: The schema for available tools.
 * - `kotlinx.serialization.json.Json`: For parsing JSON payloads in specific blocks.
 *
 * @version 2.1
 * @since 2025-08-24
 */
class AufTextParser(
    private val jsonParser: Json,
    private val toolRegistry: List<ToolDefinition>
) {

    private enum class State { SCANNING, TOOL_ACTIVE }

    fun parse(rawText: String): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        var currentState = State.SCANNING
        var currentIndex = 0
        var activeTool: ToolDefinition? = null
        var activeTagFormat: String = "" // Stores the original format, e.g., "_ACTION_MANIFEST"

        while (currentIndex < rawText.length) {
            when (currentState) {
                State.SCANNING -> {
                    val nextTagIndex = rawText.indexOf("[AUF", startIndex = currentIndex, ignoreCase = true)

                    if (nextTagIndex == -1) {
                        val remainingText = rawText.substring(currentIndex)
                        if (remainingText.isNotBlank()) {
                            blocks.add(TextBlock(remainingText.trim()))
                        }
                        currentIndex = rawText.length
                        continue
                    }

                    val textBeforeTag = rawText.substring(currentIndex, nextTagIndex)
                    if (textBeforeTag.isNotBlank()) {
                        blocks.add(TextBlock(textBeforeTag.trim()))
                    }

                    val tagContentEnd = rawText.indexOf(']', startIndex = nextTagIndex)
                    if (tagContentEnd == -1) {
                        blocks.add(ParseErrorBlock("UNKNOWN", rawText.substring(nextTagIndex), "Unclosed start tag found."))
                        currentIndex = rawText.length
                        continue
                    }

                    val fullTagContent = rawText.substring(nextTagIndex + 1, tagContentEnd)
                    val commandPart = fullTagContent.substringAfter("AUF").trim()
                    activeTagFormat = commandPart // Store original format for end tag search

                    val normalizedCommand = commandPart.replace("_", "").replace(" ", "").uppercase()
                    val tool = toolRegistry.find { it.command.replace("_", "").uppercase() == normalizedCommand }

                    if (tool == null) {
                        blocks.add(ParseErrorBlock(commandPart, "", "Unknown tool command."))
                        currentIndex = tagContentEnd + 1
                        continue
                    }

                    activeTool = tool
                    currentState = State.TOOL_ACTIVE
                    currentIndex = tagContentEnd + 1
                }

                State.TOOL_ACTIVE -> {
                    val tool = activeTool ?: break
                    // Use the original format to find the closing tag
                    val endTag = "[/AUF${activeTagFormat}]"
                    val endTagIndex = rawText.indexOf(endTag, startIndex = currentIndex, ignoreCase = true)

                    if (endTagIndex == -1) {
                        val remainingContent = rawText.substring(currentIndex)
                        blocks.add(ParseErrorBlock(tool.command, remainingContent, "Closing tag '$endTag' not found."))
                        currentIndex = rawText.length
                        continue
                    }

                    val payload = rawText.substring(currentIndex, endTagIndex).trim()
                    blocks.add(processBlock(tool, emptyMap(), payload))

                    currentIndex = endTagIndex + endTag.length
                    activeTool = null
                    currentState = State.SCANNING
                }
            }
        }
        return blocks.filter { (it as? TextBlock)?.text?.isNotBlank() ?: true }
    }

    private fun processBlock(tool: ToolDefinition, params: Map<String, String>, content: String): ContentBlock {
        return try {
            when (tool.command.replace("_", "").uppercase()) {
                "ACTIONMANIFEST" -> {
                    val cleanContent = content.removePrefix("```json").removeSuffix("```").trim()
                    val actions = jsonParser.decodeFromString(ListSerializer(Action.serializer()), cleanContent)
                    ActionBlock(actions = actions)
                }
                "FILEVIEW" -> FileContentBlock(fileName = params["path"] ?: "unknown file", content = content, language = params["language"])
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