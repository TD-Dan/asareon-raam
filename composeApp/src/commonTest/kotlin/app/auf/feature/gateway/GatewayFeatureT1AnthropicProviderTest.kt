package app.auf.feature.gateway

import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.gateway.anthropic.AnthropicProvider
import app.auf.util.LogLevel
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Tier 1 Unit Test for AnthropicProvider.
 *
 * Mandate (P-TEST-001, T1): To test the provider's pure data transformation logic
 * without any network dependencies.
 */
class GatewayFeatureT1AnthropicProviderTest {
    private val platform = FakePlatformDependencies("test")
    private val provider = AnthropicProvider(platform)

    @Test
    fun `buildRequestPayload correctly transforms universal request to Anthropic format`() {
        // ARRANGE
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
            put("max_tokens", 8192)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "User (user-1) @ ISO_TIMESTAMP_1000: Hello")
                })
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", "Assistant (agent-1) @ ISO_TIMESTAMP_2000: Hi there")
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
            put("max_tokens", 8192)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "User (user-1) @ ISO_TIMESTAMP_1000: Hello")
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
        assertTrue(preview.contains("\"max_tokens\": 8192"), "Should contain max_tokens")
        assertTrue(preview.contains("User (u1) @"), "Should contain enriched content")
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
    fun `buildRequestPayload enriches content with sender info and timestamp`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "claude-3-5-sonnet-20241022",
            contents = listOf(
                GatewayMessage("user", "Test message", "user-123", "John Doe", 5000L)
            ),
            correlationId = "corr-123"
        )

        // ACT
        val actual = provider.buildRequestPayload(request)
        val messages = actual.jsonObject["messages"]?.jsonArray
        val content = messages?.get(0)?.jsonObject?.get("content")?.toString()?.trim('"')

        // ASSERT
        assertNotNull(content)
        assertTrue(content.contains("John Doe"), "Should contain sender name")
        assertTrue(content.contains("user-123"), "Should contain sender ID")
        assertTrue(content.contains("ISO_TIMESTAMP_5000"), "Should contain formatted timestamp")
        assertTrue(content.contains("Test message"), "Should contain original message")
    }
}
