package app.auf.feature.gateway.gemini

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
import io.ktor.client.statement.*
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
private data class Content(val parts: List<Part>? = null, val role: String? = null)
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
    private val prettyJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val client = HttpClient {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 240_000 }
        expectSuccess = false
    }

    // --- Logic extracted for testability ---

    internal fun buildRequestPayload(request: GatewayRequest): JsonElement {
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

    internal fun parseResponse(responseBody: String, correlationId: String): GatewayResponse {
        val response = json.decodeFromString<GenerateContentResponse>(responseBody)

        response.error?.let {
            return GatewayResponse(null, "API Error: ${it.message}", correlationId)
        }

        val inputTokens = response.usageMetadata?.promptTokenCount
        val outputTokens = response.usageMetadata?.candidatesTokenCount

        val rawText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
        if (rawText != null) {
            if (inputTokens == null && outputTokens == null) {
                platformDependencies.log(
                    LogLevel.WARN, id,
                    "Successful response for correlationId '$correlationId' has no token usage data. " +
                            "This may indicate an API change or deserialization issue."
                )
            }
            return GatewayResponse(rawText, null, correlationId, inputTokens, outputTokens)
        }

        response.promptFeedback?.blockReason?.let {
            return GatewayResponse(null, "Blocked by provider: $it", correlationId)
        }

        if (response.candidates != null) {
            platformDependencies.log(LogLevel.INFO, id, "Received a valid response with no text content from Gemini for correlationId '$correlationId'. Treating as a completed empty turn.")
            return GatewayResponse("", null, correlationId, inputTokens, outputTokens)
        }

        platformDependencies.log(
            LogLevel.ERROR, id,
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

    override suspend fun generatePreview(request: GatewayRequest, settings: Map<String, String>): String {
        val payload = buildRequestPayload(request)
        return prettyJson.encodeToString(payload)
    }

    override suspend fun countTokens(request: GatewayRequest, settings: Map<String, String>): TokenCountEstimate? {
        val apiKey = settings[apiKeySettingKey].orEmpty()
        if (apiKey.isBlank()) return null

        return try {
            val innerPayload = buildRequestPayload(request)
            val countPayload = buildJsonObject {
                putJsonObject("generateContentRequest") {
                    put("model", "models/${request.modelName}")
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
            val currentTimeMs = platformDependencies.currentTimeMillis()

            val httpResponse: HttpResponse = client.post(apiUrl) {
                parameter("key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(apiRequest)
            }
            val responseBody: String = httpResponse.body()

            // Gemini does NOT return per-response quota headers (x-ratelimit-*).
            // This will only produce a RateLimitInfo on 429 responses (via retry-after).
            val rateLimitInfo = httpResponse.extractRateLimitInfo(currentTimeMs)

            if (rateLimitInfo != null) {
                platformDependencies.log(
                    LogLevel.DEBUG, id,
                    "Rate limit for ${request.correlationId}: " +
                            "requests=${rateLimitInfo.requestsRemaining}/${rateLimitInfo.requestLimit}, " +
                            "tokens=${rateLimitInfo.tokensRemaining}/${rateLimitInfo.tokenLimit}" +
                            (rateLimitInfo.retryAfterMs?.let { ", retryAfter=$it" } ?: "")
                )
            }

            if (isRateLimited(httpResponse.status.value)) {
                return buildRateLimitedResponse(request.correlationId, rateLimitInfo, "Gemini", currentTimeMs)
            }

            parseResponse(responseBody, request.correlationId).copy(rateLimitInfo = rateLimitInfo)

        } catch (e: ResponseException) {
            val currentTimeMs = platformDependencies.currentTimeMillis()
            val rateLimitInfo = e.extractRateLimitInfo(currentTimeMs)
            if (isRateLimited(e.response.status.value)) {
                platformDependencies.log(LogLevel.WARN, id,
                    "Rate limited (via exception) for correlationId '${request.correlationId}'.")
                return buildRateLimitedResponse(request.correlationId, rateLimitInfo, "Gemini", currentTimeMs)
            }
            platformDependencies.log(LogLevel.ERROR, id, "HTTP error: ${e.response.status}. ${e.message}")
            val userMessage = mapExceptionToUserMessage(e)
            GatewayResponse(null, userMessage, request.correlationId, rateLimitInfo = rateLimitInfo)
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