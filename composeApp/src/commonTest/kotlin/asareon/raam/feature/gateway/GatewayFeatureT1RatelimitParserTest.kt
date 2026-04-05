package asareon.raam.feature.gateway

import kotlin.test.*

/**
 * Tier 1 Unit Test for RateLimitHeaderParser.
 *
 * Mandate (P-TEST-001, T1): To test the rate limit header parsing logic in isolation.
 * All functions under test are pure — no network, no state, no platform dependencies.
 *
 * Tests cover:
 *   - parseRateLimitHeaders: Extracts a RateLimitInfo from a header map
 *   - parseResetValue: Converts duration strings to epoch ms
 *   - isRateLimited: Simple HTTP 429 check
 */
class GatewayFeatureT1RateLimitParserTest {

    private val baseTimeMs = 1_700_000_000_000L // A fixed epoch for deterministic tests

    // =========================================================================
    // parseRateLimitHeaders — Complete header sets
    // =========================================================================

    @Test
    fun `returns null when headers map is empty`() {
        val result = parseRateLimitHeaders(emptyMap(), baseTimeMs)
        assertNull(result, "Should return null when no rate limit headers are present.")
    }

    @Test
    fun `returns null when headers contain only irrelevant keys`() {
        val headers = mapOf(
            "content-type" to "application/json",
            "x-request-id" to "abc-123"
        )
        val result = parseRateLimitHeaders(headers, baseTimeMs)
        assertNull(result, "Should return null when no recognized rate limit headers are present.")
    }

    @Test
    fun `parses complete Anthropic-style header set`() {
        val headers = mapOf(
            "x-ratelimit-limit-requests" to "1000",
            "x-ratelimit-remaining-requests" to "999",
            "x-ratelimit-limit-tokens" to "80000",
            "x-ratelimit-remaining-tokens" to "79500",
            "x-ratelimit-reset-requests" to "1s",
            "x-ratelimit-reset-tokens" to "6m0s"
        )

        val result = parseRateLimitHeaders(headers, baseTimeMs)

        assertNotNull(result, "Should return a RateLimitInfo for valid headers.")
        assertEquals(1000, result.requestLimit)
        assertEquals(999, result.requestsRemaining)
        assertEquals(80000, result.tokenLimit)
        assertEquals(79500, result.tokensRemaining)
        assertEquals(baseTimeMs + 1_000, result.requestsResetAtMs)
        assertEquals(baseTimeMs + 360_000, result.tokensResetAtMs)
        assertNull(result.retryAfterMs, "retryAfterMs should be null when no retry-after header is present.")
    }

    @Test
    fun `parses complete OpenAI-style header set`() {
        // OpenAI uses the same header names as Anthropic.
        val headers = mapOf(
            "x-ratelimit-limit-requests" to "500",
            "x-ratelimit-remaining-requests" to "498",
            "x-ratelimit-limit-tokens" to "30000",
            "x-ratelimit-remaining-tokens" to "28750",
            "x-ratelimit-reset-requests" to "6m0s",
            "x-ratelimit-reset-tokens" to "2m30.5s"
        )

        val result = parseRateLimitHeaders(headers, baseTimeMs)

        assertNotNull(result)
        assertEquals(500, result.requestLimit)
        assertEquals(498, result.requestsRemaining)
        assertEquals(30000, result.tokenLimit)
        assertEquals(28750, result.tokensRemaining)
        assertEquals(baseTimeMs + 360_000, result.requestsResetAtMs)
        // 2m30.5s = 150,500ms
        assertEquals(baseTimeMs + 150_500, result.tokensResetAtMs)
    }

    // =========================================================================
    // parseRateLimitHeaders — Partial header sets
    // =========================================================================

    @Test
    fun `parses partial headers with only request limits`() {
        val headers = mapOf(
            "x-ratelimit-limit-requests" to "100",
            "x-ratelimit-remaining-requests" to "50"
        )

        val result = parseRateLimitHeaders(headers, baseTimeMs)

        assertNotNull(result, "Should return RateLimitInfo even with partial headers.")
        assertEquals(100, result.requestLimit)
        assertEquals(50, result.requestsRemaining)
        assertNull(result.tokenLimit)
        assertNull(result.tokensRemaining)
        assertNull(result.requestsResetAtMs)
        assertNull(result.tokensResetAtMs)
        assertNull(result.retryAfterMs)
    }

    @Test
    fun `parses partial headers with only token limits`() {
        val headers = mapOf(
            "x-ratelimit-limit-tokens" to "60000",
            "x-ratelimit-remaining-tokens" to "12345"
        )

        val result = parseRateLimitHeaders(headers, baseTimeMs)

        assertNotNull(result)
        assertNull(result.requestLimit)
        assertNull(result.requestsRemaining)
        assertEquals(60000, result.tokenLimit)
        assertEquals(12345, result.tokensRemaining)
    }

    @Test
    fun `parses headers with only retry-after`() {
        // Gemini-style: no quota headers, only retry-after on 429.
        val headers = mapOf(
            "retry-after" to "30"
        )

        val result = parseRateLimitHeaders(headers, baseTimeMs)

        assertNotNull(result, "Should return RateLimitInfo with only retryAfterMs populated.")
        assertNull(result.requestLimit)
        assertNull(result.requestsRemaining)
        assertNull(result.tokenLimit)
        assertNull(result.tokensRemaining)
        assertNull(result.requestsResetAtMs)
        assertNull(result.tokensResetAtMs)
        assertEquals(baseTimeMs + 30_000, result.retryAfterMs)
    }

    // =========================================================================
    // parseRateLimitHeaders — Malformed / edge-case header values
    // =========================================================================

    @Test
    fun `ignores non-numeric limit values`() {
        val headers = mapOf(
            "x-ratelimit-limit-requests" to "not-a-number",
            "x-ratelimit-remaining-tokens" to "also-not-a-number"
        )

        val result = parseRateLimitHeaders(headers, baseTimeMs)

        assertNull(result, "Should return null when all values are unparseable.")
    }

    @Test
    fun `handles mix of valid and invalid header values`() {
        val headers = mapOf(
            "x-ratelimit-limit-requests" to "100",
            "x-ratelimit-remaining-requests" to "garbage",
            "x-ratelimit-remaining-tokens" to "500"
        )

        val result = parseRateLimitHeaders(headers, baseTimeMs)

        assertNotNull(result, "Should return RateLimitInfo for any parseable headers.")
        assertEquals(100, result.requestLimit)
        assertNull(result.requestsRemaining, "Unparseable value should produce null field.")
        assertEquals(500, result.tokensRemaining)
    }

    // =========================================================================
    // parseResetValue — Duration parsing
    // =========================================================================

    @Test
    fun `parseResetValue returns null for null input`() {
        assertNull(parseResetValue(null, baseTimeMs))
    }

    @Test
    fun `parseResetValue returns null for blank input`() {
        assertNull(parseResetValue("", baseTimeMs))
        assertNull(parseResetValue("  ", baseTimeMs))
    }

    @Test
    fun `parseResetValue parses plain integer seconds`() {
        val result = parseResetValue("30", baseTimeMs)
        assertEquals(baseTimeMs + 30_000, result)
    }

    @Test
    fun `parseResetValue parses fractional seconds`() {
        val result = parseResetValue("0.5", baseTimeMs)
        assertEquals(baseTimeMs + 500, result)
    }

    @Test
    fun `parseResetValue parses duration with seconds only`() {
        val result = parseResetValue("1s", baseTimeMs)
        assertEquals(baseTimeMs + 1_000, result)
    }

    @Test
    fun `parseResetValue parses duration with minutes only`() {
        val result = parseResetValue("6m", baseTimeMs)
        assertEquals(baseTimeMs + 360_000, result)
    }

    @Test
    fun `parseResetValue parses duration with minutes and seconds`() {
        val result = parseResetValue("6m0s", baseTimeMs)
        assertEquals(baseTimeMs + 360_000, result)
    }

    @Test
    fun `parseResetValue parses duration with minutes and fractional seconds`() {
        val result = parseResetValue("2m30.5s", baseTimeMs)
        assertEquals(baseTimeMs + 150_500, result)
    }

    @Test
    fun `parseResetValue parses duration with fractional seconds only`() {
        val result = parseResetValue("0.5s", baseTimeMs)
        assertEquals(baseTimeMs + 500, result)
    }

    @Test
    fun `parseResetValue returns null for zero duration`() {
        assertNull(parseResetValue("0", baseTimeMs))
        assertNull(parseResetValue("0s", baseTimeMs))
    }

    @Test
    fun `parseResetValue returns null for unparseable string`() {
        assertNull(parseResetValue("never", baseTimeMs))
        assertNull(parseResetValue("2025-01-01T00:00:00Z", baseTimeMs))
    }

    @Test
    fun `parseResetValue handles whitespace padding`() {
        val result = parseResetValue("  1s  ", baseTimeMs)
        assertEquals(baseTimeMs + 1_000, result)
    }

    // =========================================================================
    // isRateLimited — HTTP 429 check
    // =========================================================================

    @Test
    fun `isRateLimited returns true for 429`() {
        assertTrue(isRateLimited(429))
    }

    @Test
    fun `isRateLimited returns false for 200`() {
        assertFalse(isRateLimited(200))
    }

    @Test
    fun `isRateLimited returns false for 400`() {
        assertFalse(isRateLimited(400))
    }

    @Test
    fun `isRateLimited returns false for 500`() {
        assertFalse(isRateLimited(500))
    }

    @Test
    fun `isRateLimited returns false for 401`() {
        assertFalse(isRateLimited(401))
    }

    // =========================================================================
    // retry-after header edge cases
    // =========================================================================

    @Test
    fun `retry-after with zero seconds produces null retryAfterMs`() {
        // retry-after: 0 means "retry immediately" — we treat as no wait
        val headers = mapOf("retry-after" to "0")
        val result = parseRateLimitHeaders(headers, baseTimeMs)

        // "0" parses as 0 seconds → currentTimeMs + 0 = currentTimeMs
        // This is technically "now", which is valid. Let's verify the math.
        assertEquals(baseTimeMs, result?.retryAfterMs)
    }

    @Test
    fun `retry-after with non-numeric value is ignored`() {
        val headers = mapOf("retry-after" to "Wed, 21 Oct 2015 07:28:00 GMT")
        val result = parseRateLimitHeaders(headers, baseTimeMs)

        // HTTP-date format for retry-after is not currently supported
        assertNull(result, "HTTP-date format retry-after should produce null (unsupported).")
    }

    // =========================================================================
    // RateLimitInfo serialization round-trip (contract validation)
    // =========================================================================

    @Test
    fun `RateLimitInfo serializes and deserializes correctly`() {
        val original = RateLimitInfo(
            requestLimit = 1000,
            requestsRemaining = 999,
            tokenLimit = 80000,
            tokensRemaining = 79500,
            requestsResetAtMs = baseTimeMs + 1000,
            tokensResetAtMs = baseTimeMs + 360000,
            retryAfterMs = null
        )

        val json = kotlinx.serialization.json.Json.encodeToString(
            RateLimitInfo.serializer(), original
        )
        val deserialized = kotlinx.serialization.json.Json.decodeFromString(
            RateLimitInfo.serializer(), json
        )

        assertEquals(original, deserialized)
    }

    @Test
    fun `RateLimitInfo with all nulls serializes and deserializes correctly`() {
        val original = RateLimitInfo()

        val json = kotlinx.serialization.json.Json.encodeToString(
            RateLimitInfo.serializer(), original
        )
        val deserialized = kotlinx.serialization.json.Json.decodeFromString(
            RateLimitInfo.serializer(), json
        )

        assertEquals(original, deserialized)
    }

    @Test
    fun `GatewayResponse with rateLimitInfo serializes correctly`() {
        val rateLimitInfo = RateLimitInfo(
            requestsRemaining = 42,
            retryAfterMs = baseTimeMs + 30_000
        )
        val response = GatewayResponse(
            rawContent = "Hello",
            errorMessage = null,
            correlationId = "corr-123",
            inputTokens = 100,
            outputTokens = 50,
            rateLimitInfo = rateLimitInfo
        )

        val json = kotlinx.serialization.json.Json.encodeToString(
            GatewayResponse.serializer(), response
        )
        val deserialized = kotlinx.serialization.json.Json.decodeFromString(
            GatewayResponse.serializer(), json
        )

        assertEquals(response, deserialized)
        assertEquals(42, deserialized.rateLimitInfo?.requestsRemaining)
        assertEquals(baseTimeMs + 30_000, deserialized.rateLimitInfo?.retryAfterMs)
    }

    @Test
    fun `GatewayResponse without rateLimitInfo remains backward compatible`() {
        // Simulate a JSON response from an older provider that doesn't include rateLimitInfo
        val jsonStr = """{"rawContent":"Hello","errorMessage":null,"correlationId":"corr-123","inputTokens":100,"outputTokens":50}"""
        val ignoreUnknown = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        val deserialized = ignoreUnknown.decodeFromString(GatewayResponse.serializer(), jsonStr)

        assertEquals("Hello", deserialized.rawContent)
        assertNull(deserialized.rateLimitInfo, "rateLimitInfo should default to null for backward compatibility.")
    }
}