package app.auf.service

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// --- Data Contracts for the Gateway ---

@Serializable
data class GenerateContentRequest(
    val contents: List<Content>
)

@Serializable
data class GenerateContentResponse(
    val candidates: List<Candidate>? = null,
    val promptFeedback: PromptFeedback? = null,
    val usageMetadata: UsageMetadata? = null,
    val error: ApiError? = null
)

@Serializable
data class Content(val role: String, val parts: List<Part>)

@Serializable
data class Part(val text: String)

@Serializable
data class Candidate(val content: Content)

@Serializable
data class PromptFeedback(val blockReason: String?)

@Serializable
data class UsageMetadata(val promptTokenCount: Int, val candidatesTokenCount: Int, val totalTokenCount: Int)

@Serializable
data class ApiError(val code: Int, val message: String, val status: String)

@Serializable
data class ListModelsResponse(val models: List<ModelInfo>)

@Serializable
data class ModelInfo(
    val name: String,
    val displayName: String? = null,
    val version: String? = null
)


/**
 * A thin client responsible for making direct, configured calls to the Google Generative AI API.
 *
 * ---
 * ## Mandate
 * This class's sole responsibility is to handle the raw HTTP communication with the Gemini API.
 * It takes pre-formatted request objects and returns raw response objects. It uses a Ktor client
 * configured with the provided JSON parser and timeouts. It does not contain business logic.
 *
 * ---
 * ## Dependencies
 * - `io.ktor.client.HttpClient`
 * - `kotlinx.serialization.json.Json`
 *
 * @version 1.1
 * @since 2025-08-14
 */
open class Gateway(private val jsonParser: Json) {

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(jsonParser)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 300000
        }
    }

    open suspend fun generateContent(apiKey: String, model: String, contents: List<Content>): GenerateContentResponse {
        val apiUrl = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val requestBody = GenerateContentRequest(contents = contents)
        return try {
            client.post(apiUrl) {
                parameter("key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }.body()
        } catch (e: Exception) {
            println("API Call Failed: ${e.message}")
            e.printStackTrace()
            GenerateContentResponse(
                error = ApiError(
                    code = 500,
                    message = "A client-side exception occurred: ${e.message}",
                    status = "CLIENT_EXCEPTION"
                )
            )
        }
    }

    open suspend fun listModels(apiKey: String): List<ModelInfo> {
        val apiUrl = "https://generativelanguage.googleapis.com/v1beta/models"
        return try {
            client.get(apiUrl) {
                parameter("key", apiKey)
            }.body<ListModelsResponse>().models
        } catch (e: Exception) {
            println("Failed to fetch models: ${e.message}")
            emptyList()
        }
    }
}