package app.auf

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json // This is the ONLY 'Json' import we need.
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Manages all communication with the external AI gateway (e.g., Gemini API).
 * Its mandate is to:
 * 1. Convert our internal `ChatMessage` list into the format required by the external API.
 * 2. Send the request via the `Gateway` class.
 * 3. Receive the raw response string from the API.
 * 4. Parse the raw string into a structured list of `ContentBlock`s.
 * 5. Return a clean `AIResponse` object to the `StateManager`.
 * It does NOT modify the application state directly.
 */
data class AIResponse(
    val contentBlocks: List<ContentBlock>,
    val usageMetadata: UsageMetadata? = null,
    val errorMessage: String? = null,
    val rawContent: String
)

open class GatewayManager(
    private val apiKey: String,
    private val jsonParser: Json
) {
    // The configured parser is passed down to the Gateway instance.
    private val gateway = Gateway(jsonParser)
    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    suspend fun sendMessage(selectedModel: String, messages: List<ChatMessage>): AIResponse {
        return withContext(coroutineScope.coroutineContext) {
            try {
                val apiRequestContents = convertChatToApiContents(messages)
                val response = gateway.generateContent(apiKey, selectedModel, apiRequestContents)

                response.error?.let {
                    return@withContext AIResponse(
                        contentBlocks = emptyList(),
                        rawContent = "API Error",
                        errorMessage = "API Error: ${it.message} (Code: ${it.code})"
                    )
                }

                val rawTextResponse = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    ?: response.promptFeedback?.blockReason?.let { "Blocked: $it" }
                    ?: "No content received, but no error was reported."

                val parsedBlocks = parseRawContentToBlocks(rawTextResponse)

                AIResponse(
                    contentBlocks = parsedBlocks,
                    usageMetadata = response.usageMetadata,
                    rawContent = rawTextResponse
                )
            } catch (e: Exception) {
                AIResponse(contentBlocks = emptyList(), rawContent = "Gateway Error", errorMessage = "Gateway Error: ${e.message}")
            }
        }
    }

    suspend fun listModels(): List<ModelInfo> {
        return withContext(coroutineScope.coroutineContext) {
            gateway.listModels(apiKey)
        }
    }

    private fun parseRawContentToBlocks(rawText: String): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        val regex = Regex("""\[AUF_([A-Z_]+)(?::\s*(.*?))?\]\s*(.*?)\s*\[/AUF_\1\]""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE))
        var lastIndex = 0

        regex.findAll(rawText).forEach { matchResult ->
            if (matchResult.range.first > lastIndex) {
                val precedingText = rawText.substring(lastIndex, matchResult.range.first).trim()
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

        if (lastIndex < rawText.length) {
            val trailingText = rawText.substring(lastIndex).trim()
            if (trailingText.isNotEmpty()) {
                blocks.add(TextBlock(trailingText))
            }
        }

        if (blocks.isEmpty() && rawText.isNotBlank()) {
            blocks.add(TextBlock(rawText))
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