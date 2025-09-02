package app.auf.feature.hkgagent

import app.auf.service.AufTextParser
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// --- Data Contracts specific to the Gemini API ---
@Serializable
private data class GenerateContentRequest(val contents: List<Content>)
@Serializable
private data class GenerateContentResponse(
    val candidates: List<Candidate>? = null,
    val promptFeedback: PromptFeedback? = null,
    val usageMetadata: UsageMetadata? = null,
    val error: ApiError? = null
)
@Serializable
private data class Candidate(val content: Content)
@Serializable
private data class PromptFeedback(val blockReason: String?)
@Serializable
private data class ApiError(val code: Int, val message: String, val status: String)
@Serializable
private data class ListModelsResponse(val models: List<ModelInfo>)
@Serializable
private data class ModelInfo(
    val name: String,
    val supportedGenerationMethods: List<String> = emptyList()
)


/**
 * ## Mandate
 * A concrete implementation of the AgentGateway for the Google Gemini API.
 * This class handles all raw HTTP communication and data model translation.
 */
class GatewayGemini(
    private val jsonParser: Json,
    private val apiKey: String
) : AgentGateway {

    private val client = HttpClient {
        install(ContentNegotiation) { json(jsonParser) }
        install(HttpTimeout) { requestTimeoutMillis = 300000 }
    }

    override suspend fun generateContent(request: AgentRequest): AgentResponse {
        if (apiKey.isBlank()) {
            return AgentResponse(null, "API Key is missing.", null)
        }
        val apiUrl = "https://generativelanguage.googleapis.com/v1beta/models/${request.modelName}:generateContent"
        val apiRequest = GenerateContentRequest(contents = request.contents)

        return try {
            val apiResponse: GenerateContentResponse = client.post(apiUrl) {
                parameter("key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(apiRequest)
            }.body()

            apiResponse.error?.let {
                return AgentResponse(null, "API Error: ${it.message} (Code: ${it.code})", null)
            }

            val rawText = apiResponse.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                ?: apiResponse.promptFeedback?.blockReason?.let { "Blocked: $it" }
                ?: "No content received, but no error was reported."

            AgentResponse(rawText, null, apiResponse.usageMetadata)
        } catch (e: Exception) {
            AgentResponse(null, "A client-side exception occurred: ${e.message}", null)
        }
    }

    override suspend fun listAvailableModels(): List<String> {
        if (apiKey.isBlank()) return emptyList()
        val apiUrl = "https://generativelanguage.googleapis.com/v1beta/models"
        return try {
            client.get(apiUrl) { parameter("key", apiKey) }
                .body<ListModelsResponse>().models
                .filter { "generateContent" in it.supportedGenerationMethods }
                .map { it.name.replace("models/", "") }
                .sorted()
        } catch (e: Exception) {
            println("Failed to fetch models: ${e.message}")
            emptyList()
        }
    }
}