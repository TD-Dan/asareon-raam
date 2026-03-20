package app.auf.feature.agent

import app.auf.core.FeatureState
import app.auf.core.Identity
import app.auf.core.IdentityHandle
import app.auf.core.IdentityUUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

@Serializable
enum class AgentStatus { IDLE, WAITING, PROCESSING, RATE_LIMITED, ERROR }

@Serializable
enum class TurnMode { DIRECT, PREVIEW }

// ============================================================================
// Phase A: Context Collapse State
// ============================================================================

/**
 * Two-state collapse model for context partitions. §3.1 of the design doc.
 *
 * There is no TRUNCATED state — truncation is a pipeline sentinel for oversized
 * partials, not an architectural state.
 */
@Serializable
enum class CollapseState {
    EXPANDED,   // Show full content
    COLLAPSED   // Show summary/header only
}

@Serializable
enum class AgentResourceType {
    CONSTITUTION,
    BOOTLOADER,
    SYSTEM_INSTRUCTION,
    STATE_MACHINE
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
// 4. `strategyConfig` holds operator-set, strategy-specific configuration values
//    (e.g., `knowledgeGraphId` for Sovereign). These are declared by each strategy
//    via `CognitiveStrategy.getConfigFields()` and rendered generically by the UI.
//    This is distinct from `cognitiveState` (NVRAM), which holds agent-written
//    runtime state (phase, mood, task focus, etc.).
//
// 5. `cognitiveState` (NVRAM / Control Registers) is exclusively for the agent's
//    own persistent self-awareness — data the agent writes about itself via
//    UPDATE_NVRAM. It is never written by the operator or UI.
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
    // Contains ONLY agent-written runtime state (e.g., cognitive phase, current task,
    // mood). Strategy-specific operator configuration lives in `strategyConfig`.
    val cognitiveState: JsonElement = JsonNull,

    // Resource slot → resource UUID.
    // Key (slot ID) remains plain String (strategy-defined constant, not a registered identity).
    val resources: Map<String, IdentityUUID> = emptyMap(),

    // Strategy-specific configuration values set by the operator (e.g., knowledgeGraphId
    // for Sovereign). Declared by CognitiveStrategy.getConfigFields(), rendered generically
    // by the UI, and stored here — NOT in cognitiveState (NVRAM).
    // This is "what the operator configured" vs. cognitiveState which is "what the agent
    // wrote about itself."
    val strategyConfig: JsonObject = JsonObject(emptyMap()),

    // ========================================================================
    // Phase A: Context Budget Configuration (§3.2)
    //
    // Per-agent, operator-configured. The UI displays these as approximate tokens.
    // Strategies can recommend defaults.
    // ========================================================================

    /** Soft target for context size in characters. ~50,000 tokens at ≈4 chars/token. */
    val contextBudgetChars: Int = 200_000,
    /** Hard maximum for context size in characters. ~125,000 tokens. Safety net ceiling. */
    val contextMaxBudgetChars: Int = 500_000,
    /** Maximum single-partial size in characters before truncation sentinel fires. ~25,000 tokens. */
    val contextMaxPartialChars: Int = 100_000,

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

    // ========================================================================
    // Managed Context Session (§9.1)
    //
    // Active while the "Manage Context" view is open for this agent.
    // managedContext holds the full assembly snapshot (including transient data)
    // so that IDLE transitions don't destroy data needed for reassembly (F2 fix).
    // ========================================================================

    /** Full assembly result. Available while Manage Context view is open. */
    val managedContext: ContextAssemblyResult? = null,
    /** Fast-path partition data for Tab 0. Updated on every collapse toggle. */
    val managedPartitions: PartitionAssemblyResult? = null,
    /** Raw JSON from debounced gateway preview. */
    val managedContextRawJson: String? = null,
    /** Provider-estimated input tokens from debounced gateway preview. */
    val managedContextEstimatedTokens: Int? = null,

    val stagedTurnContext: List<GatewayMessage>? = null,
    val transientHkgContext: JsonObject? = null,

    // ========================================================================
    // Workspace Context (Two-Partition Model)
    //
    // Raw listing from filesystem.RETURN_LIST — used by WorkspaceContextFormatter
    // to build the WORKSPACE_INDEX tree with collapse badges.
    // ========================================================================

    /** Raw workspace listing entries from filesystem.RETURN_LIST. Null = not yet received. */
    val transientWorkspaceListing: JsonArray? = null,
    /** File contents for EXPANDED workspace files. Key = relative path, value = content. */
    val transientWorkspaceFileContents: Map<String, String> = emptyMap(),
    /** True while a filesystem.READ_MULTIPLE request for expanded workspace files is in-flight. */
    val pendingWorkspaceFileReads: Boolean = false,

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
    val rateLimitedUntilMs: Long? = null,
    /**
     * Display-only label set by the cognitive strategy via [PostProcessResult.displayHint].
     * Rendered by the avatar card alongside the runtime status. Has no effect on
     * runtime flow control — purely informational (e.g., "Booting", "Reflecting").
     * Null = no label. Cleared when the next turn begins.
     */
    val strategyDisplayHint: String? = null,

    // ========================================================================
    // Phase A: Context Collapse Overrides (§3.7)
    //
    // Agent collapse/uncollapse choices persist across turns until explicitly changed.
    // Persisted to context.json. The pipeline respects sticky overrides up to the
    // hard maximum.
    // ========================================================================

    /** Sticky collapse overrides. Key = partition key or "hkg:<holonId>". */
    val contextCollapseOverrides: Map<String, CollapseState> = emptyMap(),

    // ========================================================================
    // Phase A: Pending Private Session Guard (§5.2)
    //
    // Set when a SESSION_CREATE has been dispatched but SESSION_CREATED has not
    // yet arrived. Prevents duplicate session creation on rapid heartbeat ticks.
    // ========================================================================

    /** True when a private session creation is in-flight. */
    val pendingPrivateSessionCreation: Boolean = false,

    // ========================================================================
    // Phase B.2: Multi-Session Ledger Accumulation
    //
    // When a turn starts, the pipeline dispatches N REQUEST_LEDGER_CONTENT
    // actions (one per subscribed session). As responses arrive, each session's
    // messages are accumulated here. When pendingLedgerSessionIds is empty,
    // all ledgers have arrived and the turn can proceed.
    // ========================================================================

    /** Session UUIDs whose ledger responses have not yet arrived. Empty = all received. */
    val pendingLedgerSessionIds: Set<IdentityUUID> = emptySet(),

    /** Accumulated per-session ledger messages. Key = session UUID, value = enriched messages. */
    val accumulatedSessionLedgers: Map<IdentityUUID, List<GatewayMessage>> = emptyMap()
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
    val managingContextForAgentId: IdentityUUID? = null,
    @Transient
    val lastAutoTriggerAgentIndex: Int = 0,

    // --- Transient State for Command Result Routing ---
    /** Maps correlationId → AgentPendingCommand for in-flight agent commands.
     *  Used to route targeted RETURN_* data to the session via DELIVER_TO_SESSION. */
    @Transient
    val pendingCommands: Map<String, AgentPendingCommand> = emptyMap()
) : FeatureState