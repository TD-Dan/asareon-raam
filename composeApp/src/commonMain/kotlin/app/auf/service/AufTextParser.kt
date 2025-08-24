package app.auf.service

import app.auf.core.ActionBlock
import app.auf.core.AnchorBlock
import app.auf.core.AppRequestBlock
import app.auf.core.ContentBlock
import app.auf.core.FileContentBlock
import app.auf.core.ParseErrorBlock
import app.auf.core.TextBlock
import app.auf.model.Action
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class AufTextParser(private val jsonParser: Json) {

    private enum class State { DEFAULT, IN_BLOCK }

    fun parse(rawText: String): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        var currentState = State.DEFAULT
        var currentTag: String? = null
        var currentParams: String? = null
        val contentBuffer = StringBuilder()
        val textBuffer = StringBuilder()

        fun flushTextBuffer() {
            if (textBuffer.isNotBlank()) {
                blocks.add(TextBlock(textBuffer.toString().trim()))
                textBuffer.clear()
            }
        }

        rawText.lines().forEach { line ->
            val trimmedLine = line.trim()

            when (currentState) {
                State.DEFAULT -> {
                    val startTagRegex = Regex("^\\[AUF_?(\\w+)(?::(.*?))?\\]")
                    val startTagMatch = startTagRegex.find(trimmedLine)

                    if (startTagMatch != null) {
                        val tagName = startTagMatch.groupValues[1]
                        val endTag = "[/AUF_${tagName}]"
                        val altEndTag = "[/AUF${tagName}]"

                        // Check for single-line block
                        if (trimmedLine.endsWith(endTag) || trimmedLine.endsWith(altEndTag)) {
                            flushTextBuffer()
                            val endTagIndex = trimmedLine.lastIndexOf("[/AUF")
                            val content = trimmedLine.substring(startTagMatch.range.last + 1, endTagIndex).trim()
                            val params = startTagMatch.groupValues[2].ifEmpty { null }
                            blocks.add(processBlock(tagName, params, content))
                        } else {
                            // Start of a multi-line block
                            flushTextBuffer()
                            currentState = State.IN_BLOCK
                            currentTag = tagName
                            currentParams = startTagMatch.groupValues[2].ifEmpty { null }
                        }
                    } else {
                        textBuffer.appendLine(line)
                    }
                }
                State.IN_BLOCK -> {
                    val isEndTag = trimmedLine.startsWith("[/AUF") && trimmedLine.endsWith("]")
                    val endTagContent = if (isEndTag) trimmedLine.substring(5, trimmedLine.length - 1) else null
                    // --- FIX: Apply the same cleaning logic to the end tag as the start tag ---
                    val cleanedEndTag = endTagContent?.let { if(it.startsWith("_")) it.drop(1) else it }

                    if (cleanedEndTag == currentTag) {
                        blocks.add(processBlock(currentTag!!, currentParams, contentBuffer.toString().trim()))
                        contentBuffer.clear()
                        currentState = State.DEFAULT
                        currentTag = null
                        currentParams = null
                    } else {
                        val isNestedStartTag = trimmedLine.startsWith("[AUF") && !isEndTag
                        if (isNestedStartTag) {
                            val errorContent = "Detected nested start tag '$trimmedLine' inside an open '$currentTag' block."
                            blocks.add(ParseErrorBlock(currentTag!!, contentBuffer.toString().trim(), errorContent))
                            contentBuffer.clear()

                            // Re-process the line as a new block start
                            val startTagRegex = Regex("^\\[AUF_?(\\w+)(?::(.*?))?\\]")
                            val startTagMatch = startTagRegex.find(trimmedLine)
                            if (startTagMatch != null) {
                                currentTag = startTagMatch.groupValues[1]
                                currentParams = startTagMatch.groupValues[2].ifEmpty { null }
                                currentState = State.IN_BLOCK // Remain in this state
                            }
                        } else {
                            contentBuffer.appendLine(line)
                        }
                    }
                }
            }
        }

        flushTextBuffer()

        if (currentState == State.IN_BLOCK) {
            val errorContent = "Block was not properly closed at the end of the input."
            blocks.add(ParseErrorBlock(currentTag!!, contentBuffer.toString().trim(), errorContent))
        }

        return blocks.filter { block ->
            (block as? TextBlock)?.text?.isNotBlank() ?: true
        }
    }

    private fun processBlock(tag: String, params: String?, content: String): ContentBlock {
        return try {
            when (tag) {
                "ACTION_MANIFEST" -> {
                    // --- FIX: Sanitize markdown fences before parsing ---
                    val cleanContent = content.removePrefix("```json").removeSuffix("```").trim()
                    val actions = jsonParser.decodeFromString(ListSerializer(Action.serializer()), cleanContent)
                    ActionBlock(actions = actions)
                }
                "FILE_VIEW" -> FileContentBlock(fileName = params ?: "unknown file", content = content)
                "APP_REQUEST" -> AppRequestBlock(requestType = content)
                "STATE_ANCHOR" -> {
                    val jsonObject = jsonParser.decodeFromString<JsonObject>(content)
                    val anchorId = jsonObject["anchorId"]?.jsonPrimitive?.content ?: "unknown-anchor"
                    AnchorBlock(anchorId, jsonObject)
                }
                else -> ParseErrorBlock(tag, content, "Unknown AUF Tag '$tag'")
            }
        } catch (e: Exception) {
            ParseErrorBlock(tag, content, "A deserialization error occurred: ${e.message}")
        }
    }
}