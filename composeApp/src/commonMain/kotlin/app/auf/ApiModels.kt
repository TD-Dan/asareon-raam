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
    // --- FIX IS HERE: Made the 'parts' field optional by providing a default value. ---
    val parts: List<Part> = emptyList()
)

@Serializable
data class Part(
    val text: String
)

// --- Response Models (for GenerateContent) ---

@Serializable
data class GenerateContentResponse(
    val candidates: List<Candidate>? = null,
    @SerialName("promptFeedback")
    val promptFeedback: PromptFeedback? = null,
    val error: ApiError? = null,
    val usageMetadata: UsageMetadata? = null
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

@Serializable
data class ApiError(
    val code: Int,
    val message: String,
    val status: String
)

@Serializable
data class UsageMetadata(
    val promptTokenCount: Int = 0,
    val candidatesTokenCount: Int = 0,
    val totalTokenCount: Int = 0
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