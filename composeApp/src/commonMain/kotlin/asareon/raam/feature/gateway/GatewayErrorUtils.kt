package asareon.raam.feature.gateway

import io.ktor.client.network.sockets.*
import io.ktor.client.plugins.*
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Maps a raw Exception from a network client to a more user-friendly message.
 *
 * REFACTOR (Phase 2 - Corrected): Uses Ktor's common exception types for timeouts,
 * but falls back to message inspection for connection/DNS errors as KMP common
 * does not unify ConnectException/UnknownHostException.
 *
 * @param e The exception caught during the API call.
 * @return A user-friendly error string.
 */
internal fun mapExceptionToUserMessage(e: Exception): String {
    // Note: The caller is responsible for logging the full stack trace for the developer.

    return when (e) {
        // Common Ktor/Coroutine Timeouts (These are strictly typed in commonMain)
        is SocketTimeoutException,
        is HttpRequestTimeoutException,
        is ConnectTimeoutException,
        is TimeoutCancellationException ->
            "Network error: The connection timed out. (Check logs for details)"

        // Fallback for platform-specifics (Connection Refused, DNS)
        else -> {
            // We must use lowercase message inspection because ConnectException/UnknownHostException
            // are platform-specific and not visible in commonMain.
            val message = e.message?.lowercase().orEmpty()
            when {
                "refused" in message ->
                    "Network error: Connection refused. The service might be down or blocked. (Check logs for details)"
                // ADDED: "resolve host" to catch 'Unable to resolve host' messages
                "unresolved" in message || "unknown host" in message || "nodename" in message || "resolve host" in message ->
                    "Network error: Could not resolve the host. Please check your internet connection and DNS settings. (Check logs for details)"
                else ->
                    "A client-side exception occurred: ${e.message ?: e::class.simpleName} (Check logs for details)"
            }
        }
    }
}