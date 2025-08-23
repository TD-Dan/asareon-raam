// --- FILE: composeApp/src/commonMain/kotlin/app/auf/service/GatewayService.kt ---
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
 * @version 3.1
 * @since 2025-08-17
 */
open class GatewayService(
    private val gateway: Gateway,
    private val parser: AufTextParser,
    private val apiKey: String,
    private val coroutineScope: CoroutineScope // <<< MODIFIED: Injected CoroutineScope
) {
    // <<< MODIFIED: Removed internal coroutineScope definition

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

    private fun convertChatToApiContents(messages: List<ChatMessage>): List<Content> {
        val apiContents = mutableListOf<Content>()
        messages.forEach { msg ->
            val reconstructedContent = msg.contentBlocks.joinToString(separator = "\n") { block ->
                when (block) {
                    is app.auf.core.TextBlock -> block.text
                    // We only need to handle TextBlock here because user/system messages are always TextBlocks.
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