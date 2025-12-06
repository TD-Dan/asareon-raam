package app.auf.feature.agent

import app.auf.core.FeatureState
import app.auf.core.Identity
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

@Serializable
enum class AgentStatus { IDLE, WAITING, PROCESSING, ERROR }

@Serializable
enum class TurnMode { DIRECT, PREVIEW }

@Serializable
data class GatewayMessage(
    val role: String,
    val content: String,
    val senderId: String,
    val senderName: String,
    val timestamp: Long
)

@Serializable
data class GatewayRequest(
    val modelName: String,
    val contents: List<GatewayMessage>,
    val correlationId: String,
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

    // Cognitive Architecture
    val cognitiveStrategyId: String = "vanilla_v1",

    // The "NVRAM" / Control Registers
    // Persisted, so the agent remembers its state across restarts.
    val cognitiveState: JsonElement = JsonNull,

    // Resource Links (for Sovereign Strategy)
    val resources: Map<String, String> = emptyMap(),

    // Configuration
    val automaticMode: Boolean = false,
    val autoWaitTimeSeconds: Int = 5,
    val autoMaxWaitTimeSeconds: Int = 30,
    val isAgentActive: Boolean = true
)

/**
 * [RUNTIME STATE]
 * Ephemeral state for an active agent.
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
    val transientHkgContext: JsonObject? = null
)

@Serializable
data class AgentRuntimeState(
    val agents: Map<String, AgentInstance> = emptyMap(),
    @Transient
    val agentStatuses: Map<String, AgentStatusInfo> = emptyMap(),
    val sessionNames: Map<String, String> = emptyMap(),
    val availableModels: Map<String, List<String>> = emptyMap(),
    val knowledgeGraphNames: Map<String, String> = emptyMap(),
    @Transient
    val userIdentities: List<Identity> = emptyList(),
    @Transient
    val hkgReservedIds: Set<String> = emptySet(),
    @Transient
    val editingAgentId: String? = null,
    @Transient
    val agentAvatarCardIds: Map<String, Map<String, String>> = emptyMap(),
    @Transient
    val agentsToPersist: Set<String>? = null,
    @Transient
    val viewingContextForAgentId: String? = null,
    @Transient
    val lastAutoTriggerAgentIndex: Int = 0,
) : FeatureState