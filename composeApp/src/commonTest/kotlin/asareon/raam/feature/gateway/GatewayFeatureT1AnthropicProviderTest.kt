package asareon.raam.feature.gateway

import asareon.raam.fakes.FakePlatformDependencies
import asareon.raam.feature.gateway.anthropic.AnthropicProvider
import asareon.raam.util.LogLevel
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Tier 1 Unit Test for AnthropicProvider.
 *
 * Mandate (P-TEST-001, T1): To test the provider's pure data transformation logic
 * without any network dependencies.
 *
 * ARCHITECTURE: Content enrichment (adding sender info, timestamps) is the
 * responsibility of AgentCognitivePipeline.executeTurn, NOT the providers.
 * Providers receive pre-enriched content and pass it through unchanged.
 * These tests verify that pass-through behaviour.
 */
class GatewayFeatureT1AnthropicProviderTest {
    private val platform = FakePlatformDependencies("test")
    private val provider = AnthropicProvider(platform)

    @Test
    fun `buildRequestPayload correctly transforms universal request to Anthropic format`() {
        // ARRANGE: Content arrives pre-enriched from the pipeline
        val request = GatewayRequest(
            modelName = "claude-3-5-sonnet-20241022",
            contents = listOf(
                GatewayMessage("user", "Hello", "user-1", "User", 1000L),
                GatewayMessage("model", "Hi there", "agent-1", "Assistant", 2000L)
            ),
            correlationId = "corr-123"
        )
        val expected = buildJsonObject {
            put("model", "claude-3-5-sonnet-20241022")
            put("max_tokens", DEFAULT_MAX_OUTPUT_TOKENS)
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
    fun `buildRequestPayload includes system prompt when provided`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "claude-3-5-sonnet-20241022",
            contents = listOf(GatewayMessage("user", "Hello", "user-1", "User", 1000L)),
            correlationId = "corr-123",
            systemPrompt = "You are a helpful assistant."
        )
        val expected = buildJsonObject {
            put("model", "claude-3-5-sonnet-20241022")
            put("max_tokens", DEFAULT_MAX_OUTPUT_TOKENS)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "Hello")
                })
            })
            put("system", "You are a helpful assistant.")
        }

        // ACT
        val actual = provider.buildRequestPayload(request)

        // ASSERT
        assertEquals(expected, actual)
    }

    @Test
    fun `buildRequestPayload correctly maps user role to user`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "claude-3-5-sonnet-20241022",
            contents = listOf(
                GatewayMessage("user", "Question", "user-1", "User", 1000L)
            ),
            correlationId = "corr-123"
        )

        // ACT
        val actual = provider.buildRequestPayload(request)
        val messages = actual.jsonObject["messages"]?.jsonArray

        // ASSERT
        assertNotNull(messages)
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]?.toString()?.trim('"'))
    }

    @Test
    fun `buildRequestPayload correctly maps model role to assistant`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "claude-3-5-sonnet-20241022",
            contents = listOf(
                GatewayMessage("model", "Answer", "agent-1", "Assistant", 1000L)
            ),
            correlationId = "corr-123"
        )

        // ACT
        val actual = provider.buildRequestPayload(request)
        val messages = actual.jsonObject["messages"]?.jsonArray

        // ASSERT
        assertNotNull(messages)
        assertEquals(1, messages.size)
        assertEquals("assistant", messages[0].jsonObject["role"]?.toString()?.trim('"'))
    }

    @Test
    fun `parseResponse correctly handles a successful API response`() {
        // ARRANGE
        val responseBody = """{ "content": [{ "type": "text", "text": "Claude Response" }] }"""
        val correlationId = "corr-123"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertEquals("Claude Response", response.rawContent)
        assertNull(response.errorMessage)
        assertEquals(correlationId, response.correlationId)
    }

    @Test
    fun `parseResponse correctly extracts token usage from successful response`() {
        // ARRANGE
        val responseBody = """{
            "content": [{ "type": "text", "text": "Response" }],
            "usage": { "input_tokens": 150, "output_tokens": 42 }
        }"""
        val correlationId = "corr-tokens"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertEquals("Response", response.rawContent)
        assertEquals(150, response.inputTokens)
        assertEquals(42, response.outputTokens)
        assertNull(response.errorMessage)
    }

    @Test
    fun `parseResponse logs warning when successful response has no token usage`() {
        // ARRANGE
        val responseBody = """{ "content": [{ "type": "text", "text": "Response" }] }"""
        val correlationId = "corr-no-tokens"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertEquals("Response", response.rawContent)
        assertNull(response.inputTokens)
        assertNull(response.outputTokens)
        val warnLog = platform.capturedLogs.find { it.level == LogLevel.WARN && it.tag == "anthropic" }
        assertNotNull(warnLog, "A warning should be logged when token usage is missing from a successful response.")
        assertTrue(warnLog.message.contains("no token usage data"))
    }

    @Test
    fun `parseResponse correctly handles an API error response`() {
        // ARRANGE
        val responseBody = """{ "error": { "type": "invalid_request_error", "message": "Invalid API key" } }"""
        val correlationId = "corr-456"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertNull(response.rawContent)
        assertEquals("API Error: Invalid API key", response.errorMessage)
        assertEquals(correlationId, response.correlationId)
    }

    @Test
    fun `parseResponse correctly handles response with multiple content blocks`() {
        // ARRANGE
        val responseBody = """
        {
          "content": [
            { "type": "text", "text": "First part" },
            { "type": "text", "text": "Second part" }
          ]
        }
        """.trimIndent()
        val correlationId = "corr-789"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        // According to the implementation, only the first text block is used
        assertEquals("First part", response.rawContent)
        assertNull(response.errorMessage)
        assertEquals(correlationId, response.correlationId)
    }

    @Test
    fun `parseResponse correctly handles an unrecognised response format`() {
        // ARRANGE
        val responseBody = """{ "some_new_field": "some_value" }"""
        val correlationId = "corr-abc"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertNull(response.rawContent)
        assertEquals("Unrecognised response format from Anthropic API.", response.errorMessage)
        val log = platform.capturedLogs.find { it.level == LogLevel.ERROR && it.tag == "anthropic" }
        assertNotNull(log, "An error should be logged for the unrecognized response.")
        assertTrue(log.message.contains(responseBody))
    }

    @Test
    fun `parseResponse handles response with null content`() {
        // ARRANGE
        val responseBody = """{ "type": "message", "role": "assistant" }"""
        val correlationId = "corr-null"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertNull(response.rawContent)
        assertEquals("Unrecognised response format from Anthropic API.", response.errorMessage)
        assertEquals(correlationId, response.correlationId)
    }

    @Test
    fun `parseResponse handles response with empty content array`() {
        // ARRANGE
        val responseBody = """{ "content": [] }"""
        val correlationId = "corr-empty"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertNull(response.rawContent)
        assertEquals("Unrecognised response format from Anthropic API.", response.errorMessage)
        assertEquals(correlationId, response.correlationId)
    }

    @Test
    fun `parseResponse handles content block without text field`() {
        // ARRANGE
        val responseBody = """{ "content": [{ "type": "image" }] }"""
        val correlationId = "corr-notext"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertNull(response.rawContent)
        assertEquals("Unrecognised response format from Anthropic API.", response.errorMessage)
        assertEquals(correlationId, response.correlationId)
    }

    @Test
    fun `generatePreview returns a pretty-printed JSON string`() = kotlinx.coroutines.test.runTest {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "claude-3-5-sonnet-20241022",
            contents = listOf(GatewayMessage("user", "Hello", "u1", "User", 1000L)),
            correlationId = "123"
        )

        // ACT
        val preview = provider.generatePreview(request, emptyMap())

        // ASSERT
        assertTrue(preview.contains("\"model\": \"claude-3-5-sonnet-20241022\""), "Should contain model name")
        assertTrue(preview.contains("\"messages\": ["), "Should contain messages array")
        assertTrue(preview.contains("\"max_tokens\": 16384"), "Should contain max_tokens")
        assertTrue(preview.contains("Hello"), "Should contain message content")
    }

    @Test
    fun `generatePreview includes system prompt when provided`() = kotlinx.coroutines.test.runTest {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "claude-3-5-sonnet-20241022",
            contents = listOf(GatewayMessage("user", "Hello", "u1", "User", 1000L)),
            correlationId = "123",
            systemPrompt = "You are helpful."
        )

        // ACT
        val preview = provider.generatePreview(request, emptyMap())

        // ASSERT
        assertTrue(preview.contains("\"system\": \"You are helpful.\""), "Should contain system prompt")
    }

    @Test
    fun `buildRequestPayload handles alternating user and assistant messages`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "claude-3-5-sonnet-20241022",
            contents = listOf(
                GatewayMessage("user", "First question", "user-1", "User", 1000L),
                GatewayMessage("model", "First answer", "agent-1", "Assistant", 2000L),
                GatewayMessage("user", "Second question", "user-1", "User", 3000L),
                GatewayMessage("model", "Second answer", "agent-1", "Assistant", 4000L)
            ),
            correlationId = "corr-123"
        )

        // ACT
        val actual = provider.buildRequestPayload(request)
        val messages = actual.jsonObject["messages"]?.jsonArray

        // ASSERT
        assertNotNull(messages)
        assertEquals(4, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]?.toString()?.trim('"'))
        assertEquals("assistant", messages[1].jsonObject["role"]?.toString()?.trim('"'))
        assertEquals("user", messages[2].jsonObject["role"]?.toString()?.trim('"'))
        assertEquals("assistant", messages[3].jsonObject["role"]?.toString()?.trim('"'))
    }

    @Test
    fun `buildRequestPayload passes through pre-enriched content unchanged`() {
        // ARRANGE: The pipeline pre-enriches content before sending to providers.
        // This test verifies the provider passes it through without modification.
        val enrichedContent = "John Doe (user-123) @ 2024-01-01T00:00:05Z: Test message"
        val request = GatewayRequest(
            modelName = "claude-3-5-sonnet-20241022",
            contents = listOf(
                GatewayMessage("user", enrichedContent, "user-123", "John Doe", 5000L)
            ),
            correlationId = "corr-123"
        )

        // ACT
        val actual = provider.buildRequestPayload(request)
        val messages = actual.jsonObject["messages"]?.jsonArray
        val content = messages?.get(0)?.jsonObject?.get("content")?.toString()?.trim('"')

        // ASSERT
        assertNotNull(content)
        assertEquals(enrichedContent, content, "Provider should pass through pre-enriched content unchanged")
    }

    @Test
    fun `buildRequestPayload omits system key when no system prompt provided`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "claude-3-5-sonnet-20241022",
            contents = listOf(GatewayMessage("user", "Hello", "user-1", "User", 1000L)),
            correlationId = "corr-123"
        )

        // ACT
        val actual = provider.buildRequestPayload(request)

        // ASSERT
        assertFalse(
            actual.jsonObject.containsKey("system"),
            "The 'system' key should not be present when no system prompt is provided."
        )
    }

    @Test
    fun `buildRequestPayload handles empty contents list`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "claude-3-5-sonnet-20241022",
            contents = emptyList(),
            correlationId = "corr-empty"
        )

        // ACT
        val actual = provider.buildRequestPayload(request)
        val messages = actual.jsonObject["messages"]?.jsonArray

        // ASSERT
        assertNotNull(messages)
        assertEquals(1, messages.size, "Messages array should contain one message when contents list is empty.")
    }

    @Test
    fun `buildCountTokensPayload excludes max_tokens`() {
        // ARRANGE: The count_tokens endpoint must NOT include max_tokens.
        val request = GatewayRequest(
            modelName = "claude-3-5-sonnet-20241022",
            contents = listOf(GatewayMessage("user", "Hello", "user-1", "User", 1000L)),
            correlationId = "corr-count"
        )

        // ACT
        val actual = provider.buildCountTokensPayload(request)

        // ASSERT
        assertFalse(
            actual.jsonObject.containsKey("max_tokens"),
            "count_tokens payload must NOT include max_tokens."
        )
        assertTrue(
            actual.jsonObject.containsKey("model"),
            "count_tokens payload must include model."
        )
        assertTrue(
            actual.jsonObject.containsKey("messages"),
            "count_tokens payload must include messages."
        )
    }

    @Test
    fun `buildCountTokensPayload includes system prompt when provided`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "claude-3-5-sonnet-20241022",
            contents = listOf(GatewayMessage("user", "Hello", "user-1", "User", 1000L)),
            correlationId = "corr-count-sys",
            systemPrompt = "You are helpful."
        )

        // ACT
        val actual = provider.buildCountTokensPayload(request)

        // ASSERT
        assertFalse(actual.jsonObject.containsKey("max_tokens"))
        assertTrue(actual.jsonObject.containsKey("system"), "System prompt should be included in count_tokens payload.")
        assertEquals(
            "You are helpful.",
            actual.jsonObject["system"]?.jsonPrimitive?.content
        )
    }

    @Test
    fun `buildCountTokensPayload omits system key when no system prompt`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "claude-3-5-sonnet-20241022",
            contents = listOf(GatewayMessage("user", "Hello", "user-1", "User", 1000L)),
            correlationId = "corr-count-nosys"
        )

        // ACT
        val actual = provider.buildCountTokensPayload(request)

        // ASSERT
        assertFalse(actual.jsonObject.containsKey("system"))
    }
}