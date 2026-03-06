package app.auf.feature.gateway.inception

import app.auf.core.Action
import app.auf.core.generated.ActionRegistry
import app.auf.feature.gateway.* // Allowed: this is inter-feature import
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.coroutines.cancellation.CancellationException

// --- Data Contracts specific to the Inception API ---
// The Inception API is mostly OpenAI-compatible, but its error field is inconsistent:
// success/structured errors use {"error": {"message": "..."}}, while auth failures
// return a bare string: {"error": "Incorrect API key provided"}.
// We deserialize `error` as a raw JsonElement and normalize it in parseResponse.
@Serializable
private data class InceptionChatResponse(
    val choices: List<Choice>? = null,
    val usage: InceptionUsage? = null,
    val error: JsonElement? = null  // May be JsonPrimitive (string) or JsonObject
)

@Serializable
private data class Choice(
    val message: InceptionMessage? = null,
    // Inception streams deltas, but for non-streaming calls `message` is used.
    val delta: InceptionMessage? = null
)

@Serializable
private data class InceptionMessage(
    val role: String? = null,
    val content: String? = null
)

@Serializable
private data class InceptionUsage(
    // OpenAI-compatible snake_case field names
    @kotlinx.serialization.SerialName("prompt_tokens")
    val promptTokens: Int? = null,
    @kotlinx.serialization.SerialName("completion_tokens")
    val completionTokens: Int? = null,
    @kotlinx.serialization.SerialName("total_tokens")
    val totalTokens: Int? = null
)

@Serializable
private data class ListModelsResponse(
    val data: List<ModelInfo> = emptyList(),
    val error: JsonElement? = null  // Same mixed-type error field
)

@Serializable
private data class ModelInfo(val id: String)

/**
 * Normalizes Inception's mixed error field into a plain string.
 *
 * The API returns one of two shapes depending on the error category:
 *   - Auth errors:       {"error": "Incorrect API key provided"}
 *   - Structured errors: {"error": {"message": "...", "type": "...", "code": "..."}}
 */
private fun JsonElement.extractErrorMessage(): String =
    when (this) {
        is JsonPrimitive -> content                           // bare string
        is JsonObject    -> this["message"]?.jsonPrimitive?.content
            ?: this.toString()               // object with message key
        else             -> toString()
    }

/**
 * A concrete implementation of [UniversalGatewayProvider] for the Inception Labs API.
 *
 * Inception's Mercury models (e.g., mercury-2, mercury-coder-small) are diffusion-based
 * large language models. The API is fully OpenAI-compatible, using:
 *   - Base URL:  https://api.inceptionlabs.ai/v1
 *   - Auth:      Authorization: Bearer <key>
 *   - Endpoint:  /v1/chat/completions  (OpenAI chat completion schema)
 *   - Models:    /v1/models            (OpenAI list-models schema)
 *
 * See: https://docs.inceptionlabs.ai/get-started/get-started
 */
class InceptionProvider(
    private val platformDependencies: PlatformDependencies
) : UniversalGatewayProvider {

    override val id: String = "inception"

    private val apiKeySettingKey = "gateway.inception.apiKey"
    private val apiHost = "api.inceptionlabs.ai"

    private val json = Json { ignoreUnknownKeys = true }
    private val prettyJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val client = HttpClient {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 240_000 }
    }

    // --- Logic extracted for testability ---

    /**
     * Builds the OpenAI-compatible JSON payload from a universal [GatewayRequest].
     *
     * Inception does not use a `name` field per message (unlike OpenAI's multi-agent
     * convention), so we send only `role` and `content`. The system prompt is prepended
     * as a `system`-role message, matching standard OpenAI convention.
     */
    internal fun buildRequestPayload(request: GatewayRequest): JsonElement {
        return buildJsonObject {
            put("model", request.modelName)
            put("messages", buildJsonArray {
                // Prepend system prompt as a system-role message if present.
                request.systemPrompt?.let {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", it)
                    })
                }
                request.contents.forEach { message ->
                    add(buildJsonObject {
                        // Map the internal "model" role to OpenAI's "assistant"
                        put("role", if (message.role == "model") "assistant" else message.role)
                        // Content enrichment (sender info, timestamps) is handled upstream by the
                        // AgentCognitivePipeline — providers receive pre-enriched content and pass it through.
                        put("content", message.content)
                    })
                }
            })
        }
    }

    /**
     * Parses a raw JSON response body into a universal [GatewayResponse].
     *
     * Follows the same three-path structure as all other providers:
     *   1. Hard API error  →  errorMessage populated
     *   2. Successful text →  rawContent populated, token usage attached
     *   3. Unrecognised format → errorMessage populated, full body logged
     */
    internal fun parseResponse(responseBody: String, correlationId: String): GatewayResponse {
        val response = json.decodeFromString<InceptionChatResponse>(responseBody)

        // Path 1: Hard API Error (handles both string and object error shapes)
        response.error?.let {
            return GatewayResponse(null, "API Error: ${it.extractErrorMessage()}", correlationId)
        }

        // Extract token usage (OpenAI-compatible fields)
        val inputTokens = response.usage?.promptTokens
        val outputTokens = response.usage?.completionTokens

        // Path 2: Successful Content Generation
        val rawText = response.choices?.firstOrNull()?.message?.content
        if (rawText != null) {
            // LOGGING: Warn if a successful response carries no token usage — may indicate
            // an API change or a deserialization issue with the snake_case fields.
            if (inputTokens == null && outputTokens == null) {
                platformDependencies.log(
                    LogLevel.WARN, id,
                    "Successful response for correlationId '$correlationId' has no token usage data. " +
                            "This may indicate an API change or deserialization issue."
                )
            }
            return GatewayResponse(rawText, null, correlationId, inputTokens, outputTokens)
        }

        // Path 3: Unrecognized response format
        platformDependencies.log(
            LogLevel.ERROR,
            id,
            "Unrecognised response format from Inception API. Full response: $responseBody"
        )
        return GatewayResponse(null, "Unrecognised response format from Inception API.", correlationId)
    }

    // --- Public Interface Implementation ---

    override fun registerSettings(dispatch: (Action) -> Unit) {
        val payload = buildJsonObject {
            put("key", apiKeySettingKey)
            put("type", "STRING")
            put("label", "Inception API Key")
            put("description", "API Key for Inception Labs Mercury models (e.g., mercury-2).")
            put("section", "API Keys")
            put("defaultValue", "")
        }
        dispatch(Action(ActionRegistry.Names.SETTINGS_ADD, payload))
    }

    override suspend fun listAvailableModels(settings: Map<String, String>): List<String> {
        val apiKey = settings[apiKeySettingKey].orEmpty()
        if (apiKey.isBlank()) {
            platformDependencies.log(
                LogLevel.WARN,
                id,
                "Cannot list models: Inception API Key is not configured."
            )
            return emptyList()
        }

        return try {
            val responseBody: String = client.get("https://$apiHost/v1/models") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }.body()

            val response = json.decodeFromString<ListModelsResponse>(responseBody)

            response.error?.let {
                platformDependencies.log(
                    LogLevel.ERROR, id,
                    "Failed to fetch models from Inception API: ${it.extractErrorMessage()}"
                )
                throw Exception("API Error: ${it.extractErrorMessage()}")
            }

            response.data.map { it.id }.sorted()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            platformDependencies.log(
                LogLevel.ERROR, id,
                "Failed to list Inception models: ${e.stackTraceToString()}"
            )
            // Hardcoded fallback — mirrors known public Mercury models as of 2025-Q1
            listOf(
                "mercury-2",
                "mercury-coder-small"
            )
        }
    }

    override suspend fun generatePreview(request: GatewayRequest, settings: Map<String, String>): String {
        val payload = buildRequestPayload(request)
        return prettyJson.encodeToString(payload)
    }

    // countTokens is intentionally not overridden: Inception does not expose a
    // dedicated token-counting endpoint. The default implementation returns null,
    // which the GatewayFeature handles gracefully by omitting the estimate.

    override suspend fun generateContent(request: GatewayRequest, settings: Map<String, String>): GatewayResponse {
        val apiKey = settings[apiKeySettingKey].orEmpty()
        if (apiKey.isBlank()) {
            return GatewayResponse(null, "Inception API Key is not configured.", request.correlationId)
        }

        return try {
            val apiRequest = buildRequestPayload(request)
            val apiUrl = "https://$apiHost/v1/chat/completions"

            val responseBody: String = client.post(apiUrl) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(apiRequest)
            }.body()

            parseResponse(responseBody, request.correlationId)
        } catch (e: CancellationException) {
            platformDependencies.log(
                LogLevel.INFO, id,
                "Inception request with correlationId '${request.correlationId}' was cancelled."
            )
            throw e
        } catch (e: Exception) {
            platformDependencies.log(
                LogLevel.ERROR, id,
                "Content generation failed: ${e.stackTraceToString()}"
            )
            val userMessage = mapExceptionToUserMessage(e)
            GatewayResponse(null, userMessage, request.correlationId)
        }
    }
}