package app.auf.feature.gateway

import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.gateway.openai.OpenAIProvider
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
            contents = listOf(GatewayMessage("user", "Hello"), GatewayMessage("assistant", "Hi there")),
            correlationId = "corr-123"
        )
        val expected = buildJsonObject {
            put("model", "gpt-4o")
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
}