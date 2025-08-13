package app.auf

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

// --- MODIFIED: The Gateway now accepts a configured Json parser. ---
class Gateway(private val jsonParser: Json) {

    private val client = HttpClient {
        install(ContentNegotiation) {
            // --- MODIFIED: It uses the provided parser instance, not a new default one. ---
            json(jsonParser)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 300000
        }
    }

    suspend fun generateContent(apiKey: String, model: String, contents: List<Content>): GenerateContentResponse {
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

    suspend fun listModels(apiKey: String): List<ModelInfo> {
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