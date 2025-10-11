package app.auf.feature.gateway.gemini

import app.auf.core.Action
import app.auf.feature.gateway.AgentGatewayProvider
import app.auf.feature.gateway.GatewayRequest
import app.auf.feature.gateway.GatewayResponse
import app.auf.feature.gateway.mapExceptionToUserMessage
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// --- Data Contracts specific to the Gemini API ---
@Serializable
private data class GenerateContentResponse(
    val candidates: List<Candidate>? = null,
    val promptFeedback: PromptFeedback? = null,
    val error: ApiError? = null
)
@Serializable
private data class Candidate(val content: Content)
@Serializable
private data class Content(val parts: List<Part>)
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
        // REMOVED: The ambiguous defaultRequest block. We will use full URLs.
        install(HttpTimeout) { requestTimeoutMillis = 60_000 }
    }

    override fun registerSettings(dispatch: (Action) -> Unit) {
        val payload = buildJsonObject {
            put("key", apiKeySettingKey)
            put("type", "STRING")
            put("label", "Gemini API Key")
            put("description", "API Key for Google AI Gemini models.")
            put("section", "API Keys")
            put("defaultValue", "")
            put("isSensitive", true)
        }
        dispatch(Action("settings.ADD", payload))
    }

    override suspend fun listAvailableModels(settings: Map<String, String>): List<String> {
        val apiKey = settings[apiKeySettingKey].orEmpty()
        if (apiKey.isBlank()) return emptyList()

        return try {
            // CORRECTED: Use a full, explicit URL.
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

        val apiRequest = buildJsonObject {
            put("contents", request.contents)
        }

        return try {
            // CORRECTED: Use a full, explicit URL.
            val apiUrl = "https://$API_HOST/v1beta/models/${request.modelName}:generateContent"
            val response: GenerateContentResponse = client.post(apiUrl) {
                parameter("key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(apiRequest)
            }.body()

            response.error?.let {
                return GatewayResponse(null, "API Error: ${it.message}", request.correlationId)
            }

            val rawText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                ?: response.promptFeedback?.blockReason?.let { "Blocked: $it" }
                ?: "No content received, but no error was reported."

            GatewayResponse(rawText, null, request.correlationId)
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.ERROR, id, "Content generation failed: ${e.message}")
            val userMessage = mapExceptionToUserMessage(e)
            GatewayResponse(null, userMessage, request.correlationId)
        }
    }
}