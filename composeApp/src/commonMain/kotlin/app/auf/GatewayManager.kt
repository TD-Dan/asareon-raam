package app.auf

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json // <<< FIX IS HERE: Added the missing import

/**
 * A simple data class to represent a processed AI response,
 * decoupling StateManager from the raw API response models.
 */
data class AIResponse(
    val content: String,
    val actionManifest: List<Action>? = null,
    val usageMetadata: UsageMetadata? = null,
    val errorMessage: String? = null
)

/**
 * Manages all communication with the remote AI gateway.
 * It is responsible for formatting requests, making API calls,
 * and parsing responses into a clean, app-usable format.
 */
class GatewayManager(
    private val apiKey: String,
    private val jsonParser: Json
) {
    private val gateway = Gateway()
    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    /**
     * Sends a list of chat messages to the AI and returns a processed response.
     * @param selectedModel The name of the AI model to use.
     * @param messages The full list of messages (system and chat history) to send.
     * @return An [AIResponse] object containing the result of the API call.
     */
    suspend fun sendMessage(selectedModel: String, messages: List<ChatMessage>): AIResponse {
        return withContext(coroutineScope.coroutineContext) {
            try {
                val apiRequestContents = convertChatToApiContents(messages)
                val response = gateway.generateContent(apiKey, selectedModel, apiRequestContents)

                response.error?.let {
                    return@withContext AIResponse(content = "", errorMessage = "API Error: ${it.message} (Code: ${it.code})")
                }

                val responseContent = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    ?: response.promptFeedback?.blockReason?.let { "Blocked: $it" }
                    ?: "No content received, but no error was reported."

                val manifestStartTag = "[AUF_ACTION_MANIFEST]"
                val manifestEndTag = "[/AUF_ACTION_MANIFEST]"

                if (responseContent.contains(manifestStartTag) && responseContent.contains(manifestEndTag)) {
                    val manifestJson = responseContent.substringAfter(manifestStartTag).substringBeforeLast(manifestEndTag).trim()
                    val parsedActions = jsonParser.decodeFromString<List<Action>>(manifestJson)
                    val summary = "The AI has proposed ${parsedActions.size} action(s). Please review and confirm."
                    AIResponse(
                        content = summary,
                        actionManifest = parsedActions,
                        usageMetadata = response.usageMetadata
                    )
                } else {
                    AIResponse(
                        content = responseContent,
                        usageMetadata = response.usageMetadata
                    )
                }
            } catch (e: Exception) {
                AIResponse(content = "", errorMessage = "Gateway Error: ${e.message}")
            }
        }
    }


    /**
     * Converts the application's internal ChatMessage format to the list of
     * Content objects required by the specific generative AI API.
     * It also handles the merging of consecutive 'user' role messages.
     */
    private fun convertChatToApiContents(messages: List<ChatMessage>): List<Content> {
        val apiContents = mutableListOf<Content>()
        messages.forEach { msg ->
            when (msg.author) {
                Author.USER, Author.AI -> {
                    val role = if (msg.author == Author.AI) "model" else "user"
                    apiContents.add(Content(role, listOf(Part(msg.content))))
                }
                Author.SYSTEM -> {
                    val fullContent = "--- START OF FILE ${msg.title} ---\n${msg.content}"
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

    /**
     * Fetches the list of available models from the API.
     */
    suspend fun listModels(): List<ModelInfo> {
        return withContext(coroutineScope.coroutineContext) {
            gateway.listModels(apiKey)
        }
    }
}