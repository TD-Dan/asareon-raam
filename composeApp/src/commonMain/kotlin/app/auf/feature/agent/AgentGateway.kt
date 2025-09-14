package app.auf.feature.agent

import kotlinx.serialization.Serializable

// --- 1. INTERFACE ---

/**
 * ## Mandate
 * Defines a platform-and-service-agnostic contract for an AI content generation service.
 * This is the abstraction the HkgAgentFeature depends on, allowing the concrete
 * implementation (e.g., GatewayGemini) to be swapped out.
 */
interface AgentGateway {
    suspend fun generateContent(request: AgentRequest): AgentResponse
    suspend fun listAvailableModels(): List<String>
}

// --- 2. DATA CONTRACTS ---

data class AgentRequest(
    val modelName: String,
    val contents: List<Content>
)

data class AgentResponse(
    val rawContent: String?,
    val errorMessage: String?,
    val usageMetadata: UsageMetadata? = null
)

// --- Shared Models (Kept internal to the feature's gateway) ---

@Serializable
data class Content(val role: String, val parts: List<Part>)

@Serializable
data class Part(val text: String)

@Serializable
data class UsageMetadata(
    val promptTokenCount: Int? = null,
    val candidatesTokenCount: Int? = null,
    val totalTokenCount: Int? = null
)