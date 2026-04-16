package asareon.raam.feature.gateway.ollama

import asareon.raam.core.Action
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.feature.gateway.* // Allowed: inter-feature import
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

// --- Data contracts specific to Ollama's OpenAI-compatible endpoint ---
// Shape mirrors OpenAI's /v1/chat/completions exactly — Ollama translates internally.
@Serializable
private data class OllamaChatResponse(
    val choices: List<Choice>? = null,
    val usage: OllamaUsage? = null,
    val error: ApiError? = null
)
@Serializable
private data class Choice(val message: OllamaMessage)
@Serializable
private data class OllamaMessage(val role: String, val content: String?, val name: String? = null)
@Serializable
private data class OllamaUsage(
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
 * A concrete implementation of [UniversalGatewayProvider] for Ollama — a locally-hosted
 * LLM runtime accessed via its OpenAI-compatible HTTP endpoint.
 *
 * ## Architecture Note
 *
 * Structurally near-identical to [asareon.raam.feature.gateway.openai.OpenAIProvider].
 * Kept as a separate class rather than a shared base class because the abstraction
 * boundary defined by [UniversalGatewayProvider] is sufficient and the duplication
 * is honest. If a third OpenAI-compatible provider is added later, extracting a
 * shared base becomes worthwhile.
 *
 * ## Key Differences from [asareon.raam.feature.gateway.openai.OpenAIProvider]
 *
 * 1. **No API key.** Ollama does not authenticate. The provider registers a
 *    configurable `baseUrl` setting instead, defaulting to `http://localhost:11434`.
 * 2. **No model filtering.** Users pull models explicitly via `ollama pull`; every
 *    returned model is one they want available. No `startsWith("gpt-")` filter.
 * 3. **No rate limiting.** Ollama returns no rate-limit headers. [RateLimitInfo]
 *    extraction runs but always produces null. HTTP 429 is structurally impossible.
 * 4. **No reasoning-model parameter split.** The `max_completion_tokens` vs
 *    `max_tokens` distinction is OpenAI-internal. Ollama accepts `max_tokens`
 *    uniformly across all models.
 */
class OllamaProvider(
    private val platformDependencies: PlatformDependencies
) : UniversalGatewayProvider {
    override val id: String = "ollama"
    private val baseUrlSettingKey = "gateway.ollama.baseUrl"
    private val defaultBaseUrl = "http://localhost:11434"

    private val json = Json { ignoreUnknownKeys = true }
    private val prettyJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val client = HttpClient {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 360_000 }
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

    /**
     * Resolves the configured base URL from settings, falling back to the default.
     * Trailing slashes are stripped so URL construction is straightforward.
     */
    internal fun resolveBaseUrl(settings: Map<String, String>): String {
        val raw = settings[baseUrlSettingKey].orEmpty().ifBlank { defaultBaseUrl }
        return raw.trimEnd('/')
    }

    private fun sanitizeOpenAIName(senderName: String, senderId: String): String {
        val raw = "${senderName}_$senderId"
        return raw.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)
    }

    internal fun buildRequestPayload(request: GatewayRequest): JsonElement {
        val messages = buildJsonArray {
            request.systemPrompt?.let {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", it)
                })
            }
            if (request.contents.isEmpty()) {
                // System-prompt-only mode: conversation is in the system prompt.
                // The OpenAI-compatible layer requires ≥1 non-system message.
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
            put("messages", messages)
            // Ollama's /v1/chat/completions accepts `max_tokens` uniformly.
            // Note: Ollama's native API uses `num_predict` inside `options` — the
            // compat layer translates internally. We stay on the OpenAI shape.
            request.maxOutputTokens?.let { put("max_tokens", it) }
        }
    }

    internal fun parseResponse(responseBody: String, correlationId: String): GatewayResponse {
        val response = json.decodeFromString<OllamaChatResponse>(responseBody)

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
                            "Ollama should always report usage — this may indicate an older Ollama version."
                )
            }
            return GatewayResponse(rawText, null, correlationId, inputTokens, outputTokens)
        }

        platformDependencies.log(
            LogLevel.ERROR, id,
            "Unrecognised response format from Ollama API. Full response: $responseBody"
        )
        return GatewayResponse(null, "Unrecognised response format from Ollama API.", correlationId)
    }

    // --- Public Interface Implementation ---

    override fun registerSettings(dispatch: (Action) -> Unit) {
        val payload = buildJsonObject {
            put("key", baseUrlSettingKey)
            put("type", "STRING")
            put("label", "Ollama Base URL")
            put("description", "Base URL of the Ollama server (default: $defaultBaseUrl). Ollama runs locally by default; no API key is required.")
            put("section", "Local Models")
            put("defaultValue", defaultBaseUrl)
        }
        dispatch(Action(ActionRegistry.Names.SETTINGS_ADD, payload))
    }

    override suspend fun listAvailableModels(settings: Map<String, String>): List<ModelDescriptor> {
        val baseUrl = resolveBaseUrl(settings)

        return try {
            val response: ListModelsResponse = client.get("$baseUrl/v1/models").body()
            response.data
                .map { it.id }
                .sorted()
                // Ollama's /v1/models endpoint does not expose per-model output token
                // limits. Context size is set per-model at pull/create time via
                // Modelfile PARAMETER num_ctx and is not surfaced here.
                // maxOutputTokens is left null — the user-configured default is used.
                .map { ModelDescriptor(it) }
        } catch (e: Exception) {
            // The common failure mode is "Ollama not running" → connection refused.
            // Empty list is the correct answer: the user has no models available
            // from this provider right now. Log at WARN so it's visible without
            // being alarming (the user may simply not have Ollama installed).
            platformDependencies.log(LogLevel.WARN, id, "Failed to fetch Ollama models from $baseUrl: ${e.message}")
            emptyList()
        }
    }

    override suspend fun generatePreview(request: GatewayRequest, settings: Map<String, String>): String {
        val payload = buildRequestPayload(request)
        return prettyJson.encodeToString(payload)
    }

    override suspend fun generateContent(request: GatewayRequest, settings: Map<String, String>): GatewayResponse {
        val baseUrl = resolveBaseUrl(settings)

        return try {
            val apiRequest = buildRequestPayload(request)
            val apiUrl = "$baseUrl/v1/chat/completions"
            val currentTimeMs = platformDependencies.currentTimeMillis()

            val httpResponse: HttpResponse = client.post(apiUrl) {
                contentType(ContentType.Application.Json)
                setBody(apiRequest)
            }
            val responseBody: String = httpResponse.body()

            // Ollama doesn't report rate limits — this will always return null.
            // Kept for contract parity with cloud providers; costs nothing.
            val rateLimitInfo = httpResponse.extractRateLimitInfo(currentTimeMs)

            // HTTP 429 is structurally impossible from a healthy Ollama server,
            // but we handle it defensively in case a reverse proxy / API gateway
            // is sitting in front of it.
            if (isRateLimited(httpResponse.status.value)) {
                return buildRateLimitedResponse(request.correlationId, rateLimitInfo, "Ollama", currentTimeMs)
            }

            parseResponse(responseBody, request.correlationId).copy(rateLimitInfo = rateLimitInfo)

        } catch (e: ResponseException) {
            val currentTimeMs = platformDependencies.currentTimeMillis()
            val rateLimitInfo = e.extractRateLimitInfo(currentTimeMs)
            if (isRateLimited(e.response.status.value)) {
                platformDependencies.log(LogLevel.WARN, id,
                    "Rate limited (via exception) for correlationId '${request.correlationId}'.")
                return buildRateLimitedResponse(request.correlationId, rateLimitInfo, "Ollama", currentTimeMs)
            }
            platformDependencies.log(LogLevel.ERROR, id, "HTTP error: ${e.response.status}. ${e.message}")
            val userMessage = mapExceptionToUserMessage(e)
            GatewayResponse(null, userMessage, request.correlationId, rateLimitInfo = rateLimitInfo)
        } catch (e: CancellationException) {
            platformDependencies.log(LogLevel.INFO, id, "Ollama request with correlationId '${request.correlationId}' was cancelled.")
            throw e
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.ERROR, id, "Content generation failed: ${e.stackTraceToString()}")
            val userMessage = mapExceptionToUserMessage(e)
            GatewayResponse(null, userMessage, request.correlationId)
        }
    }
}