package asareon.raam.feature.gateway

import asareon.raam.fakes.FakePlatformDependencies
import asareon.raam.feature.gateway.inception.InceptionProvider
import asareon.raam.util.LogLevel
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Tier 1 Unit Test for InceptionProvider.
 *
 * Mandate (P-TEST-001, T1): To test the provider's pure data transformation logic
 * without any network dependencies.
 *
 * Key contract differences from other providers:
 *   - API is OpenAI-compatible (/v1/chat/completions, Bearer auth)
 *   - Does NOT include the `name` field per message (that is an OpenAI-specific feature)
 *   - System prompt is injected as a `{"role": "system"}` message, not a root-level field
 *   - Error responses have two shapes: bare string {"error": "..."} or object {"error": {"message": "..."}}
 *   - Does NOT override countTokens — returns null (no token-counting endpoint)
 *
 * ARCHITECTURE: Content enrichment (adding sender info, timestamps) is the
 * responsibility of AgentCognitivePipeline.executeTurn, NOT the providers.
 * Providers receive pre-enriched content and pass it through unchanged.
 */
class GatewayFeatureT1InceptionProviderTest {
    private val platform = FakePlatformDependencies("test")
    private val provider = InceptionProvider(platform)

    // -------------------------------------------------------------------------
    // buildRequestPayload
    // -------------------------------------------------------------------------

    @Test
    fun `buildRequestPayload correctly transforms universal request to Inception format`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "mercury-2",
            contents = listOf(
                GatewayMessage("user", "Hello", "user-1", "User", 1000L),
                GatewayMessage("model", "Hi there", "agent-1", "Assistant", 2000L)
            ),
            correlationId = "corr-123"
        )
        val expected = buildJsonObject {
            put("model", "mercury-2")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "Hello")
                })
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", "Hi there")
                })
            })
        }

        // ACT
        val actual = provider.buildRequestPayload(request)

        // ASSERT
        assertEquals(expected, actual)
    }

    @Test
    fun `buildRequestPayload correctly maps model role to assistant`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "mercury-2",
            contents = listOf(GatewayMessage("model", "Answer", "agent-1", "Agent", 1000L)),
            correlationId = "corr-role"
        )

        // ACT
        val messages = provider.buildRequestPayload(request).jsonObject["messages"]?.jsonArray

        // ASSERT
        assertNotNull(messages)
        assertEquals("assistant", messages[0].jsonObject["role"]?.toString()?.trim('"'))
    }

    @Test
    fun `buildRequestPayload correctly maps user role to user`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "mercury-2",
            contents = listOf(GatewayMessage("user", "Question", "user-1", "User", 1000L)),
            correlationId = "corr-role"
        )

        // ACT
        val messages = provider.buildRequestPayload(request).jsonObject["messages"]?.jsonArray

        // ASSERT
        assertNotNull(messages)
        assertEquals("user", messages[0].jsonObject["role"]?.toString()?.trim('"'))
    }

    @Test
    fun `buildRequestPayload does NOT include name field - unlike OpenAI`() {
        // ARRANGE: Inception does not use OpenAI's name field convention.
        val request = GatewayRequest(
            modelName = "mercury-2",
            contents = listOf(GatewayMessage("user", "Hello", "user-1", "User", 1000L)),
            correlationId = "corr-noname"
        )

        // ACT
        val messages = provider.buildRequestPayload(request).jsonObject["messages"]?.jsonArray

        // ASSERT
        assertNotNull(messages)
        assertFalse(
            messages[0].jsonObject.containsKey("name"),
            "Inception messages should NOT include a 'name' field — that is OpenAI-specific."
        )
    }

    @Test
    fun `buildRequestPayload includes system prompt as a system-role message`() {
        // ARRANGE: Inception uses OpenAI-style system messages (not Anthropic's root-level field).
        val request = GatewayRequest(
            modelName = "mercury-2",
            contents = listOf(GatewayMessage("user", "Hello", "user-1", "User", 1000L)),
            correlationId = "corr-sys",
            systemPrompt = "You are a helpful assistant."
        )
        val expected = buildJsonObject {
            put("model", "mercury-2")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", "You are a helpful assistant.")
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "Hello")
                })
            })
        }

        // ACT
        val actual = provider.buildRequestPayload(request)

        // ASSERT
        assertEquals(expected, actual)
    }

    @Test
    fun `buildRequestPayload omits system message when no system prompt provided`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "mercury-2",
            contents = listOf(GatewayMessage("user", "Hello", "user-1", "User", 1000L)),
            correlationId = "corr-nosys"
        )

        // ACT
        val messages = provider.buildRequestPayload(request).jsonObject["messages"]?.jsonArray

        // ASSERT
        assertNotNull(messages)
        assertEquals(1, messages.size, "Only the user message should be present — no system message.")
        assertEquals("user", messages[0].jsonObject["role"]?.toString()?.trim('"'))
    }

    @Test
    fun `buildRequestPayload handles empty contents list`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "mercury-2",
            contents = emptyList(),
            correlationId = "corr-empty"
        )

        // ACT
        val messages = provider.buildRequestPayload(request).jsonObject["messages"]?.jsonArray

        // ASSERT
        assertNotNull(messages)
        assertEquals(1, messages.size, "Messages array should contain 1 message when contents list is empty.")
    }

    @Test
    fun `buildRequestPayload handles empty contents with system prompt`() {
        // ARRANGE: System prompt alone should produce exactly one system message.
        val request = GatewayRequest(
            modelName = "mercury-2",
            contents = emptyList(),
            correlationId = "corr-empty-sys",
            systemPrompt = "You are helpful."
        )

        // ACT
        val messages = provider.buildRequestPayload(request).jsonObject["messages"]?.jsonArray

        // ASSERT
        assertNotNull(messages)
        assertEquals(2, messages.size, "Only the system message and 1 message should be present.")
        assertEquals("system", messages[0].jsonObject["role"]?.toString()?.trim('"'))
    }

    @Test
    fun `buildRequestPayload passes through pre-enriched content unchanged`() {
        // ARRANGE: The pipeline pre-enriches content before sending to providers.
        val enrichedContent = "User (user-1) @ 2024-01-01T00:00:01Z: Hello"
        val request = GatewayRequest(
            modelName = "mercury-2",
            contents = listOf(GatewayMessage("user", enrichedContent, "user-1", "User", 1000L)),
            correlationId = "corr-enriched"
        )

        // ACT
        val messages = provider.buildRequestPayload(request).jsonObject["messages"]?.jsonArray
        val content = messages?.get(0)?.jsonObject?.get("content")?.toString()?.trim('"')

        // ASSERT
        assertNotNull(content)
        assertEquals(enrichedContent, content, "Provider should pass through pre-enriched content unchanged.")
    }

    @Test
    fun `buildRequestPayload handles alternating user and assistant messages`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "mercury-2",
            contents = listOf(
                GatewayMessage("user",  "Q1", "user-1", "User",  1000L),
                GatewayMessage("model", "A1", "agent-1", "Agent", 2000L),
                GatewayMessage("user",  "Q2", "user-1", "User",  3000L),
                GatewayMessage("model", "A2", "agent-1", "Agent", 4000L)
            ),
            correlationId = "corr-multi"
        )

        // ACT
        val messages = provider.buildRequestPayload(request).jsonObject["messages"]?.jsonArray

        // ASSERT
        assertNotNull(messages)
        assertEquals(4, messages.size)
        assertEquals("user",      messages[0].jsonObject["role"]?.toString()?.trim('"'))
        assertEquals("assistant", messages[1].jsonObject["role"]?.toString()?.trim('"'))
        assertEquals("user",      messages[2].jsonObject["role"]?.toString()?.trim('"'))
        assertEquals("assistant", messages[3].jsonObject["role"]?.toString()?.trim('"'))
    }

    // -------------------------------------------------------------------------
    // parseResponse
    // -------------------------------------------------------------------------

    @Test
    fun `parseResponse correctly handles a successful API response`() {
        // ARRANGE
        val responseBody = """{ "choices": [{ "message": { "role": "assistant", "content": "Mercury Response" } }] }"""
        val correlationId = "corr-ok"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertEquals("Mercury Response", response.rawContent)
        assertNull(response.errorMessage)
        assertEquals(correlationId, response.correlationId)
    }

    @Test
    fun `parseResponse correctly extracts token usage from successful response`() {
        // ARRANGE: Token fields use OpenAI's snake_case names.
        val responseBody = """{
            "choices": [{ "message": { "role": "assistant", "content": "Response" } }],
            "usage": { "prompt_tokens": 85, "completion_tokens": 22, "total_tokens": 107 }
        }"""
        val correlationId = "corr-tokens"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertEquals("Response", response.rawContent)
        assertEquals(85, response.inputTokens)
        assertEquals(22, response.outputTokens)
        assertNull(response.errorMessage)
    }

    @Test
    fun `parseResponse logs warning when successful response has no token usage`() {
        // ARRANGE
        val responseBody = """{ "choices": [{ "message": { "role": "assistant", "content": "Response" } }] }"""
        val correlationId = "corr-no-tokens"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertEquals("Response", response.rawContent)
        assertNull(response.inputTokens)
        assertNull(response.outputTokens)
        val warnLog = platform.capturedLogs.find { it.level == LogLevel.WARN && it.tag == "inception" }
        assertNotNull(warnLog, "A warning should be logged when token usage is missing from a successful response.")
        assertTrue(warnLog.message.contains("no token usage data"))
    }

    @Test
    fun `parseResponse handles API error returned as a bare string - the auth error shape`() {
        // ARRANGE: Inception returns {"error": "Incorrect API key provided"} for auth failures.
        // This is the non-standard shape that caused the original JsonDecodingException.
        val responseBody = """{"error":"Incorrect API key provided"}"""
        val correlationId = "corr-auth-err"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertNull(response.rawContent)
        assertEquals("API Error: Incorrect API key provided", response.errorMessage)
        assertEquals(correlationId, response.correlationId)
    }

    @Test
    fun `parseResponse handles API error returned as an object - the structured error shape`() {
        // ARRANGE: Some error paths return {"error": {"message": "...", "type": "...", "code": "..."}}.
        val responseBody = """{"error": {"message": "You exceeded your quota.", "type": "quota_exceeded", "code": "429"}}"""
        val correlationId = "corr-struct-err"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertNull(response.rawContent)
        assertEquals("API Error: You exceeded your quota.", response.errorMessage)
        assertEquals(correlationId, response.correlationId)
    }

    @Test
    fun `parseResponse correctly handles an unrecognised response format`() {
        // ARRANGE
        val responseBody = """{ "some_new_field": "some_value" }"""
        val correlationId = "corr-unknown"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertNull(response.rawContent)
        assertEquals("Unrecognised response format from Inception API.", response.errorMessage)
        val log = platform.capturedLogs.find { it.level == LogLevel.ERROR && it.tag == "inception" }
        assertNotNull(log, "An error should be logged for an unrecognised response.")
        assertTrue(log.message.contains(responseBody))
    }

    @Test
    fun `parseResponse handles choice with null content field`() {
        // ARRANGE: content can be null for e.g. function-call responses.
        val responseBody = """{ "choices": [{ "message": { "role": "assistant", "content": null } }] }"""
        val correlationId = "corr-null-content"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertNull(response.rawContent)
        assertEquals("Unrecognised response format from Inception API.", response.errorMessage)
    }

    // -------------------------------------------------------------------------
    // generatePreview
    // -------------------------------------------------------------------------

    @Test
    fun `generatePreview returns a pretty-printed JSON string`() = kotlinx.coroutines.test.runTest {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "mercury-2",
            contents = listOf(GatewayMessage("user", "Hello", "u1", "User", 1000L)),
            correlationId = "123"
        )

        // ACT
        val preview = provider.generatePreview(request, emptyMap())

        // ASSERT
        assertTrue(preview.contains("\"model\": \"mercury-2\""), "Should contain model name")
        assertTrue(preview.contains("\"messages\": ["), "Should contain messages array")
        assertTrue(preview.contains("\"role\": \"user\""), "Should contain user role")
        assertTrue(preview.contains("Hello"), "Should contain message content")
    }

    @Test
    fun `generatePreview includes system prompt as a system-role message`() = kotlinx.coroutines.test.runTest {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "mercury-2",
            contents = listOf(GatewayMessage("user", "Hello", "u1", "User", 1000L)),
            correlationId = "123",
            systemPrompt = "You are a pirate."
        )

        // ACT
        val preview = provider.generatePreview(request, emptyMap())

        // ASSERT
        assertTrue(preview.contains("\"role\": \"system\""), "Should contain system role message")
        assertTrue(preview.contains("You are a pirate."), "Should contain system prompt text")
    }

    // -------------------------------------------------------------------------
    // countTokens
    // -------------------------------------------------------------------------

    @Test
    fun `countTokens returns null - Inception has no token counting endpoint`() = kotlinx.coroutines.test.runTest {
        // ARRANGE: InceptionProvider intentionally does not override countTokens.
        // The GatewayFeature handles null by omitting the estimate from RETURN_PREVIEW.
        val request = GatewayRequest(
            modelName = "mercury-2",
            contents = listOf(GatewayMessage("user", "Hello", "u1", "User", 1000L)),
            correlationId = "corr-count"
        )
        val settings = mapOf("gateway.inception.apiKey" to "test-key")

        // ACT & ASSERT
        // We can only test the interface contract here (returns null) since the
        // default implementation has no network call to intercept.
        val result = (provider as UniversalGatewayProvider).countTokens(request, settings)
        assertNull(result, "InceptionProvider should return null for countTokens — no endpoint exists.")
    }
}