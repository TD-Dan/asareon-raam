package app.auf.service

import app.auf.core.Author
import app.auf.core.ChatMessage
import app.auf.core.GatewayResponse
import app.auf.core.TextBlock
import app.auf.model.ToolDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

/**
 * ---
 * ## Mandate
 * Orchestrates all communication with the external AI service (e.g., Google AI).
 * It is responsible for formatting requests from the app's data model into the API's
 * required format, sending them via the `Gateway`, and providing lists of available models.
 * It uses `AufTextParser` for any necessary message reconstruction.
 *
 * ---
 * ## Dependencies
 * - `app.auf.service.Gateway`: The client for the AI service API.
 * - `app.auf.service.AufTextParser`: The canonical parser/reconstructor.
 *
 * @version 3.4
 * @since 2025-08-24
 */
open class GatewayService(
    private val gateway: Gateway,
    private val parser: AufTextParser, // Parser is still a dependency for AppState's factory
    private val toolRegistry: List<ToolDefinition>,
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

                // The raw string is the source of truth.
                GatewayResponse(
                    contentBlocks = parser.parse(rawTextResponse), // This is now a VIEW of the raw content
                    usageMetadata = response.usageMetadata,
                    rawContent = rawTextResponse
                )
            } catch (e: Exception) {
                GatewayResponse(errorMessage = "Gateway Error: ${e.message}")
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

    private fun convertChatToApiContents(messages: List<ChatMessage>): List<Content> {
        if (messages.isEmpty()) return emptyList()

        val mergedContents = mutableListOf<Content>()
        val currentParts = mutableListOf<String>()
        var currentRole = when (messages.first().author) {
            Author.AI -> "model"
            else -> "user"
        }

        fun commitCurrentBlock() {
            if (currentParts.isNotEmpty()) {
                val combinedText = currentParts.joinToString("\n\n")
                mergedContents.add(Content(currentRole, listOf(Part(combinedText))))
                currentParts.clear()
            }
        }

        for (msg in messages) {
            val msgRole = when (msg.author) {
                Author.AI -> "model"
                else -> "user"
            }

            if (msgRole != currentRole) {
                commitCurrentBlock()
                currentRole = msgRole
            }

            // MODIFICATION: Logic simplified. Prioritize rawContent, fall back to TextBlocks only if rawContent is null.
            val content = msg.rawContent ?: msg.contentBlocks.filterIsInstance<TextBlock>().joinToString("\n") { it.text }

            val contentToAdd = if (msg.author == Author.SYSTEM) {
                "--- START OF FILE ${msg.title} ---\n$content"
            } else {
                content
            }
            currentParts.add(contentToAdd)
        }

        commitCurrentBlock()
        return mergedContents
    }
}