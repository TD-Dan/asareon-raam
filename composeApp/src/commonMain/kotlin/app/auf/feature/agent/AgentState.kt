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
    // The time in seconds to wait for user input to stop before triggering a turn.
    val autoWaitTimeSeconds: Int = 5,
    // The maximum time in seconds an agent can be in the WAITING state before being forced to trigger.
    val autoMaxWaitTimeSeconds: Int = 30,

    @Transient
    val status: AgentStatus = AgentStatus.IDLE,

    @Transient
    val errorMessage: String? = null,

    // The "Awareness Frontier". Tracks the ID of the last message seen in the subscribed session.
    @Transient
    val lastSeenMessageId: String? = null,

    // The "Commitment Frontier". A snapshot of the awareness frontier when a cognitive cycle begins.
    @Transient
    val processingFrontierMessageId: String? = null,

    // The system timestamp (milliseconds) when the agent first entered the WAITING state.
    @Transient
    val waitingSinceTimestamp: Long? = null,

    // The system timestamp (milliseconds) of the last message that the agent observed.
    @Transient
    val lastMessageReceivedTimestamp: Long? = null,
)

@Serializable
data class AgentRuntimeState(
    val agents: Map<String, AgentInstance> = emptyMap(),
    val sessionNames: Map<String, String> = emptyMap(),
    val availableModels: Map<String, List<String>> = emptyMap(),

    @Transient
    val editingAgentId: String? = null,

    @Transient
    val agentAvatarCardIds: Map<String, AvatarCardInfo> = emptyMap(),

    /** A transient field to reliably pass the IDs of agents who need their config persisted from the reducer to the onAction handler. */
    @Transient
    val agentsToPersist: Set<String>? = null
) : FeatureState {
    @Serializable
    data class AvatarCardInfo(val messageId: String, val sessionId: String)
}