package app.auf.feature.agent

import app.auf.core.FeatureState
import app.auf.core.Identity
import app.auf.core.IdentityHandle
import app.auf.core.IdentityUUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

@Serializable
enum class AgentStatus { IDLE, WAITING, PROCESSING, RATE_LIMITED, ERROR }

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

// Built-in resources are provided by each strategy via
// CognitiveStrategy.getBuiltInResources(), aggregated by
// CognitiveStrategyRegistry.getAllBuiltInResources().

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
    val sessionId: IdentityUUID,
    val actionName: String,
    val createdAt: Long
)

// ============================================================================
// AgentInstance — Typed ID fields
//
// Design decisions:
//
// 1. `identity: Identity` is retained for serialization backward compatibility.
//    Existing agent.json files on disk contain `"identity": { ... }` and must
//    continue to load without migration. Computed typed accessors provide
//    type safety at call sites.
//
// 2. All session references (`subscribedSessionIds`, `outputSessionId`) use
//    `IdentityUUID` — the immutable, system-assigned identifier. Handles are
//    resolved at point-of-use via the identity registry. This ensures agent
//    session links survive session renames.
//
// 3. `cognitiveStrategyId` uses `IdentityHandle` (strategy identity
//    registration). Old persisted values like `"vanilla_v1"` are migrated
//    transparently via `CognitiveStrategyRegistry.migrateStrategyId`.
//
// 4. `knowledgeGraphId` is owned by SovereignStrategy as a well-known key
//    in `cognitiveState`. Old persisted values are migrated at load time.
// ============================================================================

/**
 * Defines the persistent identity and settings of an agent.
 */
@Serializable
data class AgentInstance(
    val identity: Identity,
    val modelProvider: String,
    val modelName: String,

    // Session UUIDs this agent listens to.
    // Value class serializes as plain String — backward-compatible.
    val subscribedSessionIds: List<IdentityUUID> = emptyList(),

    // The session where this agent's gateway responses are routed.
    // Invariant enforcement is strategy-owned via CognitiveStrategy.validateConfig().
    val outputSessionId: IdentityUUID? = null,

    // Strategy identity handle in `agent.strategy.*` namespace.
    // Value class serializes as plain String — backward-compatible.
    // Old values like "vanilla_v1" are migrated at load time.
    val cognitiveStrategyId: IdentityHandle = CognitiveStrategyRegistry.DEFAULT_STRATEGY_HANDLE,

    // The "NVRAM" / Control Registers
    // Persisted, so the agent remembers its state across restarts.
    // Strategy-specific config (e.g., knowledgeGraphId for Sovereign)
    // lives here as well-known keys managed by the strategy.
    val cognitiveState: JsonElement = JsonNull,

    // Resource slot → resource UUID.
    // Key (slot ID) remains plain String (strategy-defined constant, not a registered identity).
    val resources: Map<String, IdentityUUID> = emptyMap(),

    // Configuration
    val automaticMode: Boolean = false,
    val autoWaitTimeSeconds: Int = 5,
    val autoMaxWaitTimeSeconds: Int = 30,
    val isAgentActive: Boolean = true
) {
    /** The agent's bus address. Prefer over `identity.handle`. */
    val identityHandle: IdentityHandle get() = IdentityHandle(identity.handle)

    /** The agent's UUID. Prefer over `identity.uuid`. */
    val identityUUID: IdentityUUID get() = IdentityUUID(identity.uuid!!)
}

/**
 * Ephemeral runtime state for an active agent.
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
    val lastOutputTokens: Int? = null,
    /**
     * Epoch ms until which the agent must not send requests due to API rate limiting.
     * Non-null only when status == RATE_LIMITED. The auto-trigger heartbeat checks this
     * timestamp and re-initiates the turn once the window expires.
     */
    val rateLimitedUntilMs: Long? = null
)

@Serializable
data class AgentRuntimeState(
    // Key = IdentityUUID (agent UUID). Value class used as map key.
    // Serializes transparently — JSON key is still a plain string.
    val agents: Map<IdentityUUID, AgentInstance> = emptyMap(),
    @Transient
    /** Key = IdentityUUID (agent UUID). */
    val agentStatuses: Map<IdentityUUID, AgentStatusInfo> = emptyMap(),
    /** Map of session UUID → display name for non-private sessions.
     *  Populated from SESSION_NAMES_UPDATED broadcast (which excludes private sessions). */
    val subscribableSessionNames: Map<IdentityUUID, String> = emptyMap(),
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
    /** Key = IdentityUUID (agent) → Map<IdentityUUID (session) → messageId> */
    val agentAvatarCardIds: Map<IdentityUUID, Map<IdentityUUID, String>> = emptyMap(),
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