package app.auf

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Represents a structured, clean response from the AI Gateway.
 * This is the data object that the StateManager will receive.
 */
data class AIResponse(
    val contentBlocks: List<ContentBlock>,
    val rawContent: String?,
    val usageMetadata: UsageMetadata?,
    val errorMessage: String? = null
)

/**
 * Manages all communication with the external AI gateway (e.g., Gemini API).
 *
 * ---
 * ## Mandate
 * This class's mandate is to:
 * 1. Convert our internal `ChatMessage` list into the format required by the external API.
 * 2. Send the request via the injected `Gateway` instance.
 * 3. Receive the raw response from the API.
 * 4. Parse the raw string into a structured list of `ContentBlock`s.
 * 5. Return a clean `AIResponse` object to the `StateManager`.
 * It does NOT modify the application state directly. It depends on an external `Gateway`
 * to handle the actual network communication.
 *
 * ---
 * ## Dependencies
 * - `app.auf.Gateway`
 * - `kotlinx.serialization.json.Json`
 *
 * @version 1.3
 * @since 2025-08-14
 */
open class GatewayManager(
    // --- DI REFACTOR: Inject the Gateway dependency and pass config like the API key. ---
    private val gateway: Gateway,
    private val jsonParser: Json,
    private val apiKey: String
) {
    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    open suspend fun sendMessage(selectedModel: String, messages: List<ChatMessage>): AIResponse {
        return withContext(coroutineScope.coroutineContext) {
            try {
                val apiRequestContents = convertChatToApiContents(messages)
                // --- DI REFACTOR: Use the injected gateway instance with the configured API key. ---
                val response = gateway.generateContent(apiKey, selectedModel, apiRequestContents)

                response.error?.let {
                    // --- FIX: Explicitly name parameters and provide null for missing values. ---
                    return@withContext AIResponse(
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

                AIResponse(
                    contentBlocks = parsedBlocks,
                    usageMetadata = response.usageMetadata,
                    rawContent = rawTextResponse
                )
            } catch (e: Exception) {
                // --- FIX: Explicitly name parameters and provide null for missing values. ---
                AIResponse(
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
            // --- DI REFACTOR: Use the injected gateway instance with the configured API key. ---
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