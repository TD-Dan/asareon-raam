package app.auf.feature.gateway

import app.auf.core.FeatureState
import kotlinx.serialization.Serializable

/**
 * The state for the GatewayFeature.
 *
 * Its sole responsibility is to act as the single source of truth for which generative
 * models are available to the application at any given time. This state is populated
 * by the GatewayFeature after successfully querying the APIs of its configured providers.
 */
@Serializable
data class GatewayState(
    /**
     * A map where the key is the provider's unique ID (e.g., "gemini") and the value
     * is a list of model name strings that the provider has reported as available.
     *
     * Example:
     * {
     *   "gemini": ["gemini-2.5-pro", "gemini-flash"],
     *   "openai": ["gpt-4o", "gpt-4-turbo"]
     * }
     */
    val availableModels: Map<String, List<String>> = emptyMap()
) : FeatureState