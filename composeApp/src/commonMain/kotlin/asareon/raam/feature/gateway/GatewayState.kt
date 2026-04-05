package asareon.raam.feature.gateway

import asareon.raam.core.FeatureState
import kotlinx.serialization.Serializable

/** Default max output tokens used when no user setting has been loaded yet. */
const val DEFAULT_MAX_OUTPUT_TOKENS = 16384

/** Settings key for the user-configurable max output tokens preference. */
const val MAX_OUTPUT_TOKENS_SETTING_KEY = "gateway.maxOutputTokens"

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
     * is a list of [ModelDescriptor] objects that the provider has reported as available.
     *
     * Each descriptor carries the model's ID and, when the provider's API exposes it,
     * the model's maximum output token limit. This allows the GatewayFeature to
     * clamp the user's configured max output tokens to the model's actual ceiling.
     *
     * Example:
     * {
     *   "anthropic": [
     *     ModelDescriptor("claude-3-5-sonnet-20241022", maxOutputTokens = 8192),
     *     ModelDescriptor("claude-3-opus-20240229", maxOutputTokens = 4096)
     *   ],
     *   "openai": [
     *     ModelDescriptor("gpt-4o", maxOutputTokens = null)
     *   ]
     * }
     */
    val availableModels: Map<String, List<ModelDescriptor>> = emptyMap(),

    /**
     * THE FIX: A map to store the API keys this feature needs, populated by listening
     * to events from the SettingsFeature. This enforces Absolute Decoupling.
     *
     * Example:
     * {
     *   "gateway.gemini.apiKey": "...",
     *   "gateway.openai.apiKey": "..."
     * }
     */
    val apiKeys: Map<String, String> = emptyMap(),

    /**
     * The user-configured maximum number of output tokens to request from providers.
     *
     * At generation time, GatewayFeature resolves the effective limit as:
     *   min(this value, model's known maxOutputTokens)
     * When the model's limit is unknown (null in its [ModelDescriptor]), this value
     * is used directly — the provider API will reject or clamp if it exceeds the
     * model's actual ceiling.
     *
     * Populated from the [MAX_OUTPUT_TOKENS_SETTING_KEY] setting.
     * Defaults to [DEFAULT_MAX_OUTPUT_TOKENS] (16,384).
     */
    val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS
) : FeatureState