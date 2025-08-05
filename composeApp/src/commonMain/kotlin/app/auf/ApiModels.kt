package app.auf

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Request Models ---

@Serializable
data class GenerateContentRequest(
    val contents: List<Content>
)

@Serializable
data class Content(
    val role: String,
    val parts: List<Part>
)

@Serializable
data class Part(
    val text: String
)

// --- Response Models (for GenerateContent) ---

@Serializable
data class GenerateContentResponse(
    // This field is present on success
    val candidates: List<Candidate>? = null,
    @SerialName("promptFeedback")
    val promptFeedback: PromptFeedback? = null,
    // ADDED: This new field will capture any error object returned by the API
    val error: ApiError? = null
)

@Serializable
data class Candidate(
    val content: Content,
    val finishReason: String?,
    val safetyRatings: List<SafetyRating> = emptyList()
)

@Serializable
data class SafetyRating(
    val category: String,
    val probability: String
)

@Serializable
data class PromptFeedback(
    val blockReason: String?,
    val safetyRatings: List<SafetyRating>
)

// ADDED: A new data class to represent the structure of an error response from the Gemini API.
@Serializable
data class ApiError(
    val code: Int,
    val message: String,
    val status: String
)


// --- Response Models (for ListModels) ---

@Serializable
data class ListModelsResponse(
    val models: List<ModelInfo>
)

@Serializable
data class ModelInfo(
    val name: String,
    val displayName: String?,
    val description: String? = null,
    @SerialName("version")
    val version: String
)