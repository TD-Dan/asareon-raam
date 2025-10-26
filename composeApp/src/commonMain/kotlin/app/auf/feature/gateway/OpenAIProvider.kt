package app.auf.feature.gateway.openai

import app.auf.core.Action
import app.auf.core.generated.ActionNames
import app.auf.feature.gateway.*
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.coroutines.cancellation.CancellationException

// --- Data Contracts specific to the OpenAI API ---
@Serializable
private data class OpenAIChatResponse(
    val choices: List<Choice>? = null,
    val error: ApiError? = null
)
@Serializable
private data class Choice(val message: OpenAIMessage)
@Serializable
private data class OpenAIMessage(val role: String, val content: String?, val name: String? = null)
@Serializable
private data class ApiError(val message: String)

@Serializable
private data class ListModelsResponse(val data: List<ModelInfo> = emptyList())
@Serializable
private data class ModelInfo(val id: String)


/**
 * A concrete implementation of the AgentGatewayProvider for the OpenAI API.
 */
class OpenAIProvider(
    private val platformDependencies: PlatformDependencies
) : UniversalGatewayProvider {
    override val id: String = "openai"
    private val apiKeySettingKey = "gateway.openai.apiKey"
    private val API_HOST = "api.openai.com"

    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 240_000 }
    }

    // --- Logic extracted for testability ---

    /** Builds the provider-specific JSON payload from a universal request. */
    internal fun buildRequestPayload(request: GatewayRequest): JsonElement {
        val openAiMessages = buildJsonArray {
            // NEW: Add the system prompt if it exists.
            request.systemPrompt?.let {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", it)
                })
            }
            request.contents.forEach { message ->
                // Use the high-clarity, timestamp-aware format for both content and name.
                val formattedTimestamp = platformDependencies.formatIsoTimestamp(message.timestamp)
                val enrichedContent = "${message.senderName} (${message.senderId}) @ ${formattedTimestamp}: ${message.content}"
                val sanitizedName = "${message.senderName}_${message.senderId}".replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)
                add(buildJsonObject {
                    put("role", if (message.role == "model") "assistant" else message.role)
                    put("content", enrichedContent)
                    put("name", sanitizedName)
                })
            }
        }
        return buildJsonObject {
            put("model", request.modelName)
            put("messages", openAiMessages)
        }
    }

    /** Parses a raw JSON response body into a universal GatewayResponse. */
    internal fun parseResponse(responseBody: String, correlationId: String): GatewayResponse {
        val response = json.decodeFromString<OpenAIChatResponse>(responseBody)

        // Path 1: Hard API Error
        response.error?.let {
            return GatewayResponse(null, "API Error: ${it.message}", correlationId)
        }

        // Path 2: Successful Content Generation
        val rawText = response.choices?.firstOrNull()?.message?.content
        if (rawText != null) {
            return GatewayResponse(rawText, null, correlationId)
        }

        // Path 3 (Future-Proofing): Unrecognized response format
        platformDependencies.log(
            LogLevel.ERROR,
            id,
            "Unrecognised response format from OpenAI API. Full response: $responseBody"
        )
        return GatewayResponse(null, "Unrecognised response format from OpenAI API.", correlationId)
    }


    // --- Public Interface Implementation ---

    override fun registerSettings(dispatch: (Action) -> Unit) {
        val payload = buildJsonObject {
            put("key", apiKeySettingKey)
            put("type", "STRING")
            put("label", "OpenAI API Key")
            put("description", "API Key for OpenAI models (e.g., GPT-4o).")
            put("section", "API Keys")
            put("defaultValue", "")
        }
        dispatch(Action(ActionNames.SETTINGS_ADD, payload))
    }

    override suspend fun listAvailableModels(settings: Map<String, String>): List<String> {
        val apiKey = settings[apiKeySettingKey].orEmpty()
        if (apiKey.isBlank()) return emptyList()

        return try {
            val response: ListModelsResponse = client.get("https://$API_HOST/v1/models") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }.body()
            response.data
                .map { it.id }
                .filter { it.startsWith("gpt-") } // Filter for common chat models to keep the list clean
                .sorted()
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.WARN, id, "Failed to fetch OpenAI models: ${e.message}")
            emptyList()
        }
    }

    override suspend fun generateContent(request: GatewayRequest, settings: Map<String, String>): GatewayResponse {
        val apiKey = settings[apiKeySettingKey].orEmpty()
        if (apiKey.isBlank()) {
            return GatewayResponse(null, "OpenAI API Key is not configured.", request.correlationId)
        }

        return try {
            val apiRequest = buildRequestPayload(request)
            val apiUrl = "https://$API_HOST/v1/chat/completions"

            val responseBody: String = client.post(apiUrl) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(apiRequest)
            }.body()

            parseResponse(responseBody, request.correlationId)
        } catch (e: CancellationException) {
            platformDependencies.log(LogLevel.INFO, id, "OpenAI request with correlationId '${request.correlationId}' was cancelled.")
            throw e
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.ERROR, id, "Content generation failed: ${e.stackTraceToString()}")
            val userMessage = mapExceptionToUserMessage(e)
            GatewayResponse(null, userMessage, request.correlationId)
        }
    }
}