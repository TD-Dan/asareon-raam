// composeApp/src'commonTest/kotlin/app/auf/GatewayTest.kt

package app.auf

import io.ktor.client.engine.mock.*
import io.ktor.client.engine.mock.HttpResponseData // <-- Add this import
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit test suite for the Gateway class.
 *
 * ---
 * ## Mandate
 * This suite verifies the networking logic of the `Gateway`. It uses Ktor's `MockEngine`
 * to simulate API responses, ensuring we can test our request/response handling
 * without making actual network calls. It validates success, API error, and
 * network failure scenarios.
 *
 * ---
 * ## Dependencies
 * - `io.ktor.client.engine.mock.MockEngine`
 * - `kotlinx.coroutines.test.runTest`
 *
 * @version 1.3
 * @since 2025-08-14
 */
class GatewayTest {

    private val testJson = JsonProvider.appJson

    private fun createMockGateway(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): Gateway {
        val mockEngine = MockEngine { request ->
            handler(this, request)
        }
        // Use the new internal constructor to inject the mock engine
        return Gateway(testJson, mockEngine)
    }

    // ... All @Test functions remain exactly the same ...
    @Test
    fun `listModels returns a list of models on success`() = runTest {
        // Arrange
        val mockResponse = """
            {
                "models": [
                    { "name": "models/gemini-pro", "version": "1.0.0", "displayName": "Gemini Pro" },
                    { "name": "models/gemini-1.5-pro-latest", "displayName": "Gemini 1.5 Pro" }
                ]
            }
        """.trimIndent()

        val gateway = createMockGateway {
            respond(
                content = mockResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        // Act
        val models = gateway.listModels("fake-api-key")

        // Assert
        assertEquals(2, models.size)
        assertEquals("models/gemini-pro", models[0].name)
        assertEquals("1.0.0", models[0].version)
        assertEquals(null, models[1].version)
    }

    @Test
    fun `listModels returns empty list on network error`() = runTest {
        // Arrange
        val gateway = createMockGateway {
            respondError(HttpStatusCode.InternalServerError)
        }

        // Act
        val models = gateway.listModels("fake-api-key")

        // Assert
        assertTrue(models.isEmpty())
    }

    @Test
    fun `generateContent returns parsed response on success`() = runTest {
        // Arrange
        val mockResponse = """
            {
                "candidates": [{"content": {"parts": [{"text": "Hello, world!"}], "role": "model"}}],
                "usageMetadata": {"promptTokenCount": 10, "candidatesTokenCount": 5, "totalTokenCount": 15}
            }
        """.trimIndent()
        val gateway = createMockGateway {
            respond(mockResponse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        // Act
        val response = gateway.generateContent("fake-key", "fake-model", emptyList())

        // Assert
        assertNotNull(response.candidates)
        assertEquals("Hello, world!", response.candidates.first().content.parts.first().text)
        assertEquals(15, response.usageMetadata?.totalTokenCount)
        assertEquals(null, response.error)
    }

    @Test
    fun `generateContent returns error object on API error`() = runTest {
        // Arrange
        val mockErrorResponse = """
            {"error": {"code": 400, "message": "API key not valid. Please pass a valid API key.", "status": "INVALID_ARGUMENT"}}
        """.trimIndent()
        val gateway = createMockGateway {
            respond(mockErrorResponse, HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        // Act
        val response = gateway.generateContent("fake-key", "fake-model", emptyList())

        // Assert
        assertNotNull(response.error)
        assertEquals(400, response.error.code)
        assertTrue(response.error.message.contains("API key not valid"))
        assertEquals(null, response.candidates)
    }

    @Test
    fun `generateContent returns client-side error on network failure`() = runTest {
        // Arrange
        val gateway = createMockGateway {
            throw Exception("Simulated network failure")
        }

        // Act
        val response = gateway.generateContent("fake-key", "fake-model", emptyList())

        // Assert
        assertNotNull(response.error)
        assertEquals(500, response.error.code)
        assertTrue(response.error.message.contains("client-side exception"))
    }
}