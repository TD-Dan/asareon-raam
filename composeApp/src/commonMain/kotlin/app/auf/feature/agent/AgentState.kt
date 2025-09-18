package app.auf.feature.agent

import app.auf.core.FeatureState
import kotlinx.coroutines.Job
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

// --- TURN STATE MACHINE (Unchanged) ---
@Serializable
sealed interface AgentTurn {
    @Serializable
    data object Idle : AgentTurn

    @Serializable
    data class Processing(
        val turnId: String,
        val parentEntryId: String,
        @Transient val job: Job? = null
    ) : AgentTurn
}

// --- NEW: GATEWAY INFORMATION MODEL ---
/**
 * Represents the state of a single, available AgentGateway.
 * This is populated at startup by querying the gateway.
 */
@Serializable
data class GatewayInfo(
    val id: String,
    val availableModels: List<String> = emptyList(),
    val isAvailable: Boolean = false // e.g., false if an API key is missing
)


// --- REVISED: THE AGENT INSTANCE MODEL ---
/**
 * Represents the complete state and configuration for a single, unique agent instance.
 */
@Serializable
data class AgentRuntimeState(
    val id: String,
    val archetypeId: String,
    val displayName: String,

    // --- Durable Configuration (Managed by AgentManagerView) ---
    /** The ID of the gateway this agent is configured to use. */
    val gatewayId: String,
    /** The specific model this agent has selected from the gateway's list. */
    val selectedModelId: String,
    val hkgPersonaId: String? = null,

    // --- Transient State Machine (Managed by the Reducer) ---
    val turn: AgentTurn = AgentTurn.Idle
)


// --- REVISED: THE FEATURE (MANAGER) STATE ---
/**
 * The state for the AgentRuntimeFeature. It now acts as a manager, holding a map
 * of all active agent instances and all available gateways.
 */
@Serializable
data class AgentRuntimeFeatureState(
    /** A map of all available gateways and their models, keyed by gateway ID. */
    val gateways: Map<String, GatewayInfo> = emptyMap(),
    /** The collection of all agent instances, keyed by their unique `id`. */
    val agents: Map<String, AgentRuntimeState> = emptyMap(),
    /** The ID of the agent currently selected in the AgentManagerView, for UI purposes. */
    val activeAgentIdForManager: String? = null
) : FeatureState