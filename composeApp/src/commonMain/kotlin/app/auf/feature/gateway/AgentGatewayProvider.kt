package app.auf.feature.gateway

import app.auf.core.Action
import kotlinx.serialization.Serializable


// --- Internal Data Contracts for Gateway Feature ---
// These are defined here, privately, to deserialize the JSON payload received from clients.
@Serializable
data class GatewayMessage(
    val role: String,
    val content: String,
    // NEW: Enriched with sender identity
    val senderId: String,
    val senderName: String
)
@Serializable
data class GatewayRequest(
    val modelName: String,
    val contents: List<GatewayMessage>,
    val correlationId: String
)

/**
 * A generic, provider-agnostic response from a content generation call.
 * This is the data model delivered back to the caller via the private channel.
 */
@Serializable
data class GatewayResponse(
    val rawContent: String?,
    val errorMessage: String?,
    val correlationId: String
)

/**
 * The universal contract for any class that provides access to a generative AI service.
 * Each implementation is responsible for its own API-specific logic and data models.
 */
interface AgentGatewayProvider {
    /** A unique, machine-readable ID for this provider (e.g., "gemini", "openai"). */
    val id: String

    /**
     * Registers the settings this provider needs (e.g., API key) with the SettingsFeature.
     * This is called once at application startup by the GatewayFeature.
     * @param dispatch A function to dispatch an action to the store.
     */
    fun registerSettings(dispatch: (Action) -> Unit)

    /**
     * The core generation method. It receives a generic request and the current settings state,
     * performs the network call, and returns a generic response.
     * @param request The generic request data.
     * @param settings A map of all current application settings. The provider should look up its own keys.
     * @return A generic response object.
     */
    suspend fun generateContent(request: GatewayRequest, settings: Map<String, String>): GatewayResponse

    /**
     * Fetches the list of available model names from the provider's API.
     * @param settings A map of all current application settings.
     * @return A list of model name strings, or an empty list on failure.
     */
    suspend fun listAvailableModels(settings: Map<String, String>): List<String>
}