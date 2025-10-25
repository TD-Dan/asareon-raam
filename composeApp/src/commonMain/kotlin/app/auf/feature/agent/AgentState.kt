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
    // Configuration
    val automaticMode: Boolean = false,
    val autoWaitTimeSeconds: Int = 5,
    val autoMaxWaitTimeSeconds: Int = 30,
    val isAgentActive: Boolean = true,

    // Transient State
    @Transient
    val status: AgentStatus = AgentStatus.IDLE,
    @Transient
    val errorMessage: String? = null,
    @Transient
    val lastSeenMessageId: String? = null,
    @Transient
    val processingFrontierMessageId: String? = null,
    @Transient
    val waitingSinceTimestamp: Long? = null,
    @Transient
    val lastMessageReceivedTimestamp: Long? = null,
    @Transient
    val processingSinceTimestamp: Long? = null,
    @Transient
    val processingStep: String? = null,
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