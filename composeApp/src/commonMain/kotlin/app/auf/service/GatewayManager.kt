package app.auf.service

import app.auf.model.Action
import app.auf.core.ActionBlock
import app.auf.core.AnchorBlock
import app.auf.core.AppRequestBlock
import app.auf.core.Author
import app.auf.core.ChatMessage
import app.auf.core.ContentBlock
import app.auf.core.FileContentBlock
import app.auf.core.GatewayResponse
import app.auf.core.TextBlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * ---
 * ## Mandate
 * Orchestrates all communication with the external AI service (e.g., Google AI).
 * It is responsible for formatting requests, sending them via the `Gateway` interface,
 * and parsing the raw response into the application's structured `ContentBlock` format.
 *
 * ---
 * ## Dependencies
 * - `app.auf.service.Gateway`: The platform-specific implementation of the AI service client.
 * - `kotlinx.serialization.json.Json`: For parsing structured blocks from the AI response.
 *
 * @version 2.0
 * @since 2025-08-16
 */
open class GatewayManager(
    private val gateway: Gateway,
    private val jsonParser: Json,
    private val apiKey: String
) {
    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    open suspend fun sendMessage(selectedModel: String, messages: List<ChatMessage>): GatewayResponse {
        return withContext(coroutineScope.coroutineContext) {
            try {
                val apiRequestContents = convertChatToApiContents(messages)
                val response = gateway.generateContent(apiKey, selectedModel, apiRequestContents)

                response.error?.let {
                    return@withContext GatewayResponse(
                        contentBlocks = emptyList(),
                        rawContent = "API Error",
                        usageMetadata = null,
                        errorMessage = "API Error: ${it.message} (Code: ${it.code})"
                    )
                }

                val rawTextResponse = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    ?: response.promptFeedback?.blockReason?.let { "Blocked: $it" }
                    ?: "No content received, but no error was reported."

                val parsedBlocks = parseRawContentToBlocks(rawTextResponse)

                GatewayResponse(
                    contentBlocks = parsedBlocks,
                    usageMetadata = response.usageMetadata,
                    rawContent = rawTextResponse
                )
            } catch (e: Exception) {
                GatewayResponse(
                    contentBlocks = emptyList(),
                    rawContent = "Gateway Error",
                    usageMetadata = null,
                    errorMessage = "Gateway Error: ${e.message}"
                )
            }
        }
    }

    open suspend fun listModels(): List<ModelInfo> {
        return withContext(coroutineScope.coroutineContext) {
            gateway.listModels(apiKey)
        }
    }

    private fun parseRawContentToBlocks(rawText: String): List<ContentBlock> {
        val normalizedText = rawText.replace("\r\n", "\n")
        val blocks = mutableListOf<ContentBlock>()

        val regex = Regex("""\[AUF_([A-Z_]+)(?::\s*(.*?))?]\s*([\s\S]*?)\s*\[/AUF_\1]""", setOf(RegexOption.MULTILINE))

        var lastIndex = 0

        // Use the normalized text for all subsequent operations.
        regex.findAll(normalizedText).forEach { matchResult ->
            if (matchResult.range.first > lastIndex) {
                val precedingText = normalizedText.substring(lastIndex, matchResult.range.first).trim()
                if (precedingText.isNotEmpty()) {
                    blocks.add(TextBlock(precedingText))
                }
            }

            val (tag, params, content) = matchResult.destructured
            try {
                when (tag) {
                    "ACTION_MANIFEST" -> {
                        val cleanContent = content.trim().removePrefix("```json").removePrefix("```").trim().removeSuffix("```")
                        val actions = jsonParser.decodeFromString<List<Action>>(cleanContent)
                        blocks.add(ActionBlock(actions = actions))
                    }
                    "FILE_VIEW" -> {
                        blocks.add(FileContentBlock(fileName = params.trim(), content = content.trim()))
                    }
                    "APP_REQUEST" -> {
                        blocks.add(AppRequestBlock(requestType = content.trim()))
                    }
                    "STATE_ANCHOR" -> {
                        val jsonObject = jsonParser.decodeFromString<JsonObject>(content)
                        val anchorId = jsonObject["anchorId"]?.jsonPrimitive?.content ?: "unknown-anchor"
                        blocks.add(AnchorBlock(anchorId, jsonObject))
                    }
                }
            } catch (e: Exception) {
                blocks.add(TextBlock("--- ERROR PARSING BLOCK ---\nTAG: $tag\nERROR: ${e.message}\nCONTENT:\n$content\n--- END ERROR ---"))
            }

            lastIndex = matchResult.range.last + 1
        }

        if (lastIndex < normalizedText.length) {
            val trailingText = normalizedText.substring(lastIndex).trim()
            if (trailingText.isNotEmpty()) {
                blocks.add(TextBlock(trailingText))
            }
        }

        if (blocks.isEmpty() && normalizedText.isNotBlank()) {
            blocks.add(TextBlock(normalizedText))
        }

        return blocks
    }

    private fun convertChatToApiContents(messages: List<ChatMessage>): List<Content> {
        val apiContents = mutableListOf<Content>()
        messages.forEach { msg ->
            val reconstructedContent = msg.contentBlocks.joinToString(separator = "\n") { block ->
                when (block) {
                    is TextBlock -> block.text
                    is ActionBlock -> "[AUF_ACTION_MANIFEST]\n${jsonParser.encodeToString(ListSerializer(Action.serializer()), block.actions)}\n[/AUF_ACTION_MANIFEST]"
                    else -> "[System placeholder for block type: ${block::class.simpleName}]"
                }
            }

            when (msg.author) {
                Author.USER, Author.AI -> {
                    val role = if (msg.author == Author.AI) "model" else "user"
                    apiContents.add(Content(role, listOf(Part(reconstructedContent))))
                }
                Author.SYSTEM -> {
                    val fullContent = "--- START OF FILE ${msg.title} ---\n$reconstructedContent"
                    apiContents.add(Content("user", listOf(Part(fullContent))))
                }
            }
        }
        val mergedContents = mutableListOf<Content>()
        if (apiContents.isNotEmpty()) {
            var currentRole = apiContents.first().role
            val currentParts = mutableListOf<String>()
            apiContents.forEach { content ->
                if (content.role == currentRole) {
                    currentParts.add(content.parts.first().text)
                } else {
                    mergedContents.add(Content(currentRole, listOf(Part(currentParts.joinToString("\n\n")))))
                    currentRole = content.role
                    currentParts.clear()
                    currentParts.add(content.parts.first().text)
                }
            }
            mergedContents.add(Content(currentRole, listOf(Part(currentParts.joinToString("\n\n")))))
        }
        return mergedContents
    }
}