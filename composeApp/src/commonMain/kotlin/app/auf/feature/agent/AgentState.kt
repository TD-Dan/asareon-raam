package app.auf.feature.agent

import app.auf.core.FeatureState
import kotlinx.serialization.Serializable

/**
 * An enumeration of the possible lifecycle states for an Agent.
 */
@Serializable
enum class AgentStatus {
    /** The agent is idle and observing its environment. */
    WAITING,
    /** The agent has been stimulated and is waiting for a pause before acting. */
    PRIMED,
    /** The agent has committed to acting and is executing a side effect (e.g., API call). */
    PROCESSING
}

@Serializable
data class CompilerSettings(
    val removeWhitespace: Boolean = true,
    val cleanHeaders: Boolean = true,
    val minifyJson: Boolean = false
)

/**
 * The state for the AgentRuntimeFeature itself.
 * In the future, this could hold a map of multiple agent states if the architecture evolves.
 */
@Serializable
data class AgentRuntimeFeatureState(
    // For now, we assume a single, active agent per session.
    val agent: AgentRuntimeState? = null
) : FeatureState

/**
 * The state for a single, active agent instance managed by the runtime.
 * This is the generic state object, repurposed from the old HkgAgentState.
 */
@Serializable
data class AgentRuntimeState(
    val id: String,
    val sessionId: String,
    val hkgPersonaId: String? = null,
    val availableModels: List<String> = emptyList(),
    val selectedModel: String = "gemini-1.5-flash-latest",
    val compilerSettings: CompilerSettings = CompilerSettings(),
    // --- State Machine ---
    val status: AgentStatus = AgentStatus.WAITING,
    val primedAt: Long? = null,
    val lastEntryAt: Long? = null,
    // --- Configuration ---
    val initialWaitMillis: Long = 1500L,
    val maxWaitMillis: Long = 10000L
) {
    // Convenience property derived from state
    val isProcessing: Boolean
        get() = status == AgentStatus.PROCESSING
}