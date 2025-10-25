package app.auf.feature.agent

import app.auf.core.FeatureState
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
enum class AgentStatus { IDLE, WAITING, PROCESSING, ERROR }

@Serializable
data class AgentInstance(
    val id: String,
    val name: String,
    val personaId: String,
    val modelProvider: String,
    val modelName: String,
    val primarySessionId: String? = null,
    val automaticMode: Boolean = false,

    @Transient
    val status: AgentStatus = AgentStatus.IDLE,

    @Transient
    val errorMessage: String? = null,

    // The "Awareness Frontier". Tracks the ID of the last message seen in the subscribed session.
    @Transient
    val lastSeenMessageId: String? = null,

    // The "Commitment Frontier". A snapshot of the awareness frontier when a cognitive cycle begins.
    @Transient
    val processingFrontierMessageId: String? = null
)

@Serializable
data class AgentRuntimeState(
    val agents: Map<String, AgentInstance> = emptyMap(),
    val sessionNames: Map<String, String> = emptyMap(),
    val availableModels: Map<String, List<String>> = emptyMap(),

    @Transient
    val editingAgentId: String? = null,

    /**
     * REFACTORED: A transient map tracking the message ID and session ID of each agent's active avatar card.
     * This solves the bug where the feature would try to delete a card from the wrong session.
     *
     * Structure: Map<agentId, AvatarCardInfo>
     */
    @Transient
    val agentAvatarCardIds: Map<String, AvatarCardInfo> = emptyMap(),

    /** A transient field to reliably pass the IDs of agents who need their config persisted from the reducer to the onAction handler. */
    @Transient
    val agentsToPersist: Set<String>? = null
) : FeatureState {
    /**
     * REFACTORED: A new data class to hold all necessary information about an avatar card's location.
     */
    @Serializable
    data class AvatarCardInfo(val messageId: String, val sessionId: String)
}