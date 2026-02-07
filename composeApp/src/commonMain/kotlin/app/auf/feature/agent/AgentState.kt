package app.auf.feature.agent

import app.auf.core.FeatureState
import app.auf.core.Identity
import app.auf.feature.agent.strategies.SovereignDefaults
import app.auf.feature.agent.strategies.VanillaStrategy
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
enum class AgentResourceType {
    CONSTITUTION,
    BOOTLOADER,
    SYSTEM_INSTRUCTION
}

@Serializable
data class AgentResource(
    val id: String,         // Internal ID or filename
    val type: AgentResourceType,
    val name: String,       // Display Name
    val content: String,    // Text content
    val isBuiltIn: Boolean = false,
    val path: String? = null // Relative path if user-defined
)

object AgentDefaults {
    val builtInResources: List<AgentResource> = listOf(
        AgentResource(
            id = "res-sovereign-constitution-v1",
            type = AgentResourceType.CONSTITUTION,
            name = "Sovereign Constitution (v5.9)",
            content = SovereignDefaults.DEFAULT_CONSTITUTION_XML,
            isBuiltIn = true
        ),
        AgentResource(
            id = "res-boot-sentinel-v1",
            type = AgentResourceType.BOOTLOADER,
            name = "Boot Sentinel (v1.0)",
            content = SovereignDefaults.BOOT_SENTINEL_XML,
            isBuiltIn = true
        ),
        AgentResource(
            id = "res-sys-instruction-v1",
            type = AgentResourceType.SYSTEM_INSTRUCTION,
            name = "Default Builtin System Instruction",
            content = VanillaStrategy.DEFAULT_SYSTEM_INSTRUCTION_XML,
            isBuiltIn = true
        )
    )
}

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
    val rawRequestJson: String,
    /** Estimated input token count from the provider's counting API. Null if unsupported. */
    val estimatedInputTokens: Int? = null
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

    // Resource Links (Maps Slot ID -> Resource ID)
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
    val transientHkgContext: JsonObject? = null,
    /** Input tokens consumed by the last completed generation request. */
    val lastInputTokens: Int? = null,
    /** Output tokens consumed by the last completed generation request. */
    val lastOutputTokens: Int? = null
)

@Serializable
data class AgentRuntimeState(
    val agents: Map<String, AgentInstance> = emptyMap(),
    @Transient
    val agentStatuses: Map<String, AgentStatusInfo> = emptyMap(),
    val sessionNames: Map<String, String> = emptyMap(),
    val availableModels: Map<String, List<String>> = emptyMap(),
    val knowledgeGraphNames: Map<String, String> = emptyMap(),

    // Shared System Resources (Loaded at startup)
    val resources: List<AgentResource> = emptyList(),

    @Transient
    val userIdentities: List<Identity> = emptyList(),
    @Transient
    val hkgReservedIds: Set<String> = emptySet(),
    @Transient
    val editingAgentId: String? = null,
    @Transient
    val editingResourceId: String? = null,
    @Transient
    val activeManagerTab: Int = 0, // 0 = Agents, 1 = Resources
    @Transient
    val agentAvatarCardIds: Map<String, Map<String, String>> = emptyMap(),
    @Transient
    val agentsToPersist: Set<String>? = null,
    @Transient
    val viewingContextForAgentId: String? = null,
    @Transient
    val lastAutoTriggerAgentIndex: Int = 0,
) : FeatureState