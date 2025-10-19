package app.auf.feature.gateway

import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.gateway.openai.OpenAIProvider
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tier 1 Unit Test for OpenAIProvider.
 *
 * Mandate (P-TEST-001, T1): To test the provider's logic for translating a universal
 * GatewayRequest into a provider-specific API call, using a MockEngine to intercept
 * the network request.
 */
class GatewayFeatureT1OpenAIProviderTest {

    private val platform = FakePlatformDependencies("test")

    private fun createProvider(mockResponse: String, statusCode: HttpStatusCode = HttpStatusCode.OK): OpenAIProvider {
        val mockEngine = MockEngine { request ->
            // Capture the request body for assertion
            val requestBody = request.body.toByteArray().decodeToString()
            assertEquals(
                """{"model":"gpt-4o","messages":[{"role":"user","content":"Hello"},{"role":"assistant","content":"Hi there"}]}""",
                requestBody
            )

            respond(
                content = ByteReadChannel(mockResponse),
                status = statusCode,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        return OpenAIProvider(platform, mockEngine)
    }

    @Test
    fun `generateContent correctly transforms universal request to OpenAI format and parses response`() = runTest {
        val mockApiResponse = """{ "choices": [{ "message": { "role": "assistant", "content": "OpenAI Response" } }] }"""
        val provider = createProvider(mockApiResponse)
        val request = GatewayRequest(
            modelName = "gpt-4o",
            // Note: OpenAI uses "assistant", not "model"
            contents = listOf(GatewayMessage("user", "Hello"), GatewayMessage("assistant", "Hi there")),
            correlationId = "corr-123"
        )

        val response = provider.generateContent(request, mapOf("gateway.openai.apiKey" to "fake-key"))

        assertEquals("OpenAI Response", response.rawContent)
        assertNull(response.errorMessage)
    }

    @Test
    fun `generateContent handles API errors correctly`() = runTest {
        val mockErrorResponse = """{ "error": { "message": "You exceeded your current quota" } }"""
        val provider = createProvider(mockErrorResponse)
        val request = GatewayRequest("gpt-4o", listOf(GatewayMessage("user", "Hello")), "corr-456")

        val response = provider.generateContent(request, mapOf("gateway.openai.apiKey" to "fake-key"))

        assertNull(response.rawContent)
        assertEquals("API Error: You exceeded your current quota", response.errorMessage)
    }
}