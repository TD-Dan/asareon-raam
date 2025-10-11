package app.auf.feature.gateway.openai

import app.auf.core.Action
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.encodeToJsonElement

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
class OpenAIProvider(
    private val platformDependencies: PlatformDependencies
) : AgentGatewayProvider {
    override val id: String = "openai"
    private val apiKeySettingKey = "gateway.openai.apiKey"
    private val API_HOST = "api.openai.com"

    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient {
        install(ContentNegotiation) { json(json) }
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

        // CORRECTED: Transform the universal message list into the OpenAI-specific JSON structure.
        // The GatewayMessage data class serializes directly to the format OpenAI expects.
        val apiRequest = buildJsonObject {
            put("model", request.modelName)
            put("messages", json.encodeToJsonElement(request.contents))
        }

        return try {
            val apiUrl = "https://$API_HOST/v1/chat/completions"
            val response: OpenAIChatResponse = client.post(apiUrl) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(apiRequest)
            }.body()

            response.error?.let {
                return GatewayResponse(null, "API Error: ${it.message}", request.correlationId)
            }

            val rawText = response.choices?.firstOrNull()?.message?.content
                ?: "No content received, but no error was reported."

            GatewayResponse(rawText, null, request.correlationId)
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.ERROR, id, "Content generation failed: ${e.message}")
            val userMessage = mapExceptionToUserMessage(e)
            GatewayResponse(null, userMessage, request.correlationId)
        }
    }
}