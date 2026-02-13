package app.auf.feature.gateway.gemini

import app.auf.core.Action
import app.auf.core.generated.ActionNames
import app.auf.feature.gateway.*
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

// --- Data Contracts specific to the Gemini API ---
@Serializable
private data class GenerateContentResponse(
    val candidates: List<Candidate>? = null,
    val promptFeedback: PromptFeedback? = null,
    val usageMetadata: UsageMetadata? = null,
    val error: ApiError? = null
)
@Serializable
private data class Candidate(
    val content: Content? = null,
    val finishReason: String? = null
)
@Serializable
private data class Content(val parts: List<Part>? = null, val role: String? = null) // Gemini can return a role in its content block
@Serializable
private data class Part(val text: String)
@Serializable
private data class PromptFeedback(val blockReason: String?)
@Serializable
private data class UsageMetadata(
    val promptTokenCount: Int? = null,
    val candidatesTokenCount: Int? = null,
    val totalTokenCount: Int? = null
)
@Serializable
private data class ApiError(val message: String)
@Serializable
private data class ListModelsResponse(val models: List<ModelInfo>)
@Serializable
private data class ModelInfo(
    val name: String,
    val supportedGenerationMethods: List<String> = emptyList()
)

// --- Token Counting API ---
@Serializable
private data class CountTokensResponse(
    val totalTokens: Int? = null,
    val error: ApiError? = null
)

/**
 * A concrete implementation of the AgentGatewayProvider for the Google Gemini API.
 */
class GeminiProvider(
    private val platformDependencies: PlatformDependencies
) : UniversalGatewayProvider {
    override val id: String = "gemini"
    private val apiKeySettingKey = "gateway.gemini.apiKey"
    private val API_HOST = "generativelanguage.googleapis.com"

    private val json = Json { ignoreUnknownKeys = true }
    // NEW: Used for preview formatting
    private val prettyJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val client = HttpClient {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 240_000 }
    }

    // --- Logic extracted for testability ---

    /** Builds the provider-specific JSON payload from a universal request. */
    internal fun buildRequestPayload(request: GatewayRequest): JsonElement {
        // Content enrichment (sender info, timestamps) is handled upstream by the
        // AgentCognitivePipeline — providers receive pre-enriched content and pass it through.
        val apiContents = buildJsonArray {
            request.contents.forEach { message ->
                add(buildJsonObject {
                    put("role", message.role)
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", message.content) })
                    })
                })
            }
        }
        // Construct the system_instruction block first to improve audit legibility.
        return buildJsonObject {
            request.systemPrompt?.let {
                putJsonObject("system_instruction") {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", it) })
                    })
                }
            }
            put("contents", apiContents)
        }
    }

    /** Parses a raw JSON response body into a universal GatewayResponse. */
    internal fun parseResponse(responseBody: String, correlationId: String): GatewayResponse {
        val response = json.decodeFromString<GenerateContentResponse>(responseBody)

        // Path 1: Hard API Error
        response.error?.let {
            return GatewayResponse(null, "API Error: ${it.message}", correlationId)
        }

        // Extract token usage from usageMetadata
        val inputTokens = response.usageMetadata?.promptTokenCount
        val outputTokens = response.usageMetadata?.candidatesTokenCount

        // Path 2: Successful Content Generation
        val rawText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
        if (rawText != null) {
            // LOGGING: Warn if a successful response has no token usage.
            if (inputTokens == null && outputTokens == null) {
                platformDependencies.log(
                    LogLevel.WARN, id,
                    "Successful response for correlationId '$correlationId' has no token usage data. " +
                            "This may indicate an API change or deserialization issue."
                )
            }
            return GatewayResponse(rawText, null, correlationId, inputTokens, outputTokens)
        }

        // Path 3: Safety/Content Filter Block
        response.promptFeedback?.blockReason?.let {
            return GatewayResponse(null, "Blocked by provider: $it", correlationId)
        }

        if (response.candidates != null) {
            platformDependencies.log(LogLevel.INFO, id, "Received a valid response with no text content from Gemini for correlationId '$correlationId'. Treating as a completed empty turn.")
            return GatewayResponse("", null, correlationId, inputTokens, outputTokens)
        }

        // Path 5 (Fallback): Unrecognized response format
        platformDependencies.log(
            LogLevel.ERROR,
            id,
            "Unrecognised response format from Gemini API. Full response: $responseBody"
        )
        return GatewayResponse(null, "Unrecognised response format from Gemini API.", correlationId)
    }

    // --- Public Interface Implementation ---

    override fun registerSettings(dispatch: (Action) -> Unit) {
        val payload = buildJsonObject {
            put("key", apiKeySettingKey)
            put("type", "STRING")
            put("label", "Gemini API Key")
            put("description", "API Key for Google AI Gemini models.")
            put("section", "API Keys")
            put("defaultValue", "")
        }
        dispatch(Action(ActionRegistry.Names.SETTINGS_ADD, payload))
    }

    override suspend fun listAvailableModels(settings: Map<String, String>): List<String> {
        val apiKey = settings[apiKeySettingKey].orEmpty()
        if (apiKey.isBlank()) return emptyList()

        return try {
            val response: ListModelsResponse = client.get("https://$API_HOST/v1beta/models") {
                parameter("key", apiKey)
            }.body()
            response.models
                .filter { "generateContent" in it.supportedGenerationMethods }
                .map { it.name.replace("models/", "") }
                .sorted()
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.WARN, id, "Failed to fetch Gemini models: ${e.message}")
            emptyList()
        }
    }

    // IMPLEMENTATION: New generatePreview method
    override suspend fun generatePreview(request: GatewayRequest, settings: Map<String, String>): String {
        // For preview, we don't need the API key, just the payload logic.
        val payload = buildRequestPayload(request)
        return prettyJson.encodeToString(payload)
    }

    /**
     * Calls Gemini's countTokens endpoint to estimate input token usage before execution.
     * The countTokens endpoint requires the request to be wrapped in a
     * "generateContentRequest" field, rather than accepting contents/system_instruction at root level.
     */
    override suspend fun countTokens(request: GatewayRequest, settings: Map<String, String>): TokenCountEstimate? {
        val apiKey = settings[apiKeySettingKey].orEmpty()
        if (apiKey.isBlank()) return null

        return try {
            val innerPayload = buildRequestPayload(request)
            // Wrap in generateContentRequest as required by the countTokens endpoint.
            // The model must be specified inside the wrapper since it's not inferred from the URL.
            val countPayload = buildJsonObject {
                putJsonObject("generateContentRequest") {
                    put("model", "models/${request.modelName}")
                    // Merge in the contents/system_instruction from the inner payload
                    innerPayload.jsonObject.forEach { (key, value) ->
                        put(key, value)
                    }
                }
            }
            val apiUrl = "https://$API_HOST/v1beta/models/${request.modelName}:countTokens"

            val responseBody: String = client.post(apiUrl) {
                parameter("key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(countPayload)
            }.body()

            val response = json.decodeFromString<CountTokensResponse>(responseBody)

            response.error?.let {
                platformDependencies.log(LogLevel.WARN, id, "Token counting failed: ${it.message}")
                return null
            }

            response.totalTokens?.let { TokenCountEstimate(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.WARN, id, "Token counting request failed: ${e.message}")
            null
        }
    }

    override suspend fun generateContent(request: GatewayRequest, settings: Map<String, String>): GatewayResponse {
        val apiKey = settings[apiKeySettingKey].orEmpty()
        if (apiKey.isBlank()) {
            return GatewayResponse(null, "Gemini API Key is not configured.", request.correlationId)
        }

        return try {
            val apiRequest = buildRequestPayload(request)
            val apiUrl = "https://$API_HOST/v1beta/models/${request.modelName}:generateContent"

            val responseBody: String = client.post(apiUrl) {
                parameter("key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(apiRequest)
            }.body()

            parseResponse(responseBody, request.correlationId)
        } catch (e: CancellationException) {
            platformDependencies.log(LogLevel.INFO, id, "Gemini request with correlationId '${request.correlationId}' was cancelled.")
            throw e
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.ERROR, id, "Content generation failed: ${e.stackTraceToString()}")
            val userMessage = mapExceptionToUserMessage(e)
            GatewayResponse(null, userMessage, request.correlationId)
        }
    }
}