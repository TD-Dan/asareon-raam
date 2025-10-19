package app.auf.feature.gateway

import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.gateway.gemini.GeminiProvider
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Tier 1 Unit Test for GeminiProvider.
 *
 * Mandate (P-TEST-001, T1): To test the provider's logic for translating a universal
 * GatewayRequest into a provider-specific API call, using a MockEngine to intercept
 * the network request.
 */
class GatewayFeatureT1GeminiProviderTest {

    private val platform = FakePlatformDependencies("test")

    private fun createProvider(mockResponse: String, statusCode: HttpStatusCode = HttpStatusCode.OK): GeminiProvider {
        val mockEngine = MockEngine { request ->
            // Capture the request body for assertion
            val requestBody = request.body.toByteArray().decodeToString()
            assertEquals(
                """{"contents":[{"role":"user","parts":[{"text":"Hello"}]},{"role":"model","parts":[{"text":"Hi there"}]}]}""",
                requestBody
            )

            respond(
                content = ByteReadChannel(mockResponse),
                status = statusCode,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = HttpClient(mockEngine)
        return GeminiProvider(platform, client)
    }

    @Test
    fun `generateContent correctly transforms universal request to Gemini format and parses response`() = runTest {
        val mockApiResponse = """{ "candidates": [{ "content": { "parts": [{ "text": "Gemini Response" }] } }] }"""
        val provider = createProvider(mockApiResponse)
        val request = GatewayRequest(
            modelName = "gemini-pro",
            contents = listOf(GatewayMessage("user", "Hello"), GatewayMessage("model", "Hi there")),
            correlationId = "corr-123"
        )

        val response = provider.generateContent(request, mapOf("gateway.gemini.apiKey" to "fake-key"))

        assertEquals("Gemini Response", response.rawContent)
        assertNull(response.errorMessage)
        assertEquals("corr-123", response.correlationId)
    }

    @Test
    fun `generateContent handles API errors correctly`() = runTest {
        val mockErrorResponse = """{ "error": { "message": "API key not valid" } }"""
        val provider = createProvider(mockErrorResponse)
        val request = GatewayRequest("gemini-pro", listOf(GatewayMessage("user", "Hello")), "corr-456")

        val response = provider.generateContent(request, mapOf("gateway.gemini.apiKey" to "fake-key"))

        assertNull(response.rawContent)
        assertEquals("API Error: API key not valid", response.errorMessage)
    }
}