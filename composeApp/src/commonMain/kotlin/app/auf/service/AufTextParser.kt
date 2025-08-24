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
 * @version 1.0
 * @since 2025-08-23
 */
class AufTextParser(
    private val jsonParser: Json,
    private val toolRegistry: List<ToolDefinition>
) {

    private enum class State { SCANNING, DETECT_TOOL, READ_PARAMETERS, TOOL_ACTIVE }

    fun parse(rawText: String): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        var currentIndex = 0

        while (currentIndex < rawText.length) {
            val nextTagIndex = rawText.indexOf("[AUF", startIndex = currentIndex, ignoreCase = true)

            if (nextTagIndex == -1) {
                // No more tags, add the rest of the text and finish
                val remainingText = rawText.substring(currentIndex)
                if (remainingText.isNotBlank()) {
                    blocks.add(TextBlock(remainingText.trim()))
                }
                break
            }

            // Add the text between the current position and the new tag
            val textBeforeTag = rawText.substring(currentIndex, nextTagIndex)
            if (textBeforeTag.isNotBlank()) {
                blocks.add(TextBlock(textBeforeTag.trim()))
            }

            // Move past the text we just processed
            currentIndex = nextTagIndex

            // --- Begin parsing the tag itself ---
            val tagContentEnd = rawText.indexOf(']', startIndex = currentIndex)
            if (tagContentEnd == -1) {
                blocks.add(ParseErrorBlock("UNKNOWN", rawText.substring(currentIndex), "Unclosed start tag found."))
                break // Fatal parsing error for the rest of the string
            }

            val fullTagContent = rawText.substring(currentIndex + 4, tagContentEnd).trim() // Content between [AUF and ]
            val paramsStartIndex = fullTagContent.indexOf('(')
            val commandString = if (paramsStartIndex != -1) fullTagContent.substring(0, paramsStartIndex) else fullTagContent
            val normalizedCommand = commandString.replace("_", "").replace(" ", "").uppercase()

            val tool = toolRegistry.find { it.command == normalizedCommand }
            if (tool == null) {
                blocks.add(ParseErrorBlock(normalizedCommand, "", "Unknown tool command."))
                currentIndex = tagContentEnd + 1
                continue
            }

            // TODO: Implement parameter parsing logic here in a future step
            val params = emptyMap<String, String>()

            if (!tool.expectsPayload) {
                // This tool is a simple, self-closing tag. We don't have this type yet, but the architecture supports it.
                // For now, we assume all tools have payloads.
                blocks.add(ParseErrorBlock(tool.command, "", "Self-closing tags are not yet supported."))
                currentIndex = tagContentEnd + 1
                continue

            } else {
                val endTag = "[/AUF${normalizedCommand}]"
                val endTagIndex = rawText.indexOf(endTag, startIndex = tagContentEnd, ignoreCase = true)

                if (endTagIndex == -1) {
                    blocks.add(ParseErrorBlock(tool.command, rawText.substring(tagContentEnd + 1), "Closing tag not found."))
                    break // Fatal error, can't recover
                }

                val payload = rawText.substring(tagContentEnd + 1, endTagIndex).trim()
                blocks.add(processBlock(tool, params, payload))
                currentIndex = endTagIndex + endTag.length
            }
        }

        return blocks.filter { (it as? TextBlock)?.text?.isNotBlank() ?: true }
    }

    private fun processBlock(tool: ToolDefinition, params: Map<String, String>, content: String): ContentBlock {
        return try {
            when (tool.command) {
                "ACTION_MANIFEST" -> {
                    val cleanContent = content.removePrefix("```json").removeSuffix("```").trim()
                    val actions = jsonParser.decodeFromString(ListSerializer(Action.serializer()), cleanContent)
                    ActionBlock(actions = actions)
                }
                "FILE_VIEW" -> FileContentBlock(fileName = params["path"] ?: "unknown file", content = content, language = params["language"])
                "APP_REQUEST" -> AppRequestBlock(requestType = content)
                "STATE_ANCHOR" -> {
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