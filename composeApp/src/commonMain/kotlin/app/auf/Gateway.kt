package app.auf

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

class Gateway {

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = true
                isLenient = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 300000
        }
    }

    // --- MODIFIED: Return the full response object, not just the text string ---
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
            // Return a response object containing the error
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