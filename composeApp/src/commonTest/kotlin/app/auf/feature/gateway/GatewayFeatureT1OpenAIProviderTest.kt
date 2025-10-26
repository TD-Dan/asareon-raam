package app.auf.feature.gateway

import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.gateway.openai.OpenAIProvider
import app.auf.util.LogLevel
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Tier 1 Unit Test for OpenAIProvider.
 *
 * Mandate (P-TEST-001, T1): To test the provider's pure data transformation logic
 * without any network dependencies.
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
                    put("content", "User (user-1) @ ISO_TIMESTAMP_1000: Hello")
                    put("name", "User_user-1")
                })
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", "Assistant (agent-1) @ ISO_TIMESTAMP_2000: Hi there")
                    put("name", "Assistant_agent-1")
                })
            })
        }

        // ACT
        val actual = provider.buildRequestPayload(request)

        // ASSERT
        assertEquals(expected, actual)
    }

    // TEST: New test for system prompt inclusion.
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
                    put("content", "User (user-1) @ ISO_TIMESTAMP_1000: Hello")
                    put("name", "User_user-1")
                })
            })
        }

        val actual = provider.buildRequestPayload(request)

        assertEquals(expected, actual)
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
}