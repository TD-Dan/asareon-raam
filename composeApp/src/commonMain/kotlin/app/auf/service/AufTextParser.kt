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
    private val startTagRegex = Regex("""^\[AUF_([A-Z_]+)(?::\s*(.*?))?]\s*$""")
    private val endTagRegex = Regex("""^\[/AUF_([A-Z_]+)]\s*$""")

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
            val startMatch = startTagRegex.find(line)
            val endMatch = endTagRegex.find(line)

            when (currentState) {
                State.DEFAULT -> {
                    if (startMatch != null) {
                        flushTextBuffer()
                        currentState = State.IN_BLOCK
                        currentTag = startMatch.groupValues[1]
                        currentParams = startMatch.groupValues.getOrNull(2)
                    } else {
                        textBuffer.appendLine(line)
                    }
                }
                State.IN_BLOCK -> {
                    if (endMatch != null && endMatch.groupValues[1] == currentTag) {
                        blocks.add(processBlock(currentTag!!, currentParams, contentBuffer.toString()))
                        contentBuffer.clear()
                        currentState = State.DEFAULT
                        currentTag = null
                        currentParams = null
                    } else if (startMatch != null) {
                        val errorContent = "Detected nested start tag '${startMatch.value}' inside an open '$currentTag' block."
                        blocks.add(ParseErrorBlock(currentTag!!, contentBuffer.toString(), errorContent))
                        contentBuffer.clear()
                        currentTag = startMatch.groupValues[1]
                        currentParams = startMatch.groupValues.getOrNull(2)
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

        return blocks
    }

    private fun processBlock(tag: String, params: String?, content: String): ContentBlock {
        return try {
            when (tag) {
                "ACTION_MANIFEST" -> {
                    val cleanContent = content.trim().removePrefix("```json").removePrefix("```").trim().removeSuffix("```")
                    val actions = jsonParser.decodeFromString<List<Action>>(cleanContent)
                    ActionBlock(actions = actions)
                }
                "FILE_VIEW" -> FileContentBlock(fileName = params?.trim() ?: "unknown file", content = content.trim())
                "APP_REQUEST" -> AppRequestBlock(requestType = content.trim())
                "STATE_ANCHOR" -> {
                    val jsonObject = jsonParser.decodeFromString<JsonObject>(content)
                    val anchorId = jsonObject["anchorId"]?.jsonPrimitive?.content ?: "unknown-anchor"
                    AnchorBlock(anchorId, jsonObject)
                }
                else -> ParseErrorBlock(tag, content, "Unknown AUF Tag '$tag'")
            }
        } catch (e: Exception) {
            ParseErrorBlock(tag, content, e.message ?: "An unknown deserialization error occurred.")
        }
    }
}