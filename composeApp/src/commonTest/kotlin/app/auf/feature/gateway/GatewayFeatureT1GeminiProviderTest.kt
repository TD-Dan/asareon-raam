package app.auf.feature.gateway

import app.auf.fakes.CapturedLog
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.gateway.gemini.GeminiProvider
import app.auf.util.LogLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.*

/**
 * Tier 1 Unit Test for GeminiProvider.
 *
 * Mandate (P-TEST-001, T1): To test the provider's pure data transformation logic
 * without any network dependencies.
 */
class GatewayFeatureT1GeminiProviderTest {

    private val platform = FakePlatformDependencies("test")
    private val provider = GeminiProvider(platform)

    @Test
    fun `buildRequestPayload correctly transforms universal request to Gemini format`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "gemini-pro",
            contents = listOf(
                GatewayMessage("user", "Hello", "user-1", "User", 1000L),
                GatewayMessage("model", "Hi there", "agent-1", "Assistant", 2000L)
            ),
            correlationId = "corr-123"
        )
        val expected = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray { add(buildJsonObject{ put("text", "User (user-1) @ ISO_TIMESTAMP_1000: Hello") }) })
                })
                add(buildJsonObject {
                    put("role", "model")
                    put("parts", buildJsonArray { add(buildJsonObject{ put("text", "Assistant (agent-1) @ ISO_TIMESTAMP_2000: Hi there") }) })
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
            modelName = "gemini-pro",
            contents = listOf(GatewayMessage("user", "Hello", "user-1", "User", 1000L)),
            correlationId = "corr-123",
            systemPrompt = "You are a helpful assistant."
        )
        val expected = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray { add(buildJsonObject{ put("text", "User (user-1) @ ISO_TIMESTAMP_1000: Hello") }) })
                })
            })
            putJsonObject("system_instruction") {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", "You are a helpful assistant.") })
                })
            }
        }

        val actual = provider.buildRequestPayload(request)

        assertEquals(expected, actual)
    }

    @Test
    fun `parseResponse correctly handles a successful API response`() {
        // ARRANGE
        val responseBody = """{ "candidates": [{ "content": { "parts": [{ "text": "Gemini Response" }] } }] }"""
        val correlationId = "corr-123"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertEquals("Gemini Response", response.rawContent)
        assertNull(response.errorMessage)
        assertEquals(correlationId, response.correlationId)
    }

    @Test
    fun `parseResponse correctly handles an API error response`() {
        // ARRANGE
        val responseBody = """{ "error": { "message": "API key not valid" } }"""
        val correlationId = "corr-456"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertNull(response.rawContent)
        assertEquals("API Error: API key not valid", response.errorMessage)
        assertEquals(correlationId, response.correlationId)
    }

    @Test
    fun `parseResponse correctly handles a blocked prompt response`() {
        // ARRANGE
        val responseBody = """{ "candidates": [{}], "promptFeedback": { "blockReason": "SAFETY" } }"""
        val correlationId = "corr-789"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertNull(response.rawContent)
        assertEquals("Blocked by provider: SAFETY", response.errorMessage)
        assertEquals(correlationId, response.correlationId)
    }

    @Test
    fun `parseResponse correctly handles a successful but empty STOP response`() {
        // ARRANGE
        val responseBody = """
        {
          "candidates": [ { "content": { "role": "model" }, "finishReason": "STOP", "index": 0 } ]
        }
        """.trimIndent()
        val correlationId = "corr-stop-123"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertEquals("", response.rawContent, "Content should be an empty string for a STOP response.")
        assertNull(response.errorMessage, "Error message should be null for a successful STOP.")
        assertTrue(platform.capturedLogs.none { it.level == LogLevel.ERROR }, "No error should be logged.")
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
        assertEquals("Unrecognised response format from Gemini API.", response.errorMessage)
        val log = platform.capturedLogs.find { it.level == LogLevel.ERROR && it.tag == "gemini" }
        assertNotNull(log, "An error should be logged for the unrecognized response.")
        assertTrue(log.message.contains(responseBody))
    }
}