package app.auf.feature.gateway

import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.gateway.gemini.GeminiProvider
import app.auf.util.LogLevel
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.*

/**
 * Tier 1 Unit Test for GeminiProvider.
 *
 * Mandate (P-TEST-001, T1): To test the provider's pure data transformation logic
 * without any network dependencies.
 *
 * ARCHITECTURE NOTE: Content enrichment (adding sender info, timestamps) is the
 * responsibility of AgentCognitivePipeline.executeTurn, NOT the providers.
 * Providers receive pre-enriched content and pass it through unchanged.
 */
class GatewayFeatureT1GeminiProviderTest {

    private val platform = FakePlatformDependencies("test")
    private val provider = GeminiProvider(platform)

    @Test
    fun `buildRequestPayload correctly transforms universal request to Gemini format`() {
        // ARRANGE: Content arrives as-is from the pipeline (already enriched upstream)
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
                    put("parts", buildJsonArray { add(buildJsonObject{ put("text", "Hello") }) })
                })
                add(buildJsonObject {
                    put("role", "model")
                    put("parts", buildJsonArray { add(buildJsonObject{ put("text", "Hi there") }) })
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
            modelName = "gemini-pro",
            contents = listOf(GatewayMessage("user", "Hello", "user-1", "User", 1000L)),
            correlationId = "corr-123",
            systemPrompt = "You are a helpful assistant."
        )
        // NOTE: Gemini provider places system_instruction before contents in the JSON.
        // JsonObject equality is order-independent, so this works regardless of insertion order.
        val expected = buildJsonObject {
            putJsonObject("system_instruction") {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", "You are a helpful assistant.") })
                })
            }
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray { add(buildJsonObject{ put("text", "Hello") }) })
                })
            })
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
    fun `parseResponse correctly extracts token usage from successful response`() {
        // ARRANGE
        val responseBody = """{
            "candidates": [{ "content": { "parts": [{ "text": "Response" }] } }],
            "usageMetadata": { "promptTokenCount": 200, "candidatesTokenCount": 55, "totalTokenCount": 255 }
        }"""
        val correlationId = "corr-tokens"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertEquals("Response", response.rawContent)
        assertEquals(200, response.inputTokens)
        assertEquals(55, response.outputTokens)
        assertNull(response.errorMessage)
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

    @Test
    fun `generatePreview returns a pretty-printed JSON string`() = kotlinx.coroutines.test.runTest {
        val request = GatewayRequest(
            modelName = "gemini-pro",
            contents = listOf(GatewayMessage("user", "Hello", "u1", "User", 1000L)),
            correlationId = "123"
        )

        val preview = provider.generatePreview(request, emptyMap())

        // We verify structural integrity rather than exact whitespace matching
        assertTrue(preview.contains("\"contents\": ["), "Should contain contents array")
        assertTrue(preview.contains("\"parts\": ["), "Should contain parts array")
        assertTrue(preview.contains("Hello"), "Should contain message content")
    }

    @Test
    fun `buildRequestPayload passes through pre-enriched content unchanged`() {
        // ARRANGE: The pipeline pre-enriches content before sending to providers.
        val enrichedContent = "User (user-1) @ 2024-01-01T00:00:01Z: Hello"
        val request = GatewayRequest(
            modelName = "gemini-pro",
            contents = listOf(
                GatewayMessage("user", enrichedContent, "user-1", "User", 1000L)
            ),
            correlationId = "corr-123"
        )

        // ACT
        val actual = provider.buildRequestPayload(request)

        // ASSERT
        val expectedPayload = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray { add(buildJsonObject{ put("text", enrichedContent) }) })
                })
            })
        }
        assertEquals(expectedPayload, actual)
    }

    @Test
    fun `parseResponse logs warning when successful response has no token usage`() {
        // ARRANGE: Parity with Anthropic/OpenAI token usage warning tests.
        val responseBody = """{ "candidates": [{ "content": { "parts": [{ "text": "Response" }] } }] }"""
        val correlationId = "corr-no-tokens"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertEquals("Response", response.rawContent)
        assertNull(response.inputTokens)
        assertNull(response.outputTokens)
        val warnLog = platform.capturedLogs.find { it.level == LogLevel.WARN && it.tag == "gemini" }
        assertNotNull(warnLog, "A warning should be logged when token usage is missing from a successful response.")
        assertTrue(warnLog.message.contains("no token usage data"))
    }

    @Test
    fun `generatePreview includes system prompt when provided`() = kotlinx.coroutines.test.runTest {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "gemini-pro",
            contents = listOf(GatewayMessage("user", "Hello", "u1", "User", 1000L)),
            correlationId = "123",
            systemPrompt = "You are helpful."
        )

        // ACT
        val preview = provider.generatePreview(request, emptyMap())

        // ASSERT
        assertTrue(preview.contains("system_instruction"), "Should contain system_instruction block")
        assertTrue(preview.contains("You are helpful."), "Should contain system prompt text")
    }

    @Test
    fun `buildRequestPayload omits system_instruction when no system prompt provided`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "gemini-pro",
            contents = listOf(GatewayMessage("user", "Hello", "user-1", "User", 1000L)),
            correlationId = "corr-123"
        )

        // ACT
        val actual = provider.buildRequestPayload(request)

        // ASSERT
        assertFalse(
            actual.jsonObject.containsKey("system_instruction"),
            "The 'system_instruction' key should not be present when no system prompt is provided."
        )
    }

    @Test
    fun `buildRequestPayload handles empty contents list`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "gemini-pro",
            contents = emptyList(),
            correlationId = "corr-empty"
        )

        // ACT
        val actual = provider.buildRequestPayload(request)
        val contents = actual.jsonObject["contents"]?.jsonArray

        // ASSERT
        assertNotNull(contents)
        assertEquals(0, contents.size, "Contents array should be empty when contents list is empty.")
    }
}