package app.auf.feature.agent

import app.auf.core.FeatureState
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

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

    // Status is now a transient, in-memory state. It will not be saved to disk.
    // When an agent is loaded, it will always default to IDLE.
    @Transient
    val status: AgentStatus = AgentStatus.IDLE,

    // A transient message for displaying errors in the UI. Also not saved.
    @Transient
    val errorMessage: String? = null
)

/**
 * The root state for the AgentRuntimeFeature. Its sole responsibility is to hold the
 * state for all configured agent instances.
 */
@Serializable
data class AgentRuntimeState(
    val agents: Map<String, AgentInstance> = emptyMap(),

    // The ID of the agent currently being edited in the UI. This is transient UI state.
    @Transient
    val editingAgentId: String? = null
) : FeatureState