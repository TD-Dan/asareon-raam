package app.auf.feature.gateway.gemini

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

// --- Data Contracts specific to the Gemini API ---
@Serializable
private data class GenerateContentResponse(
    val candidates: List<Candidate>? = null,
    val promptFeedback: PromptFeedback? = null,
    val error: ApiError? = null
)
@Serializable
private data class Candidate(
    val content: Content? = null,
    val finishReason: String? = null
)
@Serializable
private data class Content(val parts: List<Part>? = null)
@Serializable
private data class Part(val text: String)
@Serializable
private data class PromptFeedback(val blockReason: String?)
@Serializable
private data class ApiError(val message: String)
@Serializable
private data class ListModelsResponse(val models: List<ModelInfo>)
@Serializable
private data class ModelInfo(
    val name: String,
    val supportedGenerationMethods: List<String> = emptyList()
)

/**
 * A concrete implementation of the AgentGatewayProvider for the Google Gemini API.
 */
class GeminiProvider(
    private val platformDependencies: PlatformDependencies
) : AgentGatewayProvider {
    override val id: String = "gemini"
    private val apiKeySettingKey = "gateway.gemini.apiKey"
    private val API_HOST = "generativelanguage.googleapis.com"

    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 240_000 }
    }

    // --- Logic extracted for testability ---

    /** Builds the provider-specific JSON payload from a universal request. */
    internal fun buildRequestPayload(request: GatewayRequest): JsonElement {
        val apiContents = buildJsonArray {
            request.contents.forEach { message ->
                add(buildJsonObject {
                    put("role", message.role)
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", message.content) })
                    })
                })
            }
        }
        return buildJsonObject { put("contents", apiContents) }
    }

    /** Parses a raw JSON response body into a universal GatewayResponse. */
    internal fun parseResponse(responseBody: String, correlationId: String): GatewayResponse {
        val response = json.decodeFromString<GenerateContentResponse>(responseBody)

        // Path 1: Hard API Error
        response.error?.let {
            return GatewayResponse(null, "API Error: ${it.message}", correlationId)
        }

        // Path 2: Successful Content Generation
        val rawText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
        if (rawText != null) {
            return GatewayResponse(rawText, null, correlationId)
        }

        // Path 3: Safety/Content Filter Block
        response.promptFeedback?.blockReason?.let {
            return GatewayResponse(null, "Blocked by provider: $it", correlationId)
        }

        // FIX: Path 4: If the API returned a valid response structure (candidates array is present),
        // but there's no content, no error, and no block. This is a valid empty turn (e.g. finishReason: "STOP").
        if (response.candidates != null) {
            platformDependencies.log(LogLevel.INFO, id, "Received a valid response with no text content from Gemini for correlationId '$correlationId'. Treating as a completed empty turn.")
            return GatewayResponse("", null, correlationId)
        }

        // Path 5 (Fallback): Unrecognized response format
        platformDependencies.log(
            LogLevel.ERROR,
            id,
            "Unrecognised response format from Gemini API. Full response: $responseBody"
        )
        return GatewayResponse(null, "Unrecognised response format from Gemini API.", correlationId)
    }

    // --- Public Interface Implementation ---

    override fun registerSettings(dispatch: (Action) -> Unit) {
        val payload = buildJsonObject {
            put("key", apiKeySettingKey)
            put("type", "STRING")
            put("label", "Gemini API Key")
            put("description", "API Key for Google AI Gemini models.")
            put("section", "API Keys")
            put("defaultValue", "")
        }
        dispatch(Action(ActionNames.SETTINGS_ADD, payload))
    }

    override suspend fun listAvailableModels(settings: Map<String, String>): List<String> {
        val apiKey = settings[apiKeySettingKey].orEmpty()
        if (apiKey.isBlank()) return emptyList()

        return try {
            val response: ListModelsResponse = client.get("https://$API_HOST/v1beta/models") {
                parameter("key", apiKey)
            }.body()
            response.models
                .filter { "generateContent" in it.supportedGenerationMethods }
                .map { it.name.replace("models/", "") }
                .sorted()
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.WARN, id, "Failed to fetch Gemini models: ${e.message}")
            emptyList()
        }
    }

    override suspend fun generateContent(request: GatewayRequest, settings: Map<String, String>): GatewayResponse {
        val apiKey = settings[apiKeySettingKey].orEmpty()
        if (apiKey.isBlank()) {
            return GatewayResponse(null, "Gemini API Key is not configured.", request.correlationId)
        }

        return try {
            val apiRequest = buildRequestPayload(request)
            val apiUrl = "https://$API_HOST/v1beta/models/${request.modelName}:generateContent"

            val responseBody: String = client.post(apiUrl) {
                parameter("key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(apiRequest)
            }.body()

            parseResponse(responseBody, request.correlationId)
        } catch (e: CancellationException) {
            platformDependencies.log(LogLevel.INFO, id, "Gemini request with correlationId '${request.correlationId}' was cancelled.")
            throw e
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.ERROR, id, "Content generation failed: ${e.stackTraceToString()}")
            val userMessage = mapExceptionToUserMessage(e)
            GatewayResponse(null, userMessage, request.correlationId)
        }
    }
}