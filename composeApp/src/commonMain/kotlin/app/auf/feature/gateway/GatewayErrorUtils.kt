package app.auf.feature.gateway

/**
 * Maps a raw Exception from a network client to a more user-friendly message.
 * This is a key part of the Gateway's abstraction, preventing low-level network
 * errors from leaking into the UI.
 *
 * @param e The exception caught during the API call.
 * @return A user-friendly error string.
 */
internal fun mapExceptionToUserMessage(e: Exception): String {
    val message = e.message ?: e.toString()
    val causeMessage = e.cause?.message ?: ""

    return when {
        "refused" in message || "refused" in causeMessage ->
            "Network error: Connection refused. The service might be down or blocked."
        "timeout" in message.lowercase() || "timeout" in causeMessage.lowercase() ->
            "Network error: The connection timed out. Please check your internet connection."
        "unresolved" in message || "unknown host" in message.lowercase() ->
            "Network error: Could not resolve the host. Please check your internet connection and DNS settings."
        else ->
            "A client-side exception occurred: ${e.message}"
    }
}