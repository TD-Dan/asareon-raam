package app.auf.feature.agent

import app.auf.core.FeatureState
import app.auf.core.Identity
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

@Serializable
enum class AgentStatus { IDLE, WAITING, PROCESSING, ERROR }

@Serializable
enum class TurnMode { DIRECT, PREVIEW }

@Serializable
data class GatewayMessage(
    val role: String,
    val content: String,
    // Enriched with sender identity
    val senderId: String,
    val senderName: String,
    // Enriched with timestamp
    val timestamp: Long
)

@Serializable
data class GatewayRequest(
    val modelName: String,
    val contents: List<GatewayMessage>,
    val correlationId: String,
    // NEW: The system prompt for behavioral control.
    val systemPrompt: String? = null
)

@Serializable
data class StagedPreviewData(
    val agnosticRequest: GatewayRequest,
    val rawRequestJson: String
)

/**
 * [PURE CONFIGURATION]
 * Defines the persistent identity and settings of an agent.
 * Contains NO runtime state.
 */
@Serializable
data class AgentInstance(
    val id: String,
    val name: String,
    val knowledgeGraphId: String? = null,
    val modelProvider: String,
    val modelName: String,
    val subscribedSessionIds: List<String> = emptyList(),
    val privateSessionId: String? = null,
    // Configuration
    val automaticMode: Boolean = false,
    val autoWaitTimeSeconds: Int = 5,
    val autoMaxWaitTimeSeconds: Int = 30,
    val isAgentActive: Boolean = true
)

/**
 * [RUNTIME STATE]
 * Ephemeral state for an active agent.
 * This is never persisted to disk.
 */
data class AgentStatusInfo(
    val status: AgentStatus = AgentStatus.IDLE,
    val errorMessage: String? = null,
    val lastSeenMessageId: String? = null,
    val processingFrontierMessageId: String? = null,
    val waitingSinceTimestamp: Long? = null,
    val lastMessageReceivedTimestamp: Long? = null,
    val processingSinceTimestamp: Long? = null,
    val processingStep: String? = null,
    val turnMode: TurnMode = TurnMode.DIRECT,
    val stagedPreviewData: StagedPreviewData? = null,
    val stagedTurnContext: List<GatewayMessage>? = null,
    // A transient field to hold the HKG content for a single turn.
    val transientHkgContext: JsonObject? = null
)

@Serializable
data class AgentRuntimeState(
    val agents: Map<String, AgentInstance> = emptyMap(),

    // [NEW] The separate map for runtime status. Keys match agent IDs.
    @Transient
    val agentStatuses: Map<String, AgentStatusInfo> = emptyMap(),

    val sessionNames: Map<String, String> = emptyMap(),
    val availableModels: Map<String, List<String>> = emptyMap(),
    val knowledgeGraphNames: Map<String, String> = emptyMap(),

    // Cache the full list of user identities, not just the active one.
    @Transient
    val userIdentities: List<Identity> = emptyList(),

    // [NEW] Caches the set of HKG IDs that are currently reserved by any agent.
    @Transient
    val hkgReservedIds: Set<String> = emptySet(),

    @Transient
    val editingAgentId: String? = null,

    @Transient
    val agentAvatarCardIds: Map<String, AvatarCardInfo> = emptyMap(),

    /** A transient field to reliably pass the IDs of agents who need their config persisted from the reducer to the onAction handler. */
    @Transient
    val agentsToPersist: Set<String>? = null,

    /** A transient field to indicate which agent's context is being viewed in the preview screen. */
    @Transient
    val viewingContextForAgentId: String? = null,

    /** [NEW] A transient field to track the round-robin index for the staggered automatic trigger. */
    @Transient
    val lastAutoTriggerAgentIndex: Int = 0,

    ) : FeatureState {
    @Serializable
    data class AvatarCardInfo(val messageId: String, val sessionId: String)
}