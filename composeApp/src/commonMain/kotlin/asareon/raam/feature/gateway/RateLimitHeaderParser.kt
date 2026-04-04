package asareon.raam.feature.gateway

import io.ktor.client.plugins.*
import io.ktor.client.statement.*
import io.ktor.http.*

/**
 * Shared utility for extracting rate limit information from HTTP response headers.
 *
 * Most LLM API providers (Anthropic, OpenAI, and OpenAI-compatible services like Inception)
 * return a standard set of rate limit headers on every response:
 *
 *   x-ratelimit-limit-requests      — max requests per window
 *   x-ratelimit-remaining-requests  — requests left in this window
 *   x-ratelimit-limit-tokens        — max tokens per window
 *   x-ratelimit-remaining-tokens    — tokens left in this window
 *   x-ratelimit-reset-requests      — when the request window resets (duration or seconds)
 *   x-ratelimit-reset-tokens        — when the token window resets (duration or seconds)
 *   retry-after                     — seconds to wait before retrying (on HTTP 429)
 *
 * Gemini does NOT return per-response quota headers; it only sends 429 + Retry-After.
 * The parser handles this gracefully — missing headers produce null fields.
 *
 * ## Testability
 *
 * [parseRateLimitHeaders] accepts a plain Map<String, String> for easy unit testing.
 * [HttpResponse.extractRateLimitInfo] is a convenience extension that extracts the
 * relevant headers from a Ktor response and delegates to the pure function.
 */

// --- Constants ---

/**
 * Default retry-after delay (60 seconds) used when a provider returns HTTP 429
 * without a Retry-After header. This is a conservative default — most provider
 * rate limit windows are 60 seconds or less.
 */
const val DEFAULT_RETRY_AFTER_MS = 60_000L

// --- Header names (constants to avoid typos) ---
private const val H_LIMIT_REQUESTS = "x-ratelimit-limit-requests"
private const val H_REMAINING_REQUESTS = "x-ratelimit-remaining-requests"
private const val H_LIMIT_TOKENS = "x-ratelimit-limit-tokens"
private const val H_REMAINING_TOKENS = "x-ratelimit-remaining-tokens"
private const val H_RESET_REQUESTS = "x-ratelimit-reset-requests"
private const val H_RESET_TOKENS = "x-ratelimit-reset-tokens"
private const val H_RETRY_AFTER = "retry-after"

/** All header names we inspect — used by the HttpResponse extension. */
private val RATE_LIMIT_HEADER_NAMES = listOf(
    H_LIMIT_REQUESTS, H_REMAINING_REQUESTS,
    H_LIMIT_TOKENS, H_REMAINING_TOKENS,
    H_RESET_REQUESTS, H_RESET_TOKENS,
    H_RETRY_AFTER
)

/**
 * Parses rate limit information from a map of HTTP response headers.
 *
 * This is the testable core function. It is a pure function with no Ktor dependency.
 *
 * @param headers A map of lowercase header names to their string values.
 * @param currentTimeMs The current epoch milliseconds, used to convert relative
 *                      durations (e.g., "6m0s", "30") into absolute timestamps.
 * @return A populated [RateLimitInfo] if at least one rate limit header was present,
 *         or null if no rate limit information was found.
 */
internal fun parseRateLimitHeaders(
    headers: Map<String, String>,
    currentTimeMs: Long
): RateLimitInfo? {
    val requestLimit = headers[H_LIMIT_REQUESTS]?.toIntOrNull()
    val requestsRemaining = headers[H_REMAINING_REQUESTS]?.toIntOrNull()
    val tokenLimit = headers[H_LIMIT_TOKENS]?.toIntOrNull()
    val tokensRemaining = headers[H_REMAINING_TOKENS]?.toIntOrNull()
    val requestsResetAtMs = parseResetValue(headers[H_RESET_REQUESTS], currentTimeMs)
    val tokensResetAtMs = parseResetValue(headers[H_RESET_TOKENS], currentTimeMs)

    // retry-after is typically an integer number of seconds
    val retryAfterMs = headers[H_RETRY_AFTER]?.let { value ->
        value.toLongOrNull()?.let { seconds -> currentTimeMs + (seconds * 1000) }
    }

    // Only return a non-null result if at least one header was present
    val hasAny = listOf(
        requestLimit, requestsRemaining, tokenLimit, tokensRemaining,
        requestsResetAtMs, tokensResetAtMs, retryAfterMs
    ).any { it != null }

    return if (hasAny) RateLimitInfo(
        requestLimit = requestLimit,
        requestsRemaining = requestsRemaining,
        tokenLimit = tokenLimit,
        tokensRemaining = tokensRemaining,
        requestsResetAtMs = requestsResetAtMs,
        tokensResetAtMs = tokensResetAtMs,
        retryAfterMs = retryAfterMs
    ) else null
}

/**
 * Parses a rate limit reset header value into an absolute epoch millisecond timestamp.
 *
 * API providers use varying formats for reset headers:
 *   - Plain seconds:    "30"       → currentTime + 30_000ms
 *   - Simple duration:  "6m0s"     → currentTime + 360_000ms
 *   - Sub-second:       "2m30.5s"  → currentTime + 150_500ms
 *   - Seconds only:     "1s"       → currentTime + 1_000ms
 *   - Minutes only:     "6m"       → currentTime + 360_000ms
 *   - Fractional:       "0.5s"     → currentTime + 500ms
 *
 * @param value The raw header string, or null if the header was absent.
 * @param currentTimeMs The current epoch milliseconds.
 * @return The absolute epoch ms when the window resets, or null if unparseable.
 */
internal fun parseResetValue(value: String?, currentTimeMs: Long): Long? {
    if (value.isNullOrBlank()) return null

    val trimmed = value.trim()

    // Try as raw seconds first (e.g., "30", "0.5")
    trimmed.toDoubleOrNull()?.let { seconds ->
        val ms = (seconds * 1000).toLong()
        return if (ms > 0) currentTimeMs + ms else null
    }

    // Try simple duration format: optional minutes + optional seconds
    // Matches: "6m0s", "1s", "2m30.5s", "6m", "0.5s"
    val durationRegex = Regex("""^(?:(\d+)m)?(?:([\d.]+)s)?$""")
    val match = durationRegex.matchEntire(trimmed) ?: return null
    val minutes = match.groupValues[1].toLongOrNull() ?: 0L
    val seconds = match.groupValues[2].toDoubleOrNull() ?: 0.0
    val totalMs = (minutes * 60_000L) + (seconds * 1000).toLong()
    return if (totalMs > 0) currentTimeMs + totalMs else null
}

/**
 * Returns true if the HTTP response status indicates rate limiting (HTTP 429).
 *
 * @param statusCode The HTTP status code from the response.
 */
internal fun isRateLimited(statusCode: Int): Boolean =
    statusCode == 429

/**
 * Builds a [GatewayResponse] for a rate-limited (HTTP 429) response.
 *
 * Guarantees that [RateLimitInfo.retryAfterMs] is always populated — either from
 * the provider's Retry-After header or from [DEFAULT_RETRY_AFTER_MS]. This ensures
 * the agent layer always has a concrete retry timestamp to work with.
 *
 * @param correlationId The correlation ID from the original request.
 * @param rateLimitInfo The rate limit info extracted from headers, or null if no headers were present.
 * @param providerName A human-readable provider name for the error message (e.g., "Anthropic").
 * @param currentTimeMs The current epoch milliseconds, used for the default retry-after.
 */
internal fun buildRateLimitedResponse(
    correlationId: String,
    rateLimitInfo: RateLimitInfo?,
    providerName: String,
    currentTimeMs: Long
): GatewayResponse {
    // Ensure retryAfterMs is always set — use the header value if available,
    // otherwise default to 60 seconds from now.
    val effectiveInfo = (rateLimitInfo ?: RateLimitInfo()).let { info ->
        if (info.retryAfterMs == null) {
            info.copy(retryAfterMs = currentTimeMs + DEFAULT_RETRY_AFTER_MS)
        } else {
            info
        }
    }
    return GatewayResponse(
        rawContent = null,
        errorMessage = "Rate limited by $providerName API. Retrying automatically after cooldown.",
        correlationId = correlationId,
        rateLimitInfo = effectiveInfo
    )
}

// --- Ktor Extensions ---

/**
 * Convenience extension that extracts rate limit headers from a Ktor [HttpResponse]
 * and delegates to [parseRateLimitHeaders].
 *
 * Usage in providers:
 * ```
 * val httpResponse = client.post(apiUrl) { ... }
 * val rateLimitInfo = httpResponse.extractRateLimitInfo(platformDependencies.currentTimeMillis())
 * ```
 *
 * @param currentTimeMs The current epoch milliseconds.
 * @return A [RateLimitInfo] snapshot, or null if no rate limit headers were present.
 */
internal fun HttpResponse.extractRateLimitInfo(currentTimeMs: Long): RateLimitInfo? {
    val headerMap = RATE_LIMIT_HEADER_NAMES.mapNotNull { name ->
        headers[name]?.let { name to it }
    }.toMap()
    return parseRateLimitHeaders(headerMap, currentTimeMs)
}

/**
 * Attempts to extract rate limit info from a Ktor [ResponseException].
 *
 * When `expectSuccess = true` (Ktor's default), 4xx/5xx responses throw
 * [ResponseException] before the caller can inspect headers. This extension
 * allows providers to extract rate limit info from the exception's wrapped response.
 *
 * @param currentTimeMs The current epoch milliseconds.
 * @return A [RateLimitInfo] snapshot, or null if no rate limit headers were present.
 */
internal fun ResponseException.extractRateLimitInfo(currentTimeMs: Long): RateLimitInfo? {
    return response.extractRateLimitInfo(currentTimeMs)
}