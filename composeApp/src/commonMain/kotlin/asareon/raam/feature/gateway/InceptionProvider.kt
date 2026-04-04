package asareon.raam.feature.gateway.inception

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

// --- Data Contracts specific to the Inception API ---
@Serializable
private data class InceptionChatResponse(
    val choices: List<Choice>? = null,
    val usage: InceptionUsage? = null,
    val error: JsonElement? = null
)

@Serializable
private data class Choice(
    val message: InceptionMessage? = null,
    val delta: InceptionMessage? = null
)

@Serializable
private data class InceptionMessage(
    val role: String? = null,
    val content: String? = null
)

@Serializable
private data class InceptionUsage(
    @kotlinx.serialization.SerialName("prompt_tokens")
    val promptTokens: Int? = null,
    @kotlinx.serialization.SerialName("completion_tokens")
    val completionTokens: Int? = null,
    @kotlinx.serialization.SerialName("total_tokens")
    val totalTokens: Int? = null
)

@Serializable
private data class ListModelsResponse(
    val data: List<ModelInfo> = emptyList(),
    val error: JsonElement? = null
)

@Serializable
private data class ModelInfo(val id: String)

private fun JsonElement.extractErrorMessage(): String =
    when (this) {
        is JsonPrimitive -> content
        is JsonObject    -> this["message"]?.jsonPrimitive?.content ?: this.toString()
        else             -> toString()
    }

/**
 * A concrete implementation of [UniversalGatewayProvider] for the Inception Labs API.
 *
 * Inception's Mercury models (e.g., mercury-2, mercury-coder-small) are diffusion-based
 * large language models. The API is fully OpenAI-compatible, using:
 *   - Base URL:  https://api.inceptionlabs.ai/v1
 *   - Auth:      Authorization: Bearer <key>
 *   - Endpoint:  /v1/chat/completions  (OpenAI chat completion schema)
 *   - Models:    /v1/models            (OpenAI list-models schema)
 */
class InceptionProvider(
    private val platformDependencies: PlatformDependencies
) : UniversalGatewayProvider {

    override val id: String = "inception"

    private val apiKeySettingKey = "gateway.inception.apiKey"
    private val apiHost = "api.inceptionlabs.ai"

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

    internal fun buildRequestPayload(request: GatewayRequest): JsonElement {
        return buildJsonObject {
            put("model", request.modelName)
            request.maxOutputTokens?.let { put("max_tokens", it) }
            put("messages", buildJsonArray {
                request.systemPrompt?.let {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", it)
                    })
                }
                if (request.contents.isEmpty()) {
                    // System-prompt-only mode: conversation is in the system prompt.
                    // OpenAI-compatible API requires ≥1 non-system message.
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
                        })
                    }
                }
            })
        }
    }

    internal fun parseResponse(responseBody: String, correlationId: String): GatewayResponse {
        val response = json.decodeFromString<InceptionChatResponse>(responseBody)

        response.error?.let {
            return GatewayResponse(null, "API Error: ${it.extractErrorMessage()}", correlationId)
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
            "Unrecognised response format from Inception API. Full response: $responseBody"
        )
        return GatewayResponse(null, "Unrecognised response format from Inception API.", correlationId)
    }

    // --- Public Interface Implementation ---

    override fun registerSettings(dispatch: (Action) -> Unit) {
        val payload = buildJsonObject {
            put("key", apiKeySettingKey)
            put("type", "STRING")
            put("label", "Inception API Key")
            put("description", "API Key for Inception Labs Mercury models (e.g., mercury-2).")
            put("section", "API Keys")
            put("defaultValue", "")
        }
        dispatch(Action(ActionRegistry.Names.SETTINGS_ADD, payload))
    }

    override suspend fun listAvailableModels(settings: Map<String, String>): List<ModelDescriptor> {
        val apiKey = settings[apiKeySettingKey].orEmpty()
        if (apiKey.isBlank()) {
            platformDependencies.log(LogLevel.WARN, id, "Cannot list models: Inception API Key is not configured.")
            return emptyList()
        }

        return try {
            val responseBody: String = client.get("https://$apiHost/v1/models") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }.body()

            val response = json.decodeFromString<ListModelsResponse>(responseBody)
            response.error?.let {
                platformDependencies.log(LogLevel.ERROR, id, "Failed to fetch models from Inception API: ${it.extractErrorMessage()}")
                throw Exception("API Error: ${it.extractErrorMessage()}")
            }
            // Inception's OpenAI-compatible API does not expose per-model output token limits.
            // maxOutputTokens is left null — the user-configured default will be used.
            response.data.map { ModelDescriptor(it.id) }.sortedBy { it.id }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.ERROR, id, "Failed to list Inception models: ${e.stackTraceToString()}")
            listOf(ModelDescriptor("mercury-2"), ModelDescriptor("mercury-coder-small"))
        }
    }

    override suspend fun generatePreview(request: GatewayRequest, settings: Map<String, String>): String {
        val payload = buildRequestPayload(request)
        return prettyJson.encodeToString(payload)
    }

    override suspend fun generateContent(request: GatewayRequest, settings: Map<String, String>): GatewayResponse {
        val apiKey = settings[apiKeySettingKey].orEmpty()
        if (apiKey.isBlank()) {
            return GatewayResponse(null, "Inception API Key is not configured.", request.correlationId)
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
                return buildRateLimitedResponse(request.correlationId, rateLimitInfo, "Inception", currentTimeMs)
            }

            parseResponse(responseBody, request.correlationId).copy(rateLimitInfo = rateLimitInfo)

        } catch (e: ResponseException) {
            val currentTimeMs = platformDependencies.currentTimeMillis()
            val rateLimitInfo = e.extractRateLimitInfo(currentTimeMs)
            if (isRateLimited(e.response.status.value)) {
                platformDependencies.log(LogLevel.WARN, id,
                    "Rate limited (via exception) for correlationId '${request.correlationId}'.")
                return buildRateLimitedResponse(request.correlationId, rateLimitInfo, "Inception", currentTimeMs)
            }
            platformDependencies.log(LogLevel.ERROR, id, "HTTP error: ${e.response.status}. ${e.message}")
            val userMessage = mapExceptionToUserMessage(e)
            GatewayResponse(null, userMessage, request.correlationId, rateLimitInfo = rateLimitInfo)
        } catch (e: CancellationException) {
            platformDependencies.log(LogLevel.INFO, id, "Inception request with correlationId '${request.correlationId}' was cancelled.")
            throw e
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.ERROR, id, "Content generation failed: ${e.stackTraceToString()}")
            val userMessage = mapExceptionToUserMessage(e)
            GatewayResponse(null, userMessage, request.correlationId)
        }
    }
}