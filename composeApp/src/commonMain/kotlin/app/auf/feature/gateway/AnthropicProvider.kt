package app.auf.feature.gateway.anthropic

import app.auf.core.Action
import app.auf.core.generated.ActionNames
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
    // FIX: Anthropic API returns "input_tokens" and "output_tokens" (snake_case).
    // Without @SerialName, these fields silently deserialize to null, causing token
    // usage to never be reported. This was the root cause of the missing token stats.
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
    }

    // --- Logic extracted for testability ---

    /** Builds the provider-specific JSON payload from a universal request. */
    internal fun buildRequestPayload(request: GatewayRequest): JsonElement {
        // Anthropic requires alternating user/assistant messages.
        // Content enrichment (sender info, timestamps) is handled upstream by the
        // AgentCognitivePipeline — providers receive pre-enriched content and pass it through.
        val anthropicMessages = buildJsonArray {
            request.contents.forEach { message ->
                add(buildJsonObject {
                    // Map roles: "model" -> "assistant", everything else -> "user"
                    put("role", if (message.role == "model") "assistant" else "user")
                    put("content", message.content)
                })
            }
        }

        return buildJsonObject {
            put("model", request.modelName)
            put("max_tokens", 8192) // Anthropic requires this field
            put("messages", anthropicMessages)

            // System prompt goes at root level for Anthropic
            request.systemPrompt?.let {
                put("system", it)
            }
        }
    }

    /** Parses a raw JSON response body into a universal GatewayResponse. */
    internal fun parseResponse(responseBody: String, correlationId: String): GatewayResponse {
        val response = json.decodeFromString<AnthropicResponse>(responseBody)

        // Path 1: Hard API Error
        response.error?.let {
            return GatewayResponse(null, "API Error: ${it.message}", correlationId)
        }

        // Extract token usage (available on both success and some error paths)
        val inputTokens = response.usage?.inputTokens
        val outputTokens = response.usage?.outputTokens

        // Path 2: Successful Content Generation
        val rawText = response.content?.firstOrNull()?.text
        if (rawText != null) {
            // LOGGING: Warn if a successful response has no token usage — may indicate
            // a deserialization issue or an API change.
            if (inputTokens == null && outputTokens == null) {
                platformDependencies.log(
                    LogLevel.WARN, id,
                    "Successful response for correlationId '$correlationId' has no token usage data. " +
                            "This may indicate an API change or deserialization issue."
                )
            }
            return GatewayResponse(rawText, null, correlationId, inputTokens, outputTokens)
        }

        // Path 3 (Future-Proofing): Unrecognized response format
        platformDependencies.log(
            LogLevel.ERROR,
            id,
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
        dispatch(Action(ActionNames.SETTINGS_ADD, payload))
    }

    override suspend fun listAvailableModels(settings: Map<String, String>): List<String> {
        val apiKey = settings[apiKeySettingKey].orEmpty()
        if (apiKey.isBlank()) {
            platformDependencies.log(
                LogLevel.WARN,
                id,
                "Cannot list models: Anthropic API Key is not configured."
            )
            // Return fallback list if no API key is configured
            return listOf(
                "<no api key available>"
            )
        }

        return try {
            val apiUrl = "https://$apiHost/v1/models"
            val responseBody: String = client.get(apiUrl) {
                header("x-api-key", apiKey)
                header("anthropic-version", apiVersion)
            }.body()

            val response = json.decodeFromString<ModelsResponse>(responseBody)

            // Check for API errors
            response.error?.let {
                platformDependencies.log(
                    LogLevel.ERROR,
                    id,
                    "Failed to fetch models from Anthropic API: ${it.message}"
                )
                throw Exception("API Error: ${it.message}")
            }

            // Extract model IDs from the response
            response.data?.map { it.id } ?: emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            platformDependencies.log(
                LogLevel.ERROR,
                id,
                "Failed to list models: ${e.stackTraceToString()}"
            )
            // Return fallback list on error
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

    /**
     * Builds a payload specifically for the /v1/messages/count_tokens endpoint.
     * This differs from the generation payload: it requires model and messages but
     * must NOT include max_tokens.
     */
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

    /**
     * Calls Anthropic's /v1/messages/count_tokens endpoint to estimate input token usage
     * before execution. This is a lightweight call that does not consume output tokens.
     */
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

            val responseBody: String = client.post(apiUrl) {
                header("x-api-key", apiKey)
                header("anthropic-version", apiVersion)
                contentType(ContentType.Application.Json)
                setBody(apiRequest)
            }.body()

            parseResponse(responseBody, request.correlationId)
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