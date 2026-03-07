package app.auf.feature.gateway.anthropic

import app.auf.core.Action
import app.auf.core.generated.ActionRegistry
import app.auf.feature.gateway.* // Allowed: this is inter-feature import
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
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

// --- Data Contracts specific to the Anthropic API ---
@Serializable
private data class AnthropicResponse(
    val id: String? = null,
    val type: String? = null,
    val role: String? = null,
    val content: List<ContentBlock>? = null,
    val model: String? = null,
    // FIX: Anthropic API returns "stop_reason" (snake_case)
    @kotlinx.serialization.SerialName("stop_reason")
    val stopReason: String? = null,
    val usage: Usage? = null,
    val error: ApiError? = null
)

@Serializable
private data class ContentBlock(
    val type: String,
    val text: String? = null
)

@Serializable
private data class Usage(
    @kotlinx.serialization.SerialName("input_tokens")
    val inputTokens: Int? = null,
    @kotlinx.serialization.SerialName("output_tokens")
    val outputTokens: Int? = null
)

@Serializable
private data class ApiError(
    val type: String,
    val message: String
)

@Serializable
private data class ModelsResponse(
    val data: List<ModelInfo>? = null,
    val error: ApiError? = null
)

@Serializable
private data class ModelInfo(
    val id: String,
    val type: String,
    @kotlinx.serialization.SerialName("created_at")
    val createdAt: String? = null,
    @kotlinx.serialization.SerialName("display_name")
    val displayName: String? = null
)

// --- Token Counting API ---
@Serializable
private data class CountTokensResponse(
    @kotlinx.serialization.SerialName("input_tokens")
    val inputTokens: Int? = null,
    val error: ApiError? = null
)

/**
 * A concrete implementation of the UniversalGatewayProvider for the Anthropic (Claude) API.
 * Implements the Messages API: https://docs.anthropic.com/en/api/messages
 */
class AnthropicProvider(
    private val platformDependencies: PlatformDependencies
) : UniversalGatewayProvider {
    override val id: String = "anthropic"
    private val apiKeySettingKey = "gateway.anthropic.apiKey"
    private val apiHost = "api.anthropic.com"
    private val apiVersion = "2023-06-01"

    private val json = Json { ignoreUnknownKeys = true }
    private val prettyJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val client = HttpClient {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 240_000 }
        // Allow inspection of non-2xx responses (e.g., 429) without throwing.
        // The ResponseException catch below is a safety net for Ktor versions
        // where this flag may not fully suppress exceptions.
        expectSuccess = false
    }

    // --- Logic extracted for testability ---

    /** Builds the provider-specific JSON payload from a universal request. */
    internal fun buildRequestPayload(request: GatewayRequest): JsonElement {
        val anthropicMessages = buildJsonArray {
            request.contents.forEach { message ->
                add(buildJsonObject {
                    put("role", if (message.role == "model") "assistant" else "user")
                    put("content", message.content)
                })
            }
        }

        return buildJsonObject {
            put("model", request.modelName)
            put("max_tokens", 8192)
            put("messages", anthropicMessages)
            request.systemPrompt?.let {
                put("system", it)
            }
        }
    }

    /** Parses a raw JSON response body into a universal GatewayResponse. */
    internal fun parseResponse(responseBody: String, correlationId: String): GatewayResponse {
        val response = json.decodeFromString<AnthropicResponse>(responseBody)

        response.error?.let {
            return GatewayResponse(null, "API Error: ${it.message}", correlationId)
        }

        val inputTokens = response.usage?.inputTokens
        val outputTokens = response.usage?.outputTokens

        val rawText = response.content?.firstOrNull()?.text
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
            "Unrecognised response format from Anthropic API. Full response: $responseBody"
        )
        return GatewayResponse(null, "Unrecognised response format from Anthropic API.", correlationId)
    }

    // --- Public Interface Implementation ---

    override fun registerSettings(dispatch: (Action) -> Unit) {
        val payload = buildJsonObject {
            put("key", apiKeySettingKey)
            put("type", "STRING")
            put("label", "Anthropic API Key")
            put("description", "API Key for Anthropic Claude models.")
            put("section", "API Keys")
            put("defaultValue", "")
        }
        dispatch(Action(ActionRegistry.Names.SETTINGS_ADD, payload))
    }

    override suspend fun listAvailableModels(settings: Map<String, String>): List<String> {
        val apiKey = settings[apiKeySettingKey].orEmpty()
        if (apiKey.isBlank()) {
            platformDependencies.log(LogLevel.WARN, id, "Cannot list models: Anthropic API Key is not configured.")
            return emptyList()
        }

        return try {
            val apiUrl = "https://$apiHost/v1/models"
            val responseBody: String = client.get(apiUrl) {
                header("x-api-key", apiKey)
                header("anthropic-version", apiVersion)
            }.body()

            val response = json.decodeFromString<ModelsResponse>(responseBody)
            response.error?.let {
                platformDependencies.log(LogLevel.ERROR, id, "Failed to fetch models from Anthropic API: ${it.message}")
                throw Exception("API Error: ${it.message}")
            }
            response.data?.map { it.id } ?: emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.ERROR, id, "Failed to list models: ${e.stackTraceToString()}")
            listOf(
                "claude-3-5-sonnet-20241022",
                "claude-3-5-haiku-20241022",
                "claude-3-opus-20240229",
                "claude-3-sonnet-20240229",
                "claude-3-haiku-20240307"
            )
        }
    }

    override suspend fun generatePreview(request: GatewayRequest, settings: Map<String, String>): String {
        val payload = buildRequestPayload(request)
        return prettyJson.encodeToString(payload)
    }

    internal fun buildCountTokensPayload(request: GatewayRequest): JsonElement {
        val anthropicMessages = buildJsonArray {
            request.contents.forEach { message ->
                add(buildJsonObject {
                    put("role", if (message.role == "model") "assistant" else "user")
                    put("content", message.content)
                })
            }
        }
        return buildJsonObject {
            put("model", request.modelName)
            put("messages", anthropicMessages)
            request.systemPrompt?.let { put("system", it) }
        }
    }

    override suspend fun countTokens(request: GatewayRequest, settings: Map<String, String>): TokenCountEstimate? {
        val apiKey = settings[apiKeySettingKey].orEmpty()
        if (apiKey.isBlank()) return null

        return try {
            val payload = buildCountTokensPayload(request)
            val apiUrl = "https://$apiHost/v1/messages/count_tokens"
            val responseBody: String = client.post(apiUrl) {
                header("x-api-key", apiKey)
                header("anthropic-version", apiVersion)
                contentType(ContentType.Application.Json)
                setBody(payload)
            }.body()

            val response = json.decodeFromString<CountTokensResponse>(responseBody)
            response.error?.let {
                platformDependencies.log(LogLevel.WARN, id, "Token counting failed: ${it.message}")
                return null
            }
            response.inputTokens?.let { TokenCountEstimate(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.WARN, id, "Token counting request failed: ${e.message}")
            null
        }
    }

    override suspend fun generateContent(request: GatewayRequest, settings: Map<String, String>): GatewayResponse {
        val apiKey = settings[apiKeySettingKey].orEmpty()
        if (apiKey.isBlank()) {
            return GatewayResponse(null, "Anthropic API Key is not configured.", request.correlationId)
        }

        return try {
            val apiRequest = buildRequestPayload(request)
            val apiUrl = "https://$apiHost/v1/messages"
            val currentTimeMs = platformDependencies.currentTimeMillis()

            val httpResponse: HttpResponse = client.post(apiUrl) {
                header("x-api-key", apiKey)
                header("anthropic-version", apiVersion)
                contentType(ContentType.Application.Json)
                setBody(apiRequest)
            }
            val responseBody: String = httpResponse.body()
            val rateLimitInfo = httpResponse.extractRateLimitInfo(currentTimeMs)

            // Log rate limit data at DEBUG level for operational visibility
            if (rateLimitInfo != null) {
                platformDependencies.log(
                    LogLevel.DEBUG, id,
                    "Rate limit for ${request.correlationId}: " +
                            "requests=${rateLimitInfo.requestsRemaining}/${rateLimitInfo.requestLimit}, " +
                            "tokens=${rateLimitInfo.tokensRemaining}/${rateLimitInfo.tokenLimit}" +
                            (rateLimitInfo.retryAfterMs?.let { ", retryAfter=$it" } ?: "")
                )
            }

            // Check for HTTP 429 (rate limited)
            if (isRateLimited(httpResponse.status.value)) {
                return buildRateLimitedResponse(request.correlationId, rateLimitInfo, "Anthropic", currentTimeMs)
            }

            parseResponse(responseBody, request.correlationId).copy(rateLimitInfo = rateLimitInfo)

        } catch (e: ResponseException) {
            // SAFETY NET: Catches 4xx/5xx if expectSuccess=false doesn't fully suppress.
            // This handles Ktor versions where the HttpCallValidator still throws.
            val currentTimeMs = platformDependencies.currentTimeMillis()
            val rateLimitInfo = e.extractRateLimitInfo(currentTimeMs)
            if (isRateLimited(e.response.status.value)) {
                platformDependencies.log(LogLevel.WARN, id,
                    "Rate limited (via exception) for correlationId '${request.correlationId}'.")
                return buildRateLimitedResponse(request.correlationId, rateLimitInfo, "Anthropic", currentTimeMs)
            }
            // Non-429 HTTP error — try to parse body for API error details
            platformDependencies.log(LogLevel.ERROR, id, "HTTP error: ${e.response.status}. ${e.message}")
            val userMessage = mapExceptionToUserMessage(e)
            GatewayResponse(null, userMessage, request.correlationId, rateLimitInfo = rateLimitInfo)
        } catch (e: CancellationException) {
            platformDependencies.log(LogLevel.INFO, id, "Anthropic request with correlationId '${request.correlationId}' was cancelled.")
            throw e
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.ERROR, id, "Content generation failed: ${e.stackTraceToString()}")
            val userMessage = mapExceptionToUserMessage(e)
            GatewayResponse(null, userMessage, request.correlationId)
        }
    }
}