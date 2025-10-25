package app.auf.feature.gateway

import app.auf.core.*
import app.auf.core.generated.ActionNames
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

class GatewayFeature(
    private val platformDependencies: PlatformDependencies,
    private val coroutineScope: CoroutineScope,
    // All available providers are injected here at the composition root (e.g., in AppContainer).
    providers: List<AgentGatewayProvider>
) : Feature {
    override val name: String = "gateway"

    // The feature's only internal, transient state is this private map of its plugins.
    private val providerMap = providers.associateBy { it.id }
    private val providerApiKeys = providers.map { "gateway.${it.id}.apiKey" }.toSet()
    private val json = Json { ignoreUnknownKeys = true }

    private val activeRequests = mutableMapOf<String, Job>()

    override fun onAction(action: Action, store: Store, previousState: AppState) {
        when (action.name) {
            ActionNames.SYSTEM_PUBLISH_INITIALIZING -> {
                // Each provider registers its own settings, making the system extensible.
                providerMap.values.forEach { provider ->
                    provider.registerSettings { actionToDispatch -> store.dispatch(this.name, actionToDispatch) }
                }
            }

            ActionNames.SYSTEM_PUBLISH_STARTING -> {
                // After settings are loaded, trigger an initial model refresh for all providers.
                providerMap.keys.forEach { providerId ->
                    refreshProviderModels(providerId, store)
                }
            }

            ActionNames.SETTINGS_PUBLISH_VALUE_CHANGED -> {
                val key = action.payload?.get("key")?.jsonPrimitive?.content ?: return
                // If one of our known API keys changed, trigger a model refresh for that provider.
                if (key in providerApiKeys) {
                    val providerId = key.split('.')[1]
                    refreshProviderModels(providerId, store)
                }
            }

            ActionNames.GATEWAY_REQUEST_AVAILABLE_MODELS -> {
                val gatewayState = store.state.value.featureStates[name] as? GatewayState ?: return
                val payload = Json.encodeToJsonElement(gatewayState.availableModels).jsonObject
                store.dispatch(this.name, Action(ActionNames.GATEWAY_PUBLISH_AVAILABLE_MODELS_UPDATED, payload))
            }

            ActionNames.GATEWAY_GENERATE_CONTENT -> {
                handleGenerateContent(action, store)
            }

            ActionNames.GATEWAY_CANCEL_REQUEST -> {
                handleCancelRequest(action)
            }
        }
    }

    private fun handleCancelRequest(action: Action) {
        val correlationId = action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull ?: return
        val job = activeRequests[correlationId]
        if (job != null) {
            platformDependencies.log(LogLevel.INFO, name, "Cancelling gateway request with correlationId: $correlationId")
            job.cancel()
            activeRequests.remove(correlationId) // Proactive removal, though invokeOnCompletion also handles it.
        } else {
            platformDependencies.log(LogLevel.WARN, name, "Received CANCEL_REQUEST for unknown or completed correlationId: $correlationId")
        }
    }

    private fun handleGenerateContent(action: Action, store: Store) {
        val payload = action.payload ?: return
        val originator = action.originator ?: return
        val providerId = payload["providerId"]?.jsonPrimitive?.contentOrNull ?: return
        val modelName = payload["modelName"]?.jsonPrimitive?.contentOrNull ?: return
        val correlationId = payload["correlationId"]?.jsonPrimitive?.contentOrNull ?: return

        val contents = payload["contents"]?.jsonArray?.let {
            json.decodeFromJsonElement<List<GatewayMessage>>(it)
        } ?: return

        val provider = providerMap[providerId]
        if (provider == null) {
            platformDependencies.log(LogLevel.ERROR, name, "GENERATE_CONTENT request for unknown provider '$providerId'. Ignoring.")
            return
        }

        val gatewayState = store.state.value.featureStates[name] as? GatewayState ?: return

        val job = coroutineScope.launch {
            val request = GatewayRequest(modelName, contents, correlationId)
            val response = provider.generateContent(request, gatewayState.apiKeys)

            val responsePayload = try {
                Json.encodeToJsonElement(response).jsonObject
            } catch (e: Exception) {
                platformDependencies.log(
                    LogLevel.FATAL,
                    name,
                    "CRITICAL: Failed to serialize GatewayResponse for originator '$originator'. This is a contract violation. Error: ${e.message}"
                )
                val errorResponse = GatewayResponse(
                    rawContent = null,
                    errorMessage = "FATAL: GatewayFeature failed to serialize its own response.",
                    correlationId = correlationId
                )
                Json.encodeToJsonElement(errorResponse).jsonObject
            }

            val envelope = PrivateDataEnvelope(
                type = ActionNames.Envelopes.GATEWAY_RESPONSE,
                payload = responsePayload
            )
            store.deliverPrivateData(this@GatewayFeature.name, originator, envelope)
        }
        activeRequests[correlationId] = job
        job.invokeOnCompletion { activeRequests.remove(correlationId) }
    }

    private fun refreshProviderModels(providerId: String, store: Store) {
        val provider = providerMap[providerId] ?: return
        val gatewayState = store.state.value.featureStates[name] as? GatewayState ?: return

        coroutineScope.launch {
            val models = provider.listAvailableModels(gatewayState.apiKeys)
            val payload = buildJsonObject {
                put("providerId", providerId)
                put("models", Json.encodeToJsonElement(models))
            }
            store.dispatch(this@GatewayFeature.name, Action(ActionNames.GATEWAY_INTERNAL_MODELS_UPDATED, payload))
        }
    }

    override fun reducer(state: AppState, action: Action): AppState {
        val currentFeatureState = state.featureStates[name] as? GatewayState ?: GatewayState()
        var newFeatureState: GatewayState? = null

        when (action.name) {
            ActionNames.GATEWAY_INTERNAL_MODELS_UPDATED -> {
                val payload = action.payload ?: return state
                val providerId = payload["providerId"]?.jsonPrimitive?.contentOrNull ?: return state
                val models = Json.decodeFromJsonElement<List<String>>(payload["models"] ?: return state)
                val newModels = currentFeatureState.availableModels + (providerId to models)
                newFeatureState = currentFeatureState.copy(availableModels = newModels)
            }
            ActionNames.SETTINGS_PUBLISH_LOADED -> {
                val loadedValues = action.payload?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                val relevantKeys = loadedValues.filterKeys { it in providerApiKeys }
                if (relevantKeys.isNotEmpty()) {
                    newFeatureState = currentFeatureState.copy(apiKeys = currentFeatureState.apiKeys + relevantKeys)
                }
            }
            ActionNames.SETTINGS_PUBLISH_VALUE_CHANGED -> {
                val key = action.payload?.get("key")?.jsonPrimitive?.contentOrNull ?: return state
                val value = action.payload["value"]?.jsonPrimitive?.contentOrNull ?: return state
                if (key in providerApiKeys) {
                    newFeatureState = currentFeatureState.copy(apiKeys = currentFeatureState.apiKeys + (key to value))
                }
            }
        }
        return newFeatureState?.let {
            if (it != currentFeatureState) state.copy(featureStates = state.featureStates + (name to it)) else state
        } ?: state
    }
}