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

@Serializable
data class AgentInstance(
    val id: String,
    val name: String,
    val knowledgeGraphId: String? = null,
    val modelProvider: String,
    val modelName: String,
    val subscribedSessionIds: List<String> = emptyList(), // *** MODIFIED: Renamed from primarySessionId and changed to List
    val privateSessionId: String? = null, // *** MODIFIED: Now nullable
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
    @Transient
    val turnMode: TurnMode = TurnMode.DIRECT,
    @Transient
    val stagedPreviewData: StagedPreviewData? = null,
    @Transient
    val stagedTurnContext: List<GatewayMessage>? = null,
    // *** NEW: A transient field to hold the HKG content for a single turn.
    @Transient
    val transientHkgContext: JsonObject? = null
)

@Serializable
data class AgentRuntimeState(
    val agents: Map<String, AgentInstance> = emptyMap(),
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