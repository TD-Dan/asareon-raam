package asareon.raam.feature.gateway

import asareon.raam.core.*
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.util.LogLevel
import asareon.raam.util.PlatformDependencies
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
    override val identity: Identity = Identity(uuid = null, handle = "gateway", localHandle = "gateway", name="Gateway")

    // The feature's only internal, transient state is this private map of its plugins.
    private val providerMap = providers.associateBy { it.id }
    private val providerApiKeys = providers.map { "gateway.${it.id}.apiKey" }.toSet()
    private val json = Json { ignoreUnknownKeys = true }

    // REFACTOR: A map to track active generation jobs. Now mutated ONLY via onAction.
    private val activeRequests = mutableMapOf<String, Job>()

    override fun handleSideEffects(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {
        val gatewayState = newState as? GatewayState
        if (gatewayState == null) {
            // This should never happen in normal operation — it means the reducer returned
            // a non-GatewayState or null. Log at WARN to surface configuration issues.
            if (newState != null) {
                platformDependencies.log(LogLevel.WARN, identity.handle, "handleSideEffects received unexpected state type: ${newState::class.simpleName}. Skipping.")
            }
            return
        }
        when (action.name) {
            ActionRegistry.Names.SYSTEM_INITIALIZING -> {
                // Each provider registers its own settings, making the system extensible.
                providerMap.values.forEach { provider ->
                    provider.registerSettings { actionToDispatch -> store.deferredDispatch(identity.handle, actionToDispatch) }
                }
                // Register the global max output tokens setting.
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SETTINGS_ADD, buildJsonObject {
                    put("key", MAX_OUTPUT_TOKENS_SETTING_KEY)
                    put("type", "NUMERIC_LONG")
                    put("label", "Max Output Tokens")
                    put("description", "Maximum number of tokens the model can generate per response. " +
                            "If the selected model supports fewer tokens, the model's limit takes precedence.")
                    put("section", "Gateway")
                    put("defaultValue", DEFAULT_MAX_OUTPUT_TOKENS.toString())
                }))
            }

            ActionRegistry.Names.SYSTEM_RUNNING -> {
                // After settings are loaded, trigger an initial model refresh for all providers.
                providerMap.keys.forEach { providerId ->
                    refreshProviderModels(providerId, gatewayState, store)
                }
            }

            ActionRegistry.Names.SETTINGS_VALUE_CHANGED -> {
                val key = action.payload?.get("key")?.jsonPrimitive?.content
                if (key == null) {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "SETTINGS_VALUE_CHANGED received with missing 'key' field. Ignoring.")
                    return
                }
                // If one of our known API keys changed, trigger a model refresh for that provider.
                if (key in providerApiKeys) {
                    val providerId = key.split('.')[1]
                    refreshProviderModels(providerId, gatewayState, store)
                }
                // No side effect needed for maxOutputTokens changes — the reducer captures
                // it into state, and it takes effect on the next generation request.
            }

            ActionRegistry.Names.GATEWAY_REQUEST_AVAILABLE_MODELS -> {
                val payload = Json.encodeToJsonElement(gatewayState.availableModels).jsonObject
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.GATEWAY_AVAILABLE_MODELS_UPDATED, payload))
            }

            ActionRegistry.Names.GATEWAY_GENERATE_CONTENT -> {
                handleGenerateContent(action, gatewayState, store)
            }

            ActionRegistry.Names.GATEWAY_PREPARE_PREVIEW -> {
                handlePreparePreview(action, gatewayState, store)
            }

            ActionRegistry.Names.GATEWAY_CANCEL_REQUEST -> {
                handleCancelRequest(action)
            }

            // NEW: Safe concurrency handler
            ActionRegistry.Names.GATEWAY_REQUEST_COMPLETED -> {
                handleRequestCompleted(action)
            }
        }
    }

    /**
     * Resolves the effective max output tokens for a given provider and model.
     *
     * Uses min(user setting, model's known maximum) when the model exposes its limit
     * via the provider's models API (Anthropic, Gemini). Falls back to the user setting
     * alone when the model's limit is unknown (OpenAI, Inception).
     */
    private fun resolveMaxOutputTokens(
        providerId: String,
        modelName: String,
        gatewayState: GatewayState
    ): Int {
        val modelDescriptor = gatewayState.availableModels[providerId]?.find { it.id == modelName }
        val modelMax = modelDescriptor?.maxOutputTokens
        val settingMax = gatewayState.maxOutputTokens
        return if (modelMax != null) minOf(settingMax, modelMax) else settingMax
    }

    private fun handleCancelRequest(action: Action) {
        val correlationId = action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull
        if (correlationId == null) {
            platformDependencies.log(LogLevel.WARN, identity.handle, "CANCEL_REQUEST received with missing or null correlationId. Ignoring.")
            return
        }
        val job = activeRequests[correlationId]
        if (job != null) {
            platformDependencies.log(LogLevel.INFO, identity.handle, "Cancelling gateway request with correlationId: $correlationId")
            job.cancel()
            // We do NOT remove from map here. The job cancellation will trigger the completion handler,
            // which dispatches REQUEST_COMPLETED, which removes it safely.
        } else {
            platformDependencies.log(LogLevel.WARN, identity.handle, "Received CANCEL_REQUEST for unknown or completed correlationId: $correlationId")
        }
    }

    private fun handleRequestCompleted(action: Action) {
        val correlationId = action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull
        if (correlationId == null) {
            platformDependencies.log(LogLevel.WARN, identity.handle, "REQUEST_COMPLETED received with missing or null correlationId. Ignoring.")
            return
        }
        if (activeRequests.containsKey(correlationId)) {
            activeRequests.remove(correlationId)
        }
    }

    private fun handleGenerateContent(action: Action, gatewayState: GatewayState, store: Store) {
        val payload = action.payload
        val originator = action.originator
        val providerId = payload?.get("providerId")?.jsonPrimitive?.contentOrNull
        val modelName = payload?.get("modelName")?.jsonPrimitive?.contentOrNull
        val correlationId = payload?.get("correlationId")?.jsonPrimitive?.contentOrNull
        val systemPrompt = payload?.get("systemPrompt")?.jsonPrimitive?.contentOrNull

        val contents = try {
            payload?.get("contents")?.jsonArray?.let {
                json.decodeFromJsonElement<List<GatewayMessage>>(it)
            }
        } catch (e: Exception) {
            platformDependencies.log(
                LogLevel.ERROR, identity.handle,
                "DROPPED GENERATE_CONTENT: failed to deserialize 'contents' field. Error: ${e.message}"
            )
            null
        }

        if (payload == null || originator == null || providerId == null ||
            modelName == null || correlationId == null || contents == null) {
            platformDependencies.log(
                LogLevel.ERROR, identity.handle,
                "DROPPED GENERATE_CONTENT: malformed or incomplete payload. " +
                        "payload=${payload != null}, originator=$originator, providerId=$providerId, " +
                        "modelName=$modelName, correlationId=$correlationId, contentsPresent=${contents != null}"
            )
            return
        }

        val provider = providerMap[providerId]
        if (provider == null) {
            platformDependencies.log(LogLevel.ERROR, identity.handle, "GENERATE_CONTENT request for unknown provider '$providerId'. Ignoring.")
            return
        }

        // GUARD: Reject duplicate in-flight requests for the same correlationId.
        // This is a defence-in-depth check — the primary fix lives in AgentCognitivePipeline.
        // A duplicate here indicates a race condition upstream (e.g. the context-gathering
        // timeout firing after executeTurn has already been dispatched).
        if (activeRequests.containsKey(correlationId)) {
            platformDependencies.log(
                LogLevel.ERROR, identity.handle,
                "DROPPED GENERATE_CONTENT: correlationId '$correlationId' is already in-flight. " +
                        "This indicates a duplicate dispatch from the caller — likely a context-gathering " +
                        "timeout race. Dropping to prevent double API call and duplicate response."
            )
            return
        }

        // Resolve effective max output tokens from user setting and model metadata.
        val effectiveMaxTokens = resolveMaxOutputTokens(providerId, modelName, gatewayState)

        // AUDIT: Log the request intention
        platformDependencies.log(LogLevel.INFO, identity.handle, "Generating content via $providerId ($modelName). CorrelationId: $correlationId. SystemPrompt: ${systemPrompt != null}. MaxOutputTokens: $effectiveMaxTokens")

        val job = coroutineScope.launch {
            val request = GatewayRequest(modelName, contents, correlationId, systemPrompt, effectiveMaxTokens)
            val response = provider.generateContent(request, gatewayState.apiKeys)

            // Log token usage if available
            if (response.inputTokens != null || response.outputTokens != null) {
                platformDependencies.log(
                    LogLevel.INFO, identity.handle,
                    "Token usage for $correlationId: input=${response.inputTokens ?: "N/A"}, output=${response.outputTokens ?: "N/A"}"
                )
            }

            val responsePayload = try {
                Json.encodeToJsonElement(response).jsonObject
            } catch (e: Exception) {
                platformDependencies.log(
                    LogLevel.FATAL,
                    identity.handle,
                    "CRITICAL: Failed to serialize GatewayResponse for originator '$originator'. This is a contract violation. Error: ${e.message}"
                )
                val errorResponse = GatewayResponse(
                    rawContent = null,
                    errorMessage = "FATAL: GatewayFeature failed to serialize its own response.",
                    correlationId = correlationId
                )
                Json.encodeToJsonElement(errorResponse).jsonObject
            }

            store.deferredDispatch(this@GatewayFeature.identity.handle, Action(


                name = ActionRegistry.Names.GATEWAY_RETURN_RESPONSE,


                payload = responsePayload,


                targetRecipient = originator


            ))
        }

        // CONCURRENCY: Register job and setup safe cleanup
        activeRequests[correlationId] = job
        job.invokeOnCompletion {
            // Dispatch internal action to mutate map on the main thread
            val cleanupPayload = buildJsonObject { put("correlationId", correlationId) }
            store.dispatch(this@GatewayFeature.identity.handle, Action(ActionRegistry.Names.GATEWAY_REQUEST_COMPLETED, cleanupPayload))
        }
    }

    private fun handlePreparePreview(action: Action, gatewayState: GatewayState, store: Store) {
        val payload = action.payload
        val originator = action.originator
        val providerId = payload?.get("providerId")?.jsonPrimitive?.contentOrNull
        val modelName = payload?.get("modelName")?.jsonPrimitive?.contentOrNull
        val correlationId = payload?.get("correlationId")?.jsonPrimitive?.contentOrNull
        val systemPrompt = payload?.get("systemPrompt")?.jsonPrimitive?.contentOrNull

        val contents = try {
            payload?.get("contents")?.jsonArray?.let {
                json.decodeFromJsonElement<List<GatewayMessage>>(it)
            }
        } catch (e: Exception) {
            platformDependencies.log(
                LogLevel.ERROR, identity.handle,
                "DROPPED PREPARE_PREVIEW: failed to deserialize 'contents' field. Error: ${e.message}"
            )
            null
        }

        if (payload == null || originator == null || providerId == null ||
            modelName == null || correlationId == null || contents == null) {
            platformDependencies.log(
                LogLevel.ERROR, identity.handle,
                "DROPPED PREPARE_PREVIEW: malformed or incomplete payload. " +
                        "payload=${payload != null}, originator=$originator, providerId=$providerId, " +
                        "modelName=$modelName, correlationId=$correlationId, contentsPresent=${contents != null}"
            )
            return
        }

        val provider = providerMap[providerId]
        if (provider == null) {
            platformDependencies.log(LogLevel.ERROR, identity.handle, "PREPARE_PREVIEW request for unknown provider '$providerId'.")
            return
        }

        // Resolve effective max output tokens so the preview reflects the actual value.
        val effectiveMaxTokens = resolveMaxOutputTokens(providerId, modelName, gatewayState)
        val agnosticRequest = GatewayRequest(modelName, contents, correlationId, systemPrompt, effectiveMaxTokens)

        // ARCHITECTURE: Use the polymorphic interface (Provider is the Translator)
        // We launch a coroutine because generatePreview is suspendable
        coroutineScope.launch {
            val rawRequestJson = try {
                provider.generatePreview(agnosticRequest, gatewayState.apiKeys)
            } catch (e: Exception) {
                platformDependencies.log(LogLevel.ERROR, identity.handle, "Failed to generate preview: ${e.message}")
                "Error generating preview: ${e.message}"
            }

            // NEW: Attempt token counting if the provider supports it
            val tokenEstimate = try {
                provider.countTokens(agnosticRequest, gatewayState.apiKeys)
            } catch (e: Exception) {
                platformDependencies.log(LogLevel.WARN, identity.handle, "Token counting failed for preview ($providerId): ${e.message}")
                null
            }

            val responsePayload = buildJsonObject {
                put("correlationId", correlationId)
                put("agnosticRequest", Json.encodeToJsonElement(agnosticRequest))
                put("rawRequestJson", rawRequestJson)
                // Include token estimate if available
                tokenEstimate?.let {
                    put("estimatedInputTokens", it.inputTokens)
                }
            }

            store.deferredDispatch(this@GatewayFeature.identity.handle, Action(


                name = ActionRegistry.Names.GATEWAY_RETURN_PREVIEW,


                payload = responsePayload,


                targetRecipient = originator


            ))
        }
    }


    private fun refreshProviderModels(providerId: String, gatewayState: GatewayState, store: Store) {
        val provider = providerMap[providerId]
        if (provider == null) {
            platformDependencies.log(LogLevel.WARN, identity.handle, "refreshProviderModels called for unknown provider '$providerId'. Ignoring.")
            return
        }

        coroutineScope.launch {
            val models = provider.listAvailableModels(gatewayState.apiKeys)
            val payload = buildJsonObject {
                put("providerId", providerId)
                put("models", Json.encodeToJsonElement(models))
            }
            store.dispatch(this@GatewayFeature.identity.handle, Action(ActionRegistry.Names.GATEWAY_MODELS_UPDATED, payload))
        }
    }

    override fun reducer(state: FeatureState?, action: Action): FeatureState? {
        val currentFeatureState = state as? GatewayState ?: GatewayState()

        when (action.name) {
            ActionRegistry.Names.GATEWAY_MODELS_UPDATED -> {
                val payload = action.payload ?: return currentFeatureState
                val providerId = payload["providerId"]?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
                val models = Json.decodeFromJsonElement<List<ModelDescriptor>>(payload["models"] ?: return currentFeatureState)
                // If the provider returned no models (e.g. API key is blank or the call failed),
                // remove it from the map entirely so it never surfaces in the UI.
                val newModels = if (models.isEmpty()) {
                    currentFeatureState.availableModels - providerId
                } else {
                    currentFeatureState.availableModels + (providerId to models)
                }
                return currentFeatureState.copy(availableModels = newModels)
            }
            ActionRegistry.Names.SETTINGS_LOADED -> {
                val loadedValues = action.payload?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                val relevantApiKeys = loadedValues.filterKeys { it in providerApiKeys }
                val maxTokensValue = loadedValues[MAX_OUTPUT_TOKENS_SETTING_KEY]?.toIntOrNull()

                var newState = currentFeatureState
                if (relevantApiKeys.isNotEmpty()) {
                    newState = newState.copy(apiKeys = newState.apiKeys + relevantApiKeys)
                }
                if (maxTokensValue != null && maxTokensValue > 0) {
                    newState = newState.copy(maxOutputTokens = maxTokensValue)
                }
                return if (newState != currentFeatureState) newState else currentFeatureState
            }
            ActionRegistry.Names.SETTINGS_VALUE_CHANGED -> {
                val key = action.payload?.get("key")?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
                val value = action.payload["value"]?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
                if (key in providerApiKeys) {
                    return currentFeatureState.copy(apiKeys = currentFeatureState.apiKeys + (key to value))
                }
                if (key == MAX_OUTPUT_TOKENS_SETTING_KEY) {
                    val maxTokens = value.toIntOrNull()
                    if (maxTokens != null && maxTokens > 0) {
                        return currentFeatureState.copy(maxOutputTokens = maxTokens)
                    }
                }
            }
        }
        return currentFeatureState
    }
}