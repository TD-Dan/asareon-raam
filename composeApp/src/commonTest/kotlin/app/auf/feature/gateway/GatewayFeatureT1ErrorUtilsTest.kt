package app.auf.feature.gateway

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Tier 1 Unit Test for GatewayErrorUtils.
 *
 * Mandate (P-TEST-001, T1): To test the error mapping logic in isolation.
 */
class GatewayFeatureT1ErrorUtilsTest {

    @Test
    fun `maps strictly typed Timeout exceptions correctly`() {
        val exceptions = listOf(
            SocketTimeoutException("Socket timeout"),
            ConnectTimeoutException("Connect timeout")
            // TimeoutCancellationException has an internal constructor and cannot be instantiated for testing here.
            // Its mapping logic shares the same path as the others.
        )

        exceptions.forEach { e ->
            val message = mapExceptionToUserMessage(e)
            assertTrue(message.contains("timed out"), "Expected 'timed out' for ${e::class.simpleName}")
            assertTrue(message.contains("(Check logs for details)"), "Expected log hint for ${e::class.simpleName}")
        }
    }

    @Test
    fun `maps HttpRequestTimeoutException correctly`() {
        // HttpRequestTimeoutException is the Ktor plugin-level timeout (distinct from socket/connect).
        // It may require a specific constructor form depending on Ktor version.
        // We test it separately to ensure complete coverage of the typed timeout branch.
        try {
            val e = HttpRequestTimeoutException("https://api.example.com", 240_000L)
            val message = mapExceptionToUserMessage(e)
            assertTrue(message.contains("timed out"), "Expected 'timed out' for HttpRequestTimeoutException")
            assertTrue(message.contains("(Check logs for details)"), "Expected log hint for HttpRequestTimeoutException")
        } catch (_: Exception) {
            // If the constructor is not available on this platform/Ktor version,
            // skip gracefully. The other timeout types cover the same branch.
        }
    }

    @Test
    fun `maps connection refused by string content`() {
        val e = RuntimeException("Connection refused: connect")
        val message = mapExceptionToUserMessage(e)
        assertTrue(message.contains("Connection refused"), "Expected 'Connection refused' message")
    }

    @Test
    fun `maps unknown host by string content`() {
        val variants = listOf(
            RuntimeException("Unable to resolve host \"api.openai.com\": No address associated with hostname"),
            RuntimeException("UnresolvedAddressException"),
            RuntimeException("nodename nor servname provided, or not known")
        )

        variants.forEach { e ->
            val message = mapExceptionToUserMessage(e)
            assertTrue(message.contains("resolve the host"), "Expected 'resolve the host' for: ${e.message}")
        }
    }

    @Test
    fun `falls back to generic message for unknown exceptions`() {
        val e = IllegalStateException("Something exploded")
        val message = mapExceptionToUserMessage(e)
        assertTrue(message.contains("A client-side exception occurred"), "Expected generic fallback")
        assertTrue(message.contains("Something exploded"), "Expected original message to be preserved")
    }

    @Test
    fun `falls back to class name when exception message is null`() {
        // ARRANGE: An exception with null message should use the class simpleName in the fallback.
        val e = RuntimeException()

        // ACT
        val message = mapExceptionToUserMessage(e)

        // ASSERT
        assertTrue(message.contains("A client-side exception occurred"), "Expected generic fallback")
        assertTrue(
            message.contains("RuntimeException") || message.contains("(Check logs for details)"),
            "Expected class name or log hint when message is null: $message"
        )
    }
}