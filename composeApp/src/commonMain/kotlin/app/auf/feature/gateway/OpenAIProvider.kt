package app.auf.feature.gateway.openai

import app.auf.core.Action
import app.auf.feature.gateway.AgentGatewayProvider
import app.auf.feature.gateway.GatewayRequest
import app.auf.feature.gateway.GatewayResponse
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

// --- Data Contracts specific to the OpenAI API ---
@Serializable
private data class OpenAIChatResponse(
    val choices: List<Choice>? = null,
    val error: ApiError? = null
)
@Serializable
private data class Choice(val message: OpenAIMessage)
@Serializable
private data class OpenAIMessage(val role: String, val content: String?)
@Serializable
private data class ApiError(val message: String)

/**
 * A concrete implementation of the AgentGatewayProvider for the OpenAI API.
 */
class OpenAIProvider : AgentGatewayProvider {
    override val id: String = "openai"
    private val apiKeySettingKey = "gateway.openai.apiKey"

    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient {
        install(ContentNegotiation) { json(json) }
        defaultRequest {
            url {
                protocol = URLProtocol.HTTPS
                host = "api.openai.com"
            }
            header(HttpHeaders.ContentType, ContentType.Application.Json)
        }
        install(HttpTimeout) { requestTimeoutMillis = 60_000 }
    }

    override fun registerSettings(dispatch: (Action) -> Unit) {
        val payload = buildJsonObject {
            put("key", apiKeySettingKey)
            put("type", "STRING")
            put("label", "OpenAI API Key")
            put("description", "API Key for OpenAI models (e.g., GPT-4o).")
            put("section", "API Keys")
            put("defaultValue", "")
        }
        dispatch(Action("settings.ADD", payload))
    }

    override suspend fun listAvailableModels(settings: Map<String, String>): List<String> {
        val apiKey = settings[apiKeySettingKey].orEmpty()
        if (apiKey.isBlank()) return emptyList()
        // For now, we return a hardcoded list to demonstrate the pattern.
        return listOf("gpt-4o", "gpt-4-turbo", "gpt-3.5-turbo")
    }

    override suspend fun generateContent(request: GatewayRequest, settings: Map<String, String>): GatewayResponse {
        val apiKey = settings[apiKeySettingKey].orEmpty()
        if (apiKey.isBlank()) {
            return GatewayResponse(null, "OpenAI API Key is not configured.", request.correlationId)
        }

        // THE FIX: Manually construct the provider-specific request body.
        // This removes the problematic OpenAIChatRequest data class and fixes the compile error.
        val apiRequest = buildJsonObject {
            put("model", request.modelName)
            put("messages", request.contents)
        }

        return try {
            val response: OpenAIChatResponse = client.post("v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                setBody(apiRequest)
            }.body()

            response.error?.let {
                return GatewayResponse(null, "API Error: ${it.message}", request.correlationId)
            }

            val rawText = response.choices?.firstOrNull()?.message?.content
                ?: "No content received, but no error was reported."

            GatewayResponse(rawText, null, request.correlationId)
        } catch (e: Exception) {
            println("ERROR: OpenAI content generation failed: ${e.message}")
            GatewayResponse(null, "A client-side exception occurred: ${e.message}", request.correlationId)
        }
    }
}