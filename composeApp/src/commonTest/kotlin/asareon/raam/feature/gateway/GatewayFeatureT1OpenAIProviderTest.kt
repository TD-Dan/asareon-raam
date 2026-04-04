package app.auf.feature.gateway

import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.gateway.openai.OpenAIProvider
import app.auf.util.LogLevel
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Tier 1 Unit Test for OpenAIProvider.
 *
 * Mandate (P-TEST-001, T1): To test the provider's pure data transformation logic
 * without any network dependencies.
 *
 * ARCHITECTURE: Content enrichment (adding sender info, timestamps) is the
 * responsibility of AgentCognitivePipeline.executeTurn, NOT the providers.
 * Providers receive pre-enriched content and pass it through unchanged.
 * However, OpenAI's `name` field IS provider-specific and should be tested here.
 */
class GatewayFeatureT1OpenAIProviderTest {
    private val platform = FakePlatformDependencies("test")
    private val provider = OpenAIProvider(platform)

    @Test
    fun `buildRequestPayload correctly transforms universal request to OpenAI format`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "gpt-4o",
            contents = listOf(
                GatewayMessage("user", "Hello", "user-1", "User", 1000L),
                GatewayMessage("model", "Hi there", "agent-1", "Assistant", 2000L)
            ),
            correlationId = "corr-123"
        )
        val expected = buildJsonObject {
            put("model", "gpt-4o")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "Hello")
                    put("name", "User_user-1")
                })
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", "Hi there")
                    put("name", "Assistant_agent-1")
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
        val request = GatewayRequest(
            modelName = "gpt-4o",
            contents = listOf(GatewayMessage("user", "Hello", "user-1", "User", 1000L)),
            correlationId = "corr-123",
            systemPrompt = "You are a pirate."
        )
        val expected = buildJsonObject {
            put("model", "gpt-4o")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", "You are a pirate.")
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "Hello")
                    put("name", "User_user-1")
                })
            })
        }

        val actual = provider.buildRequestPayload(request)

        assertEquals(expected, actual)
    }

    @Test
    fun `buildRequestPayload includes sanitized name field for multi-agent clarity`() {
        // ARRANGE: OpenAI supports a `name` field on messages — this is provider-specific
        val request = GatewayRequest(
            modelName = "gpt-4o",
            contents = listOf(
                GatewayMessage("user", "Hello", "user-123", "John Doe", 1000L)
            ),
            correlationId = "corr-123"
        )

        // ACT
        val actual = provider.buildRequestPayload(request)
        val messages = actual.jsonObject["messages"]?.jsonArray
        val nameField = messages?.get(0)?.jsonObject?.get("name")?.toString()?.trim('"')

        // ASSERT
        assertNotNull(nameField, "OpenAI messages should include a 'name' field")
        // Name should be sanitized: only [a-zA-Z0-9_-], max 64 chars
        assertTrue(nameField.matches(Regex("^[a-zA-Z0-9_-]{1,64}$")), "Name should be sanitized for OpenAI: $nameField")
        assertTrue(nameField.contains("John"), "Name should contain sender name")
        assertTrue(nameField.contains("user-123"), "Name should contain sender ID")
    }

    @Test
    fun `parseResponse correctly handles a successful API response`() {
        // ARRANGE
        val responseBody = """{ "choices": [{ "message": { "role": "assistant", "content": "OpenAI Response" } }] }"""
        val correlationId = "corr-123"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertEquals("OpenAI Response", response.rawContent)
        assertNull(response.errorMessage)
        assertEquals(correlationId, response.correlationId)
    }

    @Test
    fun `parseResponse correctly extracts token usage from successful response`() {
        // ARRANGE
        val responseBody = """{
            "choices": [{ "message": { "role": "assistant", "content": "Response" } }],
            "usage": { "prompt_tokens": 120, "completion_tokens": 35, "total_tokens": 155 }
        }"""
        val correlationId = "corr-tokens"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertEquals("Response", response.rawContent)
        assertEquals(120, response.inputTokens)
        assertEquals(35, response.outputTokens)
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
        val warnLog = platform.capturedLogs.find { it.level == LogLevel.WARN && it.tag == "openai" }
        assertNotNull(warnLog, "A warning should be logged when token usage is missing from a successful response.")
        assertTrue(warnLog.message.contains("no token usage data"))
    }

    @Test
    fun `parseResponse correctly handles an API error response`() {
        // ARRANGE
        val responseBody = """{ "error": { "message": "You exceeded your current quota" } }"""
        val correlationId = "corr-456"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertNull(response.rawContent)
        assertEquals("API Error: You exceeded your current quota", response.errorMessage)
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
        assertEquals("Unrecognised response format from OpenAI API.", response.errorMessage)
        val log = platform.capturedLogs.find { it.level == LogLevel.ERROR && it.tag == "openai" }
        assertNotNull(log, "An error should be logged for the unrecognized response.")
        assertTrue(log.message.contains(responseBody))
    }

    @Test
    fun `generatePreview returns a pretty-printed JSON string`() = kotlinx.coroutines.test.runTest {
        val request = GatewayRequest(
            modelName = "gpt-4o",
            contents = listOf(GatewayMessage("user", "Hello", "u1", "User", 1000L)),
            correlationId = "123"
        )

        val preview = provider.generatePreview(request, emptyMap())

        assertTrue(preview.contains("\"model\": \"gpt-4o\""), "Should contain model name")
        assertTrue(preview.contains("\"messages\": ["), "Should contain messages array")
        assertTrue(preview.contains("\"role\": \"user\""), "Should contain user role")
    }

    @Test
    fun `buildRequestPayload passes through pre-enriched content unchanged`() {
        // ARRANGE: The pipeline pre-enriches content before sending to providers.
        val enrichedContent = "John Doe (user-123) @ 2024-01-01T00:00:05Z: Test message"
        val request = GatewayRequest(
            modelName = "gpt-4o",
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
    fun `generatePreview includes system prompt when provided`() = kotlinx.coroutines.test.runTest {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "gpt-4o",
            contents = listOf(GatewayMessage("user", "Hello", "u1", "User", 1000L)),
            correlationId = "123",
            systemPrompt = "You are a pirate."
        )

        // ACT
        val preview = provider.generatePreview(request, emptyMap())

        // ASSERT
        assertTrue(preview.contains("\"role\": \"system\""), "Should contain system role")
        assertTrue(preview.contains("You are a pirate."), "Should contain system prompt text")
    }

    @Test
    fun `buildRequestPayload omits system message when no system prompt provided`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "gpt-4o",
            contents = listOf(GatewayMessage("user", "Hello", "user-1", "User", 1000L)),
            correlationId = "corr-123"
        )

        // ACT
        val actual = provider.buildRequestPayload(request)
        val messages = actual.jsonObject["messages"]?.jsonArray

        // ASSERT
        assertNotNull(messages)
        assertEquals(1, messages.size, "Only the user message should be present, no system message.")
        assertEquals("user", messages[0].jsonObject["role"]?.toString()?.trim('"'))
    }

    @Test
    fun `buildRequestPayload handles empty contents list`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "gpt-4o",
            contents = emptyList(),
            correlationId = "corr-empty"
        )

        // ACT
        val actual = provider.buildRequestPayload(request)
        val messages = actual.jsonObject["messages"]?.jsonArray

        // ASSERT
        assertNotNull(messages)
        assertEquals(1, messages.size, "Messages array should contain 1 message when contents list is empty.")
    }

    @Test
    fun `buildRequestPayload handles empty contents with system prompt`() {
        // ARRANGE: System prompt should still produce one message even with empty contents.
        val request = GatewayRequest(
            modelName = "gpt-4o",
            contents = emptyList(),
            correlationId = "corr-empty-sys",
            systemPrompt = "You are helpful."
        )

        // ACT
        val actual = provider.buildRequestPayload(request)
        val messages = actual.jsonObject["messages"]?.jsonArray

        // ASSERT
        assertNotNull(messages)
        assertEquals(2, messages.size, "Only the system message and one message should be present.")
        assertEquals("system", messages[0].jsonObject["role"]?.toString()?.trim('"'))
    }

    @Test
    fun `sanitized name handles special characters`() {
        // ARRANGE: Name with characters outside [a-zA-Z0-9_-] should be sanitized.
        val request = GatewayRequest(
            modelName = "gpt-4o",
            contents = listOf(
                GatewayMessage("user", "Hello", "user-1", "José García!", 1000L)
            ),
            correlationId = "corr-special"
        )

        // ACT
        val actual = provider.buildRequestPayload(request)
        val messages = actual.jsonObject["messages"]?.jsonArray
        val nameField = messages?.get(0)?.jsonObject?.get("name")?.toString()?.trim('"')

        // ASSERT
        assertNotNull(nameField)
        assertTrue(nameField.matches(Regex("^[a-zA-Z0-9_-]{1,64}$")), "Name should be sanitized: $nameField")
    }

    @Test
    fun `sanitized name truncates to 64 characters`() {
        // ARRANGE: Name + senderId longer than 64 characters should be truncated.
        val longName = "A".repeat(50)
        val longId = "B".repeat(50)
        val request = GatewayRequest(
            modelName = "gpt-4o",
            contents = listOf(
                GatewayMessage("user", "Hello", longId, longName, 1000L)
            ),
            correlationId = "corr-long"
        )

        // ACT
        val actual = provider.buildRequestPayload(request)
        val messages = actual.jsonObject["messages"]?.jsonArray
        val nameField = messages?.get(0)?.jsonObject?.get("name")?.toString()?.trim('"')

        // ASSERT
        assertNotNull(nameField)
        assertTrue(nameField.length <= 64, "Name should be truncated to 64 chars, was ${nameField.length}: $nameField")
        assertTrue(nameField.matches(Regex("^[a-zA-Z0-9_-]{1,64}$")), "Name should remain valid after truncation: $nameField")
    }

    @Test
    fun `parseResponse handles choice with null content field`() {
        // ARRANGE: The `content` field in a choice message can be null (e.g., function call responses).
        val responseBody = """{ "choices": [{ "message": { "role": "assistant", "content": null } }] }"""
        val correlationId = "corr-null-content"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertNull(response.rawContent)
        assertEquals("Unrecognised response format from OpenAI API.", response.errorMessage)
        assertEquals(correlationId, response.correlationId)
    }
}