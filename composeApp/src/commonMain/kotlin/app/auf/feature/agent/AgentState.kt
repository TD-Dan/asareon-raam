package app.auf.feature.agent

import app.auf.core.FeatureState
import app.auf.core.Identity
import app.auf.core.IdentityHandle
import app.auf.core.IdentityUUID
import app.auf.feature.agent.strategies.SovereignDefaults // Allowed: this is an inter-feature import
import app.auf.feature.agent.strategies.VanillaStrategy // Allowed: this is an inter-feature import
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
 * Tracks an agent command dispatched via ACTION_CREATED so that AgentRuntimeFeature
 * can route targeted RETURN_* data back to the originating session via
 * commandbot.DELIVER_TO_SESSION.
 *
 * Mirrors CoreState.PendingCommand but lives in agent state because agent commands
 * require sandbox-aware formatting that CoreFeature doesn't own.
 */
@Serializable
data class AgentPendingCommand(
    val correlationId: String,
    val agentId: IdentityUUID,
    val agentName: String,
    val sessionId: IdentityHandle,
    val actionName: String,
    val createdAt: Long
)

// ============================================================================
// [PHASE 1] AgentInstance — Typed ID fields
//
// Design decisions:
//
// 1. `identity: Identity` is RETAINED for serialization backward compatibility.
//    Existing agent.json files on disk contain `"identity": { ... }` and must
//    continue to load without migration. Computed typed accessors provide the
//    Phase 1 type safety at call sites.
//
// 2. `subscribedSessionIds`, `privateSessionId`, and `resources` are migrated
//    to value class wrappers. Because value classes serialize transparently as
//    their underlying type, existing JSON is forward-compatible — no migration.
//
// 3. `cognitiveStrategyId` remains `String` until Phase 2 (strategy identity
//    registration).
//
// 4. `knowledgeGraphId` remains `String?` until Phase 4 (compartmentalization).
// ============================================================================

/**
 * [PURE CONFIGURATION]
 * Defines the persistent identity and settings of an agent.
 */
@Serializable
data class AgentInstance(
    val identity: Identity,
    val knowledgeGraphId: String? = null,
    val modelProvider: String,
    val modelName: String,

    // [PHASE 1] Typed: session localHandle wrappers.
    // Value class serializes as plain String — backward-compatible.
    val subscribedSessionIds: List<IdentityHandle> = emptyList(),
    val privateSessionId: IdentityHandle? = null,

    // Cognitive Architecture
    val cognitiveStrategyId: String = "vanilla_v1",

    // The "NVRAM" / Control Registers
    // Persisted, so the agent remembers its state across restarts.
    val cognitiveState: JsonElement = JsonNull,

    // [PHASE 1] Typed: resource slot → resource UUID.
    // Key (slot ID) remains plain String (strategy-defined constant, not a registered identity).
    // Value migrated from String to IdentityUUID.
    val resources: Map<String, IdentityUUID> = emptyMap(),

    // Configuration
    val automaticMode: Boolean = false,
    val autoWaitTimeSeconds: Int = 5,
    val autoMaxWaitTimeSeconds: Int = 30,
    val isAgentActive: Boolean = true
) {
    // ---- Phase 1 typed accessors (computed from embedded Identity) ----

    /** The agent's bus address. Prefer over `identity.handle`. */
    val identityHandle: IdentityHandle get() = IdentityHandle(identity.handle)

    /** The agent's UUID. Prefer over `identity.uuid`. */
    val identityUUID: IdentityUUID get() = IdentityUUID(identity.uuid!!)
}

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
    /** Formatted workspace file listing for injection into the system prompt. Null = not yet received. */
    val transientWorkspaceContext: String? = null,
    /** Timestamp (epoch ms) when context gathering started. Used for timeout validation. */
    val contextGatheringStartedAt: Long? = null,
    /** Input tokens consumed by the last completed generation request. */
    val lastInputTokens: Int? = null,
    /** Output tokens consumed by the last completed generation request. */
    val lastOutputTokens: Int? = null
)

@Serializable
data class AgentRuntimeState(
    // [PHASE 1] Key = IdentityUUID (agent UUID). Value class used as map key.
    // Serializes transparently — JSON key is still a plain string.
    val agents: Map<IdentityUUID, AgentInstance> = emptyMap(),
    @Transient
    /** Key = IdentityUUID (agent UUID). */
    val agentStatuses: Map<IdentityUUID, AgentStatusInfo> = emptyMap(),
    /** Map of session localHandle → display name for non-private sessions.
     *  Populated from SESSION_NAMES_UPDATED broadcast (which excludes isPrivate sessions).
     *  [PHASE 1] Key typed as IdentityHandle (session localHandle wrapper). */
    val subscribableSessionNames: Map<IdentityHandle, String> = emptyMap(),
    val availableModels: Map<String, List<String>> = emptyMap(),
    val knowledgeGraphNames: Map<String, String> = emptyMap(),

    // Shared System Resources (Loaded at startup)
    val resources: List<AgentResource> = emptyList(),

    @Transient
    val userIdentities: List<Identity> = emptyList(),
    @Transient
    val hkgReservedIds: Set<String> = emptySet(),
    @Transient
    val editingAgentId: IdentityUUID? = null,
    @Transient
    val editingResourceId: String? = null,
    @Transient
    val activeManagerTab: Int = 0, // 0 = Agents, 1 = Resources
    @Transient
    /** Key = IdentityUUID (agent) → Map<IdentityHandle (session) → messageId> */
    val agentAvatarCardIds: Map<IdentityUUID, Map<IdentityHandle, String>> = emptyMap(),
    @Transient
    val agentsToPersist: Set<IdentityUUID>? = null,
    @Transient
    val viewingContextForAgentId: IdentityUUID? = null,
    @Transient
    val lastAutoTriggerAgentIndex: Int = 0,

    // --- Transient State for Command Result Routing ---
    /** Maps correlationId → AgentPendingCommand for in-flight agent commands.
     *  Used to route targeted RETURN_* data to the session via DELIVER_TO_SESSION. */
    @Transient
    val pendingCommands: Map<String, AgentPendingCommand> = emptyMap()
) : FeatureState