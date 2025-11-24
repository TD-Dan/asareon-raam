package app.auf.feature.gateway

import app.auf.core.*
import app.auf.core.generated.ActionNames
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

open class GatewayFeature(
    private val platformDependencies: PlatformDependencies,
    private val coroutineScope: CoroutineScope,
    // All available providers are injected here at the composition root (e.g., in AppContainer).
    providers: List<UniversalGatewayProvider>
) : Feature {
    override val name: String = "gateway"

    // The feature's only internal, transient state is this private map of its plugins.
    private val providerMap = providers.associateBy { it.id }
    private val providerApiKeys = providers.map { "gateway.${it.id}.apiKey" }.toSet()
    private val json = Json { ignoreUnknownKeys = true }
    // private val prettyJson = Json { ignoreUnknownKeys = true; prettyPrint = true } // Removed: Provider handles formatting now

    // REFACTOR: A map to track active generation jobs. Now mutated ONLY via onAction.
    private val activeRequests = mutableMapOf<String, Job>()

    override fun onAction(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {
        val gatewayState = newState as? GatewayState ?: return
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
                    refreshProviderModels(providerId, gatewayState, store)
                }
            }

            ActionNames.SETTINGS_PUBLISH_VALUE_CHANGED -> {
                val key = action.payload?.get("key")?.jsonPrimitive?.content ?: return
                // If one of our known API keys changed, trigger a model refresh for that provider.
                if (key in providerApiKeys) {
                    val providerId = key.split('.')[1]
                    refreshProviderModels(providerId, gatewayState, store)
                }
            }

            ActionNames.GATEWAY_REQUEST_AVAILABLE_MODELS -> {
                val payload = Json.encodeToJsonElement(gatewayState.availableModels).jsonObject
                store.dispatch(this.name, Action(ActionNames.GATEWAY_PUBLISH_AVAILABLE_MODELS_UPDATED, payload))
            }

            ActionNames.GATEWAY_GENERATE_CONTENT -> {
                handleGenerateContent(action, gatewayState, store)
            }

            ActionNames.GATEWAY_PREPARE_PREVIEW -> {
                handlePreparePreview(action, gatewayState, store)
            }

            ActionNames.GATEWAY_CANCEL_REQUEST -> {
                handleCancelRequest(action)
            }

            // NEW: Safe concurrency handler
            ActionNames.GATEWAY_INTERNAL_REQUEST_COMPLETED -> {
                handleRequestCompleted(action)
            }
        }
    }

    private fun handleCancelRequest(action: Action) {
        val correlationId = action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull ?: return
        val job = activeRequests[correlationId]
        if (job != null) {
            platformDependencies.log(LogLevel.INFO, name, "Cancelling gateway request with correlationId: $correlationId")
            job.cancel()
            // We do NOT remove from map here. The job cancellation will trigger the completion handler,
            // which dispatches REQUEST_COMPLETED, which removes it safely.
        } else {
            platformDependencies.log(LogLevel.WARN, name, "Received CANCEL_REQUEST for unknown or completed correlationId: $correlationId")
        }
    }

    private fun handleRequestCompleted(action: Action) {
        val correlationId = action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull ?: return
        if (activeRequests.containsKey(correlationId)) {
            // platformDependencies.log(LogLevel.DEBUG, name, "Cleaning up completed request: $correlationId")
            activeRequests.remove(correlationId)
        }
    }

    private fun handleGenerateContent(action: Action, gatewayState: GatewayState, store: Store) {
        val payload = action.payload ?: return

        // SAFETY: Check for missing originator (Silent Drop Prevention)
        val originator = action.originator
        if (originator == null) {
            platformDependencies.log(LogLevel.ERROR, name, "DROPPED REQUEST: GENERATE_CONTENT received without an originator. Cannot reply.")
            return
        }

        val providerId = payload["providerId"]?.jsonPrimitive?.contentOrNull ?: return
        val modelName = payload["modelName"]?.jsonPrimitive?.contentOrNull ?: return
        val correlationId = payload["correlationId"]?.jsonPrimitive?.contentOrNull ?: return
        val systemPrompt = payload["systemPrompt"]?.jsonPrimitive?.contentOrNull

        val contents = payload["contents"]?.jsonArray?.let {
            json.decodeFromJsonElement<List<GatewayMessage>>(it)
        } ?: return

        val provider = providerMap[providerId]
        if (provider == null) {
            platformDependencies.log(LogLevel.ERROR, name, "GENERATE_CONTENT request for unknown provider '$providerId'. Ignoring.")
            return
        }

        // AUDIT: Log the request intention
        platformDependencies.log(LogLevel.INFO, name, "Generating content via $providerId ($modelName). CorrelationId: $correlationId. SystemPrompt: ${systemPrompt != null}")

        val job = coroutineScope.launch {
            val request = GatewayRequest(modelName, contents, correlationId, systemPrompt)
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
                type = ActionNames.Envelopes.GATEWAY_RESPONSE_RESPONSE, // Updated Name
                payload = responsePayload
            )
            store.deliverPrivateData(this@GatewayFeature.name, originator, envelope)
        }

        // CONCURRENCY: Register job and setup safe cleanup
        activeRequests[correlationId] = job
        job.invokeOnCompletion {
            // Dispatch internal action to mutate map on the main thread
            val cleanupPayload = buildJsonObject { put("correlationId", correlationId) }
            store.dispatch(this@GatewayFeature.name, Action(ActionNames.GATEWAY_INTERNAL_REQUEST_COMPLETED, cleanupPayload))
        }
    }

    private fun handlePreparePreview(action: Action, gatewayState: GatewayState, store: Store) {
        val payload = action.payload ?: return
        val originator = action.originator
        if (originator == null) {
            platformDependencies.log(LogLevel.ERROR, name, "DROPPED REQUEST: PREPARE_PREVIEW received without an originator.")
            return
        }

        val providerId = payload["providerId"]?.jsonPrimitive?.contentOrNull ?: return
        val modelName = payload["modelName"]?.jsonPrimitive?.contentOrNull ?: return
        val correlationId = payload["correlationId"]?.jsonPrimitive?.contentOrNull ?: return
        val systemPrompt = payload["systemPrompt"]?.jsonPrimitive?.contentOrNull
        val contents = payload["contents"]?.jsonArray?.let { json.decodeFromJsonElement<List<GatewayMessage>>(it) } ?: return

        val provider = providerMap[providerId]
        if (provider == null) {
            platformDependencies.log(LogLevel.ERROR, name, "PREPARE_PREVIEW request for unknown provider '$providerId'.")
            return
        }

        val agnosticRequest = GatewayRequest(modelName, contents, correlationId, systemPrompt)

        // ARCHITECTURE: Use the polymorphic interface (Provider is the Translator)
        // We launch a coroutine because generatePreview is suspendable
        coroutineScope.launch {
            val rawRequestJson = try {
                provider.generatePreview(agnosticRequest, gatewayState.apiKeys)
            } catch (e: Exception) {
                platformDependencies.log(LogLevel.ERROR, name, "Failed to generate preview: ${e.message}")
                "Error generating preview: ${e.message}"
            }

            val responsePayload = buildJsonObject {
                put("correlationId", correlationId)
                put("agnosticRequest", Json.encodeToJsonElement(agnosticRequest))
                put("rawRequestJson", rawRequestJson)
            }

            val envelope = PrivateDataEnvelope(
                type = ActionNames.Envelopes.GATEWAY_RESPONSE_PREVIEW, // Updated Name
                payload = responsePayload
            )
            store.deliverPrivateData(this@GatewayFeature.name, originator, envelope)
        }
    }


    private fun refreshProviderModels(providerId: String, gatewayState: GatewayState, store: Store) {
        val provider = providerMap[providerId] ?: return

        coroutineScope.launch {
            val models = provider.listAvailableModels(gatewayState.apiKeys)
            val payload = buildJsonObject {
                put("providerId", providerId)
                put("models", Json.encodeToJsonElement(models))
            }
            store.dispatch(this@GatewayFeature.name, Action(ActionNames.GATEWAY_INTERNAL_MODELS_UPDATED, payload))
        }
    }

    override fun reducer(state: FeatureState?, action: Action): FeatureState? {
        val currentFeatureState = state as? GatewayState ?: GatewayState()

        when (action.name) {
            ActionNames.GATEWAY_INTERNAL_MODELS_UPDATED -> {
                val payload = action.payload ?: return currentFeatureState
                val providerId = payload["providerId"]?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
                val models = Json.decodeFromJsonElement<List<String>>(payload["models"] ?: return currentFeatureState)
                val newModels = currentFeatureState.availableModels + (providerId to models)
                return currentFeatureState.copy(availableModels = newModels)
            }
            ActionNames.SETTINGS_PUBLISH_LOADED -> {
                val loadedValues = action.payload?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                val relevantKeys = loadedValues.filterKeys { it in providerApiKeys }
                if (relevantKeys.isNotEmpty()) {
                    return currentFeatureState.copy(apiKeys = currentFeatureState.apiKeys + relevantKeys)
                }
            }
            ActionNames.SETTINGS_PUBLISH_VALUE_CHANGED -> {
                val key = action.payload?.get("key")?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
                val value = action.payload["value"]?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
                if (key in providerApiKeys) {
                    return currentFeatureState.copy(apiKeys = currentFeatureState.apiKeys + (key to value))
                }
            }
        }
        return currentFeatureState
    }
}