package app.auf.service

import app.auf.core.ChatMessage
import app.auf.core.GatewayResponse
import app.auf.core.Author
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

/**
 * ---
 * ## Mandate
 * Orchestrates all communication with the external AI service (e.g., Google AI).
 * It is responsible for formatting requests from the app's data model into the API's
 * required format, sending them via the `Gateway`, and providing lists of available models
 * based on their capabilities. It delegates the parsing of the raw response string to
 * the `AufTextParser`.
 *
 * ---
 * ## Dependencies
 * - `app.auf.service.Gateway`: The client for the AI service API.
 * - `app.auf.service.AufTextParser`: The canonical parser for the AUF tagged-text format.
 *
 * @version 3.2
 * @since 2025-08-24
 */
open class GatewayService(
    private val gateway: Gateway,
    private val parser: AufTextParser,
    private val apiKey: String,
    private val coroutineScope: CoroutineScope
) {

    open suspend fun sendMessage(selectedModel: String, messages: List<ChatMessage>): GatewayResponse {
        return withContext(coroutineScope.coroutineContext) {
            try {
                val apiRequestContents = convertChatToApiContents(messages)
                val response = gateway.generateContent(apiKey, selectedModel, apiRequestContents)

                response.error?.let {
                    return@withContext GatewayResponse(
                        errorMessage = "API Error: ${it.message} (Code: ${it.code})"
                    )
                }

                val rawTextResponse = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    ?: response.promptFeedback?.blockReason?.let { "Blocked: $it" }
                    ?: "No content received, but no error was reported."

                val parsedBlocks = parser.parse(rawTextResponse)

                GatewayResponse(
                    contentBlocks = parsedBlocks,
                    usageMetadata = response.usageMetadata,
                    rawContent = rawTextResponse
                )
            } catch (e: Exception) {
                GatewayResponse(
                    errorMessage = "Gateway Error: ${e.message}"
                )
            }
        }
    }

    open suspend fun listTextModels(): List<String> {
        return withContext(coroutineScope.coroutineContext) {
            gateway.listModels(apiKey)
                .filter { "generateContent" in it.supportedGenerationMethods }
                .map { it.name.replace("models/", "") }
                .sorted()
        }
    }

    // --- FIX: Rewritten for author-aware merging ---
    private fun convertChatToApiContents(messages: List<ChatMessage>): List<Content> {
        if (messages.isEmpty()) return emptyList()

        val mergedContents = mutableListOf<Content>()
        var currentParts = mutableListOf<String>()
        var currentAuthor = messages.first().author

        fun commitCurrentBlock() {
            if (currentParts.isNotEmpty()) {
                val role = when (currentAuthor) {
                    Author.AI -> "model"
                    Author.USER, Author.SYSTEM -> "user"
                }
                val combinedText = currentParts.joinToString("\n\n")
                mergedContents.add(Content(role, listOf(Part(combinedText))))
                currentParts.clear()
            }
        }

        for (msg in messages) {
            if (msg.author != currentAuthor) {
                commitCurrentBlock()
                currentAuthor = msg.author
            }

            val reconstructedContent = msg.contentBlocks.joinToString(separator = "\n") { block ->
                when (block) {
                    is app.auf.core.TextBlock -> block.text
                    else -> "[System placeholder for block type: ${block::class.simpleName}]"
                }
            }

            val contentToAdd = if (msg.author == Author.SYSTEM) {
                "--- START OF FILE ${msg.title} ---\n$reconstructedContent"
            } else {
                reconstructedContent
            }
            currentParts.add(contentToAdd)
        }

        commitCurrentBlock() // Commit the last block
        return mergedContents
    }
}