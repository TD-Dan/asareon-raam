package app.auf.feature.gateway

import app.auf.core.*
import app.auf.feature.settings.SettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

class GatewayFeature(
    private val coroutineScope: CoroutineScope,
    // All available providers are injected here at the composition root (e.g., in AppContainer).
    providers: List<AgentGatewayProvider>
) : Feature {
    override val name: String = "gateway"

    // The feature's only internal, transient state is this private map of its plugins.
    private val providerMap = providers.associateBy { it.id }
    private val providerApiKeys = providers.map { "gateway.${it.id}.apiKey" }.toSet()
    private val json = Json { ignoreUnknownKeys = true }

    override fun onAction(action: Action, store: Store) {
        when (action.name) {
            "system.INITIALIZING" -> {
                // Each provider registers its own settings, making the system extensible.
                providerMap.values.forEach { provider ->
                    provider.registerSettings { actionToDispatch -> store.dispatch(this.name, actionToDispatch) }
                }
            }

            "system.STARTING" -> {
                // After settings are loaded, trigger an initial model refresh for all providers.
                providerMap.keys.forEach { providerId ->
                    refreshProviderModels(providerId, store)
                }
            }

            "settings.VALUE_CHANGED" -> {
                val key = action.payload?.get("key")?.jsonPrimitive?.content ?: return
                // If one of our known API keys changed, trigger a model refresh for that provider.
                if (key in providerApiKeys) {
                    val providerId = key.split('.')[1]
                    refreshProviderModels(providerId, store)
                }
            }

            "gateway.REQUEST_AVAILABLE_MODELS" -> {
                val gatewayState = store.state.value.featureStates[name] as? GatewayState ?: return
                val payload = Json.encodeToJsonElement(gatewayState.availableModels).jsonObject
                store.dispatch(this.name, Action("gateway.AVAILABLE_MODELS_UPDATED", payload))
            }

            "gateway.GENERATE_CONTENT" -> {
                handleGenerateContent(action, store)
            }
        }
    }

    private fun handleGenerateContent(action: Action, store: Store) {
        val payload = action.payload ?: return
        val originator = action.originator ?: return
        val providerId = payload["providerId"]?.jsonPrimitive?.contentOrNull ?: return
        val modelName = payload["modelName"]?.jsonPrimitive?.contentOrNull ?: return
        val correlationId = payload["correlationId"]?.jsonPrimitive?.contentOrNull ?: return

        // CORRECTED: Decode the payload into our type-safe, universal list of messages.
        val contents = payload["contents"]?.jsonArray?.let {
            json.decodeFromJsonElement<List<GatewayMessage>>(it)
        } ?: return

        val provider = providerMap[providerId] ?: return // Silently ignore unknown providers
        val settingsState = store.state.value.featureStates["settings"] as? SettingsState ?: return

        coroutineScope.launch {
            val request = GatewayRequest(modelName, contents, correlationId)
            // Delegate the actual work to the specific provider plugin.
            val response = provider.generateContent(request, settingsState.values)
            // Securely deliver the response directly to the original requester.
            store.deliverPrivateData(this@GatewayFeature.name, originator, response)
        }
    }

    private fun refreshProviderModels(providerId: String, store: Store) {
        val provider = providerMap[providerId] ?: return
        val settingsState = store.state.value.featureStates["settings"] as? SettingsState ?: return

        coroutineScope.launch {
            val models = provider.listAvailableModels(settingsState.values)
            val payload = buildJsonObject {
                put("providerId", providerId)
                put("models", Json.encodeToJsonElement(models))
            }
            store.dispatch(this@GatewayFeature.name, Action("gateway.internal.MODELS_UPDATED", payload))
        }
    }

    override fun reducer(state: AppState, action: Action): AppState {
        val currentFeatureState = state.featureStates[name] as? GatewayState ?: GatewayState()

        return when (action.name) {
            "gateway.internal.MODELS_UPDATED" -> {
                val payload = action.payload ?: return state
                val providerId = payload["providerId"]?.jsonPrimitive?.contentOrNull ?: return state
                val models = Json.decodeFromJsonElement<List<String>>(payload["models"] ?: return state)

                val newModels = currentFeatureState.availableModels + (providerId to models)
                val newFeatureState = currentFeatureState.copy(availableModels = newModels)

                state.copy(featureStates = state.featureStates + (name to newFeatureState))
            }
            else -> state
        }
    }
}