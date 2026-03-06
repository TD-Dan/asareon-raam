package app.auf.feature.gateway

import app.auf.core.Action
import kotlinx.serialization.Serializable


// --- Internal Data Contracts for Gateway Feature ---
// These are defined here, privately, to deserialize the JSON payload received from clients.
@Serializable
data class GatewayMessage(
    val role: String,
    val content: String,
    val senderId: String,
    val senderName: String,
    val timestamp: Long
)
@Serializable
data class GatewayRequest(
    val modelName: String,
    val contents: List<GatewayMessage>,
    val correlationId: String,
    val systemPrompt: String? = null
)

/**
 * A generic, provider-agnostic response from a content generation call.
 * This is the data model delivered back to the caller via the private channel.
 */
@Serializable
data class GatewayResponse(
    val rawContent: String?,
    val errorMessage: String?,
    val correlationId: String,
    /** The number of tokens consumed by the input/prompt, as reported by the provider. */
    val inputTokens: Int? = null,
    /** The number of tokens consumed by the generated output, as reported by the provider. */
    val outputTokens: Int? = null,
    /** Rate limit snapshot extracted from the provider's HTTP response headers. Null if unavailable. */
    val rateLimitInfo: RateLimitInfo? = null
)

/**
 * A provider-agnostic token count estimate, returned by providers that support
 * pre-flight token counting (e.g., Anthropic's /v1/messages/count_tokens).
 */
@Serializable
data class TokenCountEstimate(
    val inputTokens: Int
)

/**
 * A provider-agnostic snapshot of API rate limit state, extracted from HTTP response headers.
 *
 * Providers populate this from the standard rate limit headers returned by most LLM APIs:
 *   - Anthropic: x-ratelimit-limit-requests, x-ratelimit-remaining-requests, etc.
 *   - OpenAI:    x-ratelimit-limit-requests, x-ratelimit-remaining-requests, etc.
 *   - Inception: Same as OpenAI (OpenAI-compatible API).
 *   - Gemini:    429 + Retry-After only (no per-response quota headers).
 *
 * Null fields indicate the provider or response did not include that header.
 * This data is advisory — the authoritative signal is the retryAfterMs field,
 * which is set when the provider returns HTTP 429.
 */
@Serializable
data class RateLimitInfo(
    /** Maximum requests allowed in the current rate limit window. */
    val requestLimit: Int? = null,
    /** Remaining requests in the current rate limit window. */
    val requestsRemaining: Int? = null,
    /** Maximum tokens allowed in the current rate limit window. */
    val tokenLimit: Int? = null,
    /** Remaining tokens in the current rate limit window. */
    val tokensRemaining: Int? = null,
    /**
     * Epoch milliseconds when the request rate limit window resets.
     * Converted from the provider's reset header (ISO duration or seconds).
     */
    val requestsResetAtMs: Long? = null,
    /**
     * Epoch milliseconds when the token rate limit window resets.
     */
    val tokensResetAtMs: Long? = null,
    /**
     * Epoch milliseconds until which the caller MUST NOT send another request.
     * Non-null only when the provider returned HTTP 429 (Too Many Requests).
     * The caller should delay retries until at least this timestamp.
     */
    val retryAfterMs: Long? = null
)

/**
 * The universal contract for any class that provides access to a generative AI service.
 * Each implementation is responsible for its own API-specific logic and data models.
 */
interface UniversalGatewayProvider {
    /** A unique, machine-readable ID for this provider (e.g., "gemini", "openai"). */
    val id: String

    /**
     * Registers the settings this provider needs (e.g., API key) with the SettingsFeature.
     * This is called once at application startup by the GatewayFeature.
     * @param dispatch A function to dispatch an action to the store.
     */
    fun registerSettings(dispatch: (Action) -> Unit)

    /**
     * The core generation method. It receives a generic request and the current settings state,
     * performs the network call, and returns a generic response.
     * @param request The generic request data.
     * @param settings A map of all current application settings. The provider should look up its own keys.
     * @return A generic response object.
     */
    suspend fun generateContent(request: GatewayRequest, settings: Map<String, String>): GatewayResponse

    /**
     * Generates a preview of the provider-specific payload that WOULD be sent to the API.
     * This allows users to inspect the exact data structure before execution.
     *
     * @param request The generic request data.
     * @param settings A map of all current application settings.
     * @return A formatted string representation of the request payload (e.g., pretty-printed JSON).
     */
    suspend fun generatePreview(request: GatewayRequest, settings: Map<String, String>): String

    /**
     * Fetches the list of available model names from the provider's API.
     * @param settings A map of all current application settings.
     * @return A list of model name strings, or an empty list on failure.
     */
    suspend fun listAvailableModels(settings: Map<String, String>): List<String>

    /**
     * Estimates the token count for a request payload WITHOUT executing it.
     * Only providers with a dedicated token-counting endpoint need to override this.
     *
     * @param request The generic request data.
     * @param settings A map of all current application settings.
     * @return A [TokenCountEstimate] if supported, or null if this provider does not support pre-flight counting.
     */
    suspend fun countTokens(request: GatewayRequest, settings: Map<String, String>): TokenCountEstimate? = null
}