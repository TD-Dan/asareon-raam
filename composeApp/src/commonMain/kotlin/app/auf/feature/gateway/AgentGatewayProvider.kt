package app.auf.feature.gateway

import app.auf.core.Action
import kotlinx.serialization.json.JsonArray

/**
 * A generic, provider-agnostic request for content generation.
 * This is the data model used on the main application action bus.
 */
data class GatewayRequest(
    val modelName: String,
    // CORRECTED: The universal prompt format is a JSON Array, which is the
    // common structure for multi-turn chat conversations.
    val contents: JsonArray,
    val correlationId: String
)

/**
 * A generic, provider-agnostic response from a content generation call.
 * This is the data model delivered back to the caller via the private channel.
 */
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