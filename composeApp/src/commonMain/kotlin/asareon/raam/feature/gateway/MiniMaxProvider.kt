package asareon.raam.feature.gateway.minimax

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

// --- Data Contracts specific to the MiniMax API (OpenAI-compatible) ---
@Serializable
private data class MiniMaxChatResponse(
    val choices: List<Choice>? = null,
    val usage: MiniMaxUsage? = null,
    val error: JsonElement? = null
)

@Serializable
private data class Choice(
    val message: MiniMaxMessage? = null,
    val delta: MiniMaxMessage? = null
)

@Serializable
private data class MiniMaxMessage(
    val role: String? = null,
    val content: String? = null,
    /**
     * MiniMax M2.7's internal reasoning trace, populated when the model performs
     * chain-of-thought reasoning. Separate from [content] which holds the final answer.
     * May also appear inline in [content] wrapped in `<think>` tags — the parser
     * handles deduplication.
     */
    @kotlinx.serialization.SerialName("reasoning_content")
    val reasoningContent: String? = null
)

@Serializable
private data class MiniMaxUsage(
    @kotlinx.serialization.SerialName("prompt_tokens")
    val promptTokens: Int? = null,
    @kotlinx.serialization.SerialName("completion_tokens")
    val completionTokens: Int? = null,
    @kotlinx.serialization.SerialName("total_tokens")
    val totalTokens: Int? = null
)

private fun JsonElement.extractErrorMessage(): String =
    when (this) {
        is JsonPrimitive -> content
        is JsonObject    -> this["message"]?.jsonPrimitive?.content ?: this.toString()
        else             -> toString()
    }

/**
 * A concrete implementation of [UniversalGatewayProvider] for the MiniMax API.
 *
 * MiniMax models (e.g., MiniMax-M2.7, MiniMax-M2.5) are accessed via an
 * OpenAI-compatible API:
 *   - Base URL:  https://api.minimax.io/v1
 *   - Auth:      Authorization: Bearer <key>
 *   - Endpoint:  /v1/chat/completions  (OpenAI chat completion schema)
 *
 * Note: MiniMax does NOT expose a /v1/models listing endpoint. Available models
 * are maintained as a static catalogue in [KNOWN_MODELS].
 */
class MiniMaxProvider(
    private val platformDependencies: PlatformDependencies
) : UniversalGatewayProvider {

    override val id: String = "minimax"

    private val apiKeySettingKey = "gateway.minimax.apiKey"
    private val apiHost = "api.minimax.io"

    private val json = Json { ignoreUnknownKeys = true }
    private val prettyJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val client = HttpClient {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 240_000 }
        expectSuccess = false
    }

    // --- Logic extracted for testability ---

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
        val response = json.decodeFromString<MiniMaxChatResponse>(responseBody)

        response.error?.let {
            return GatewayResponse(null, "API Error: ${it.extractErrorMessage()}", correlationId)
        }

        val inputTokens = response.usage?.promptTokens
        val outputTokens = response.usage?.completionTokens

        val message = response.choices?.firstOrNull()?.message
        val rawText = message?.content
        if (rawText != null) {
            if (inputTokens == null && outputTokens == null) {
                platformDependencies.log(
                    LogLevel.WARN, id,
                    "Successful response for correlationId '$correlationId' has no token usage data. " +
                            "This may indicate an API change or deserialization issue."
                )
            }

            // MiniMax M2.7 may return reasoning in a separate `reasoning_content` field
            // instead of (or in addition to) inline <think> tags in `content`.
            // If the structured field is present and content doesn't already contain
            // a <think> block, prepend it so the BlockSeparatingParser picks it up.
            val assembledContent = if (!message.reasoningContent.isNullOrBlank()
                && !rawText.contains("<think>")) {
                "<think>\n${message.reasoningContent}\n</think>\n$rawText"
            } else {
                rawText
            }

            return GatewayResponse(assembledContent, null, correlationId, inputTokens, outputTokens)
        }

        platformDependencies.log(
            LogLevel.ERROR, id,
            "Unrecognised response format from MiniMax API. Full response: $responseBody"
        )
        return GatewayResponse(null, "Unrecognised response format from MiniMax API.", correlationId)
    }

    // --- Public Interface Implementation ---

    override fun registerSettings(dispatch: (Action) -> Unit) {
        val payload = buildJsonObject {
            put("key", apiKeySettingKey)
            put("type", "STRING")
            put("label", "MiniMax API Key")
            put("description", "API Key for MiniMax models (e.g., MiniMax-M2.7, MiniMax-M2.5).")
            put("section", "API Keys")
            put("defaultValue", "")
        }
        dispatch(Action(ActionRegistry.Names.SETTINGS_ADD, payload))
    }

    override suspend fun listAvailableModels(settings: Map<String, String>): List<ModelDescriptor> {
        val apiKey = settings[apiKeySettingKey].orEmpty()
        if (apiKey.isBlank()) {
            platformDependencies.log(LogLevel.WARN, id, "Cannot list models: MiniMax API Key is not configured.")
            return emptyList()
        }

        // MiniMax does not expose a standard /v1/models listing endpoint.
        // Return a static list of known models. maxOutputTokens is left null —
        // the user-configured default will be used, and the API will reject or
        // clamp if it exceeds the model's actual ceiling (131,072 for M2.7).
        return KNOWN_MODELS
    }

    companion object {
        /**
         * Minimal trigger message injected when `contents` is empty (system-prompt-only mode).
         * All conversation context is in the system prompt; this satisfies the API's
         * requirement for ≥1 non-system message.
         */
        internal const val SYSTEM_PROMPT_TRIGGER = "[Turn initiated. Respond based on your system prompt.]"

        /**
         * Static catalogue of known MiniMax text models.
         *
         * MiniMax does not expose a /v1/models listing endpoint, so this list is
         * maintained manually. Update when MiniMax releases new models.
         */
        private val KNOWN_MODELS = listOf(
            ModelDescriptor("MiniMax-M2.7", maxOutputTokens = 131_072),
            ModelDescriptor("MiniMax-M2.7-highspeed", maxOutputTokens = 131_072),
            ModelDescriptor("MiniMax-M2.5", maxOutputTokens = 131_072),
            ModelDescriptor("MiniMax-M2.5-highspeed", maxOutputTokens = 131_072),
            ModelDescriptor("MiniMax-M2.1", maxOutputTokens = 131_072),
            ModelDescriptor("MiniMax-M2.1-highspeed", maxOutputTokens = 131_072),
            ModelDescriptor("MiniMax-M2")
        )
    }

    override suspend fun generatePreview(request: GatewayRequest, settings: Map<String, String>): String {
        val payload = buildRequestPayload(request)
        return prettyJson.encodeToString(payload)
    }

    override suspend fun generateContent(request: GatewayRequest, settings: Map<String, String>): GatewayResponse {
        val apiKey = settings[apiKeySettingKey].orEmpty()
        if (apiKey.isBlank()) {
            return GatewayResponse(null, "MiniMax API Key is not configured.", request.correlationId)
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
                return buildRateLimitedResponse(request.correlationId, rateLimitInfo, "MiniMax", currentTimeMs)
            }

            parseResponse(responseBody, request.correlationId).copy(rateLimitInfo = rateLimitInfo)

        } catch (e: ResponseException) {
            val currentTimeMs = platformDependencies.currentTimeMillis()
            val rateLimitInfo = e.extractRateLimitInfo(currentTimeMs)
            if (isRateLimited(e.response.status.value)) {
                platformDependencies.log(LogLevel.WARN, id,
                    "Rate limited (via exception) for correlationId '${request.correlationId}'.")
                return buildRateLimitedResponse(request.correlationId, rateLimitInfo, "MiniMax", currentTimeMs)
            }
            platformDependencies.log(LogLevel.ERROR, id, "HTTP error: ${e.response.status}. ${e.message}")
            val userMessage = mapExceptionToUserMessage(e)
            GatewayResponse(null, userMessage, request.correlationId, rateLimitInfo = rateLimitInfo)
        } catch (e: CancellationException) {
            platformDependencies.log(LogLevel.INFO, id, "MiniMax request with correlationId '${request.correlationId}' was cancelled.")
            throw e
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.ERROR, id, "Content generation failed: ${e.stackTraceToString()}")
            val userMessage = mapExceptionToUserMessage(e)
            GatewayResponse(null, userMessage, request.correlationId)
        }
    }
}