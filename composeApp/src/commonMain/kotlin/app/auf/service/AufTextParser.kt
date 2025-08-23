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

            val isStartTag = trimmedLine.startsWith("[AUF_") && trimmedLine.endsWith("]")
            val isEndTag = trimmedLine.startsWith("[/AUF_") && trimmedLine.endsWith("]")

            when (currentState) {
                State.DEFAULT -> {
                    if (isStartTag) {
                        flushTextBuffer()
                        currentState = State.IN_BLOCK
                        val tagContent = trimmedLine.substring(5, trimmedLine.length - 1) // Corrected slicing
                        val parts = tagContent.split(':', limit = 2)
                        currentTag = parts[0]
                        currentParams = if (parts.size > 1) parts[1].trim() else null
                    } else {
                        textBuffer.appendLine(line)
                    }
                }
                State.IN_BLOCK -> {
                    val endTagMatch = if (isEndTag) trimmedLine.substring(6, trimmedLine.length - 1) else null
                    if (endTagMatch == currentTag) {
                        blocks.add(processBlock(currentTag!!, currentParams, contentBuffer.toString().trim()))
                        contentBuffer.clear()
                        currentState = State.DEFAULT
                        currentTag = null
                        currentParams = null
                    } else if (isStartTag) {
                        val errorContent = "Detected nested start tag '$trimmedLine' inside an open '$currentTag' block."
                        blocks.add(ParseErrorBlock(currentTag!!, contentBuffer.toString(), errorContent))
                        contentBuffer.clear()
                        val tagContent = trimmedLine.substring(5, trimmedLine.length - 1)
                        val parts = tagContent.split(':', limit = 2)
                        currentTag = parts[0]
                        currentParams = if (parts.size > 1) parts[1].trim() else null
                    } else {
                        contentBuffer.appendLine(line)
                    }
                }
            }
        }

        flushTextBuffer()

        if (currentState == State.IN_BLOCK) {
            val errorContent = "Block was not properly closed at the end of the input."
            blocks.add(ParseErrorBlock(currentTag!!, contentBuffer.toString(), errorContent))
        }

        return blocks.filter { block ->
            (block as? TextBlock)?.text?.isNotBlank() ?: true
        }
    }

    private fun processBlock(tag: String, params: String?, content: String): ContentBlock {
        return try {
            when (tag) {
                "ACTION_MANIFEST" -> {
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