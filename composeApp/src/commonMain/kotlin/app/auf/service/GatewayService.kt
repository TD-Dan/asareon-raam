package app.auf.service

import app.auf.core.ActionBlock
import app.auf.core.AnchorBlock
import app.auf.core.AppRequestBlock
import app.auf.core.Author
import app.auf.core.ChatMessage
import app.auf.core.ContentBlock
import app.auf.core.FileContentBlock
import app.auf.core.GatewayResponse
import app.auf.core.ParseErrorBlock
import app.auf.core.SentinelBlock
import app.auf.core.TextBlock
import app.auf.model.Action
import app.auf.model.ToolDefinition
import app.auf.util.JsonProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject

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
 * @version 3.3
 * @since 2025-08-24
 */
open class GatewayService(
    private val gateway: Gateway,
    private val parser: AufTextParser,
    private val toolRegistry: List<ToolDefinition>, // <<< Injected dependency
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

    private fun convertChatToApiContents(messages: List<ChatMessage>): List<Content> {
        if (messages.isEmpty()) return emptyList()

        val mergedContents = mutableListOf<Content>()
        var currentParts = mutableListOf<String>()
        // --- FIX: Merge based on the final API role, not the Author enum ---
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
                else -> "user" // SYSTEM and USER both map to the 'user' role for the API
            }

            if (msgRole != currentRole) {
                commitCurrentBlock()
                currentRole = msgRole
            }

            // --- FIX: Use robust reconstruction logic, eliminating placeholders ---
            val reconstructedContent = msg.rawContent ?: msg.contentBlocks.joinToString(separator = "\n") { block ->
                reconstructBlockToString(block)
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

    // --- FIX: This helper ensures consistent, high-fidelity reconstruction ---
    private fun reconstructBlockToString(block: ContentBlock): String {
        return when (block) {
            is TextBlock -> block.text
            is ActionBlock -> {
                val command = toolRegistry.find { it.name == "Action Manifest" }?.command ?: "ACTION_MANIFEST"
                val content = JsonProvider.appJson.encodeToString(ListSerializer(Action.serializer()), block.actions)
                "[AUF_${command}]\n$content\n[/AUF_${command}]"
            }
            is FileContentBlock -> {
                val command = toolRegistry.find { it.name == "File View" }?.command ?: "FILE_VIEW"
                val langParam = block.language?.let { ", language=\"$it\"" } ?: ""
                "[AUF_${command}(path=\"${block.fileName}\"$langParam)]\n${block.content}\n[/AUF_${command}]"
            }
            is AppRequestBlock -> {
                val command = toolRegistry.find { it.name == "App Request" }?.command ?: "APP_REQUEST"
                "[AUF_${command}]${block.requestType}[/AUF_${command}]"
            }
            is AnchorBlock -> {
                val command = toolRegistry.find { it.name == "State Anchor" }?.command ?: "STATE_ANCHOR"
                val content = JsonProvider.appJson.encodeToString(JsonObject.serializer(), block.content)
                "[AUF_${command}]\n$content\n[/AUF_${command}]"
            }
            is ParseErrorBlock -> "<!-- PARSE ERROR: ${block.errorMessage} | RAW: ${block.rawContent} -->"
            is SentinelBlock -> "<!-- SENTINEL: ${block.message} -->"
        }
    }
}