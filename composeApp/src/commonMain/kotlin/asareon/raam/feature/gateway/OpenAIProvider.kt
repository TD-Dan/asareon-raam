package asareon.raam.feature.gateway.openai

import asareon.raam.core.Action
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.feature.gateway.* // Allowed: this is inter-feature import
import asareon.raam.util.LogLevel
import asareon.raam.util.PlatformDependencies
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.coroutines.cancellation.CancellationException

// --- Data Contracts specific to the OpenAI API ---
@Serializable
private data class OpenAIChatResponse(
    val choices: List<Choice>? = null,
    val usage: OpenAIUsage? = null,
    val error: ApiError? = null
)
@Serializable
private data class Choice(val message: OpenAIMessage)
@Serializable
private data class OpenAIMessage(val role: String, val content: String?, val name: String? = null)
@Serializable
private data class OpenAIUsage(
    @kotlinx.serialization.SerialName("prompt_tokens")
    val promptTokens: Int? = null,
    @kotlinx.serialization.SerialName("completion_tokens")
    val completionTokens: Int? = null,
    @kotlinx.serialization.SerialName("total_tokens")
    val totalTokens: Int? = null
)
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
    private val apiHost = "api.openai.com"

    private val json = Json { ignoreUnknownKeys = true }
    private val prettyJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val client = HttpClient {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 240_000 }
        expectSuccess = false
    }

    // --- Logic extracted for testability ---

    companion object {
        /**
         * Minimal trigger message injected when `contents` is empty (system-prompt-only mode).
         * All conversation context is in the system prompt; this satisfies the API's
         * requirement for ≥1 non-system message.
         */
        internal const val SYSTEM_PROMPT_TRIGGER = "[Turn initiated. Respond based on your system prompt.]"
    }

    private fun sanitizeOpenAIName(senderName: String, senderId: String): String {
        val raw = "${senderName}_$senderId"
        return raw.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)
    }

    internal fun buildRequestPayload(request: GatewayRequest): JsonElement {
        val openAiMessages = buildJsonArray {
            request.systemPrompt?.let {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", it)
                })
            }
            if (request.contents.isEmpty()) {
                // System-prompt-only mode: conversation is in the system prompt.
                // OpenAI requires ≥1 non-system message.
                add(buildJsonObject {
                    put("role", "user")
                    put("content", SYSTEM_PROMPT_TRIGGER)
                })
            } else {
                // Legacy path (deprecated): contents carries conversation messages.
                request.contents.forEach { message ->
                    add(buildJsonObject {
                        put("role", if (message.role == "model") "assistant" else message.role)
                        put("content", message.content)
                        put("name", sanitizeOpenAIName(message.senderName, message.senderId))
                    })
                }
            }
        }
        return buildJsonObject {
            put("model", request.modelName)
            put("messages", openAiMessages)
            request.maxOutputTokens?.let { put("max_tokens", it) }
        }
    }

    internal fun parseResponse(responseBody: String, correlationId: String): GatewayResponse {
        val response = json.decodeFromString<OpenAIChatResponse>(responseBody)

        response.error?.let {
            return GatewayResponse(null, "API Error: ${it.message}", correlationId)
        }

        val inputTokens = response.usage?.promptTokens
        val outputTokens = response.usage?.completionTokens

        val rawText = response.choices?.firstOrNull()?.message?.content
        if (rawText != null) {
            if (inputTokens == null && outputTokens == null) {
                platformDependencies.log(
                    LogLevel.WARN, id,
                    "Successful response for correlationId '$correlationId' has no token usage data. " +
                            "This may indicate an API change or deserialization issue."
                )
            }
            return GatewayResponse(rawText, null, correlationId, inputTokens, outputTokens)
        }

        platformDependencies.log(
            LogLevel.ERROR, id,
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
        dispatch(Action(ActionRegistry.Names.SETTINGS_ADD, payload))
    }

    override suspend fun listAvailableModels(settings: Map<String, String>): List<ModelDescriptor> {
        val apiKey = settings[apiKeySettingKey].orEmpty()
        if (apiKey.isBlank()) return emptyList()

        return try {
            val response: ListModelsResponse = client.get("https://$apiHost/v1/models") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }.body()
            response.data
                .map { it.id }
                .filter { it.startsWith("gpt-") }
                .sorted()
                // OpenAI's list-models API does not expose per-model output token limits.
                // maxOutputTokens is left null — the user-configured default will be used,
                // and the API will return a clear error if it exceeds the model's ceiling.
                .map { ModelDescriptor(it) }
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.WARN, id, "Failed to fetch OpenAI models: ${e.message}")
            emptyList()
        }
    }

    override suspend fun generatePreview(request: GatewayRequest, settings: Map<String, String>): String {
        val payload = buildRequestPayload(request)
        return prettyJson.encodeToString(payload)
    }

    override suspend fun generateContent(request: GatewayRequest, settings: Map<String, String>): GatewayResponse {
        val apiKey = settings[apiKeySettingKey].orEmpty()
        if (apiKey.isBlank()) {
            return GatewayResponse(null, "OpenAI API Key is not configured.", request.correlationId)
        }

        return try {
            val apiRequest = buildRequestPayload(request)
            val apiUrl = "https://$apiHost/v1/chat/completions"
            val currentTimeMs = platformDependencies.currentTimeMillis()

            val httpResponse: HttpResponse = client.post(apiUrl) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(apiRequest)
            }
            val responseBody: String = httpResponse.body()
            val rateLimitInfo = httpResponse.extractRateLimitInfo(currentTimeMs)

            if (rateLimitInfo != null) {
                platformDependencies.log(
                    LogLevel.DEBUG, id,
                    "Rate limit for ${request.correlationId}: " +
                            "requests=${rateLimitInfo.requestsRemaining}/${rateLimitInfo.requestLimit}, " +
                            "tokens=${rateLimitInfo.tokensRemaining}/${rateLimitInfo.tokenLimit}" +
                            (rateLimitInfo.retryAfterMs?.let { ", retryAfter=$it" } ?: "")
                )
            }

            if (isRateLimited(httpResponse.status.value)) {
                return buildRateLimitedResponse(request.correlationId, rateLimitInfo, "OpenAI", currentTimeMs)
            }

            parseResponse(responseBody, request.correlationId).copy(rateLimitInfo = rateLimitInfo)

        } catch (e: ResponseException) {
            val currentTimeMs = platformDependencies.currentTimeMillis()
            val rateLimitInfo = e.extractRateLimitInfo(currentTimeMs)
            if (isRateLimited(e.response.status.value)) {
                platformDependencies.log(LogLevel.WARN, id,
                    "Rate limited (via exception) for correlationId '${request.correlationId}'.")
                return buildRateLimitedResponse(request.correlationId, rateLimitInfo, "OpenAI", currentTimeMs)
            }
            platformDependencies.log(LogLevel.ERROR, id, "HTTP error: ${e.response.status}. ${e.message}")
            val userMessage = mapExceptionToUserMessage(e)
            GatewayResponse(null, userMessage, request.correlationId, rateLimitInfo = rateLimitInfo)
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