package app.auf

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.HttpTimeout // Added
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
        // NEW: Install HttpTimeout plugin with a generous timeout for LLM calls
        install(HttpTimeout) {
            // Set the request timeout to 300 seconds (300,000 milliseconds)
            requestTimeoutMillis = 300000
        }
    }

    suspend fun generateContent(apiKey: String, model: String, contents: List<Content>): String {
        val apiUrl = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val requestBody = GenerateContentRequest(contents = contents)
        try {
            val response: GenerateContentResponse = client.post(apiUrl) {
                parameter("key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }.body()

            // --- MODIFIED ERROR HANDLING LOGIC ---
            // First, check if the response contains an explicit error object.
            // If it does, format and return the detailed error message.
            response.error?.let {
                return "Error: ${it.message} (Code: ${it.code}, Status: ${it.status})"
            }

            // If there's no error object, parse the success response as before.
            return response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                ?: response.promptFeedback?.blockReason?.let { "Blocked: $it" }
                ?: "No content received, but no error was reported." // Modified to be more descriptive
        } catch (e: Exception) {
            println("API Call Failed: ${e.message}")
            e.printStackTrace()
            // This catch block now handles true network/serialization exceptions.
            return "Error: A client-side exception occurred. Check console for details."
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