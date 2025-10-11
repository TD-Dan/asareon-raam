package app.auf.feature.agent

import app.auf.core.FeatureState
import kotlinx.serialization.Serializable

/**
 * Defines the possible lifecycle states for an AgentInstance.
 */
@Serializable
enum class AgentStatus {
    /** The agent is active and ready to be triggered. */
    IDLE,
    /** The agent has been triggered but is in a cooldown/debounce period before processing. (For future use) */
    WAITING,
    /** The agent is actively processing a turn (e.g., waiting for an API response). */
    PROCESSING,
    /** The agent's last turn resulted in an error. */
    ERROR
}

/**
 * Represents a single, configurable, autonomous agent instance hosted by the AgentRuntimeFeature.
 */
@Serializable
data class AgentInstance(
    val id: String,
    val name: String,
    val personaId: String, // The ID of the AI_Persona_Root holon file. (For future use)
    val modelProvider: String, // e.g., "gemini" or "openai"
    val modelName: String, // e.g., "gemini-2.5-pro"
    val primarySessionId: String? = null, // The session this agent will post its responses to.
    val status: AgentStatus = AgentStatus.IDLE
)

/**
 * The root state for the AgentRuntimeFeature. Its sole responsibility is to hold the
 * state for all configured agent instances.
 */
@Serializable
data class AgentRuntimeState(
    val agents: Map<String, AgentInstance> = emptyMap()
) : FeatureState