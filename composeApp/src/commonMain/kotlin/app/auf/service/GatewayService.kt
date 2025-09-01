package app.auf.service

import app.auf.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

/**
 * ---
 * ## Mandate
 * Orchestrates all communication with the external AI service (e.g., Google AI).
 * It is responsible for formatting requests from the app's data model into the API's
 * required format, sending them via the `Gateway`, and providing lists of available models.
 * It uses the `compiledContent` of system messages for token efficiency.
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
                val apiRequestContents = buildApiContentsFromChatHistory(messages)
                val response = gateway.generateContent(apiKey, selectedModel, apiRequestContents)

                response.error?.let {
                    return@withContext GatewayResponse(
                        errorMessage = "API Error: ${it.message} (Code: ${it.code})"
                    )
                }

                val rawTextResponse = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    ?: response.promptFeedback?.blockReason?.let { "Blocked: $it" }
                    ?: "No content received, but no error was reported."

                GatewayResponse(
                    contentBlocks = parser.parse(rawTextResponse),
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

    open fun buildApiContentsFromChatHistory(messages: List<ChatMessage>): List<Content> {
        if (messages.isEmpty()) return emptyList()

        // --- INSTRUMENTATION START ---
        println("[GatewayService] --- Starting Prompt Build ---")
        // --- INSTRUMENTATION END ---

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

            val contentToSend: String
            // --- INSTRUMENTATION START ---
            print("[GatewayService] Processing msg '${msg.title ?: msg.author}': ")
            // --- INSTRUMENTATION END ---
            when (msg.author) {
                Author.SYSTEM -> {
                    if (msg.compiledContent != null) {
                        contentToSend = msg.compiledContent
                        println("Using compiledContent.")
                    } else {
                        contentToSend = msg.rawContent ?: ""
                        println("FALLING BACK to rawContent.")
                    }
                }
                else -> {
                    contentToSend = msg.rawContent ?: ""
                    println("Using rawContent.")
                }
            }


            val contentToAdd = if (msg.author == Author.SYSTEM) {
                "--- START OF FILE ${msg.title} ---\n$contentToSend"
            } else {
                contentToSend
            }
            currentParts.add(contentToAdd)
        }

        commitCurrentBlock()
        // --- INSTRUMENTATION START ---
        println("[GatewayService] --- Prompt Build Complete: ${mergedContents.size} blocks. ---")
        // --- INSTRUMENTATION END ---
        return mergedContents
    }
}