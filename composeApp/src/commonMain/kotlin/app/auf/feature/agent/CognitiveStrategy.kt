package app.auf.feature.agent

import app.auf.core.IdentityHandle
import app.auf.core.Store
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Declares a strategy-specific configuration field rendered by the Agent Manager UI.
 * Analogous to [ResourceSlot] but for config values stored in cognitiveState.
 *
 * The core UI renders these generically — it never inspects which strategy is active.
 */
data class StrategyConfigField(
    val key: String,
    val type: StrategyConfigFieldType,
    val displayName: String,
    val description: String = ""
)

enum class StrategyConfigFieldType {
    /** Rendered as a Knowledge Graph dropdown selector. */
    KNOWLEDGE_GRAPH,
    /** Rendered as a dropdown of the agent's subscribed sessions to select the output target. */
    OUTPUT_SESSION
}

/**
 * Declares a resource slot that a strategy requires.
 * Used by the UI to render slot selectors and by the Pipeline to validate/resolve resources.
 */
data class ResourceSlot(
    val slotId: String,              // e.g., "constitution", "bootloader", "system_instruction"
    val type: AgentResourceType,
    val displayName: String,
    val description: String,
    val isRequired: Boolean = true
)

/**
 * Defines the cognitive architecture of an Agent.
 *
 * This strategy pattern allows the AUF App to host diverse forms of agency
 * (e.g., "Vanilla", "Sovereign", "Scripted") by decoupling the lifecycle and
 * prompt construction from the runtime engine.
 *
 * It manages the "Control Registers" (cognitiveState) of the agent, acting as the
 * Operating System that translates the Agent's static identity (HKG) and current
 * volatility (State) into a System Prompt.
 *
 * Strategies are registered identities in the `agent.strategy.*` namespace.
 * The `identityHandle` serves as the stable, collision-proof contract enforced
 * by the identity registry.
 *
 * The core runtime speaks exclusively to this interface — all strategy-specific
 * behavior is encapsulated within each strategy's implementation of these hooks.
 * No implicit strategy checks remain in the runtime, pipeline, or CRUD logic.
 */
interface CognitiveStrategy {

    /**
     * The identity handle for this strategy in the `agent.strategy.*` namespace.
     * Examples: `agent.strategy.vanilla`, `agent.strategy.sovereign`.
     *
     * Used as the key in [CognitiveStrategyRegistry], for serialization in
     * [AgentInstance.cognitiveStrategyId], and for UI selection.
     */
    val identityHandle: IdentityHandle

    /**
     * The human-readable name (e.g., "Sovereign Constitutional").
     */
    val displayName: String

    // =========================================================================
    // Core methods
    // =========================================================================

    /**
     * Declares the resource slots this strategy uses.
     * Used by the UI for slot selectors and by the Pipeline for validation.
     */
    fun getResourceSlots(): List<ResourceSlot>

    /**
     * Declares strategy-specific configuration fields that should be rendered
     * in the Agent Manager UI under a "Strategy Settings" section.
     *
     * The core UI renders these generically based on [StrategyConfigFieldType].
     * Strategies with no extra settings return an empty list (the default).
     *
     * Values are stored as well-known keys in [AgentInstance.cognitiveState],
     * managed by the strategy.
     */
    fun getConfigFields(): List<StrategyConfigField> = emptyList()

    /**
     * Returns the initial "NVRAM" state for a freshly created agent.
     * e.g., { "phase": "BOOTING", "rigor": "STANDARD", "knowledgeGraphId": null }
     *
     * Strategy-specific configuration (e.g., knowledgeGraphId for Sovereign)
     * should be included here as well-known keys with defined defaults.
     * The strategy owns and manages these keys via cognitiveState.
     */
    fun getInitialState(): JsonElement

    /**
     * Returns the set of valid NVRAM keys that this strategy recognizes.
     *
     * Used by the reducer to validate incoming `UPDATE_NVRAM` payloads — keys
     * not in this set are dropped with a warning log. This enforces the
     * principle that strategies own their NVRAM schema.
     *
     * Default implementation derives keys from [getInitialState]. Strategies
     * with dynamic key sets (rare) can override.
     *
     * Returns null if the strategy imposes no key restrictions (e.g., Minimal
     * with JsonNull initial state). Null = all keys accepted (no validation).
     */
    fun getValidNvramKeys(): Set<String>? {
        val initial = getInitialState()
        return (initial as? JsonObject)?.keys
    }

    /**
     * The Core Function.
     * Purely transforms Context + State into the System Prompt.
     *
     * This allows the strategy to dynamically inject protocols based on the
     * current 'rigor' register, or inject Sentinel checks based on the 'phase' register.
     */
    fun prepareSystemPrompt(
        context: AgentTurnContext,
        state: JsonElement
    ): String

    /**
     * Analyzes the Agent's raw text response to determine if a state transition is required.
     *
     * Used primarily for Sentinel Checks (e.g., transitioning BOOT -> AWAKE).
     *
     * @return A result indicating if the turn should proceed or HALT, and the new state.
     */
    fun postProcessResponse(
        response: String,
        currentState: JsonElement
    ): PostProcessResult

    // =========================================================================
    // Lifecycle hooks — all have default no-op implementations
    //
    // The runtime calls these polymorphically on all agents. Strategy-specific
    // behavior (HKG reservation, session linking, etc.) is fully encapsulated
    // within each strategy's implementation. The core runtime never checks
    // which strategy an agent is using.
    // =========================================================================

    /**
     * Returns the built-in [AgentResource] objects this strategy ships with.
     * Called by [CognitiveStrategyRegistry.getAllBuiltInResources] at init time
     * to seed the resource catalog.
     *
     * Default: empty list (strategies with no built-in resources need not override).
     */
    fun getBuiltInResources(): List<AgentResource> = emptyList()

    /**
     * Called once after an agent is created and its identity is registered.
     * Use for one-time setup: infrastructure reservation, initial state seeding, etc.
     */
    fun onAgentRegistered(agent: AgentInstance, store: Store) {}

    /**
     * Called after every AGENT_UPDATE_CONFIG that changes the agent's configuration.
     * Both old and new state are provided for diff-based reactions.
     * Use for detecting configuration transitions (e.g., HKG assignment/revocation).
     */
    fun onAgentConfigChanged(old: AgentInstance, new: AgentInstance, store: Store) {}

    /**
     * Called periodically by the runtime (on the same heartbeat as auto-trigger checks)
     * to allow the strategy to verify and repair its infrastructure.
     * Use for session linking, reservation renewal, etc.
     * Must be idempotent — it will be called repeatedly.
     */
    fun ensureInfrastructure(agent: AgentInstance, agentState: AgentRuntimeState, store: Store) {}

    /**
     * Strategy-owned config validation. Called by AgentCrudLogic after
     * AGENT_UPDATE_CONFIG to let the strategy validate or repair config fields.
     *
     * Example: VanillaStrategy enforces outputSessionId ∈ subscribedSessionIds.
     * SovereignStrategy permits out-of-band outputSessionId (cognition session).
     *
     * Returns the (possibly corrected) agent instance.
     * Default: returns the agent unchanged (no validation).
     */
    fun validateConfig(agent: AgentInstance): AgentInstance = agent

    /**
     * Called by the cognitive pipeline to request any additional context this strategy
     * needs before assembling the prompt (e.g., HKG context for Sovereign).
     *
     * This is an async dispatch — the strategy fires context request actions and the
     * pipeline waits for responses. Returns true if additional context was requested
     * (the pipeline should wait for it), false otherwise.
     */
    fun requestAdditionalContext(agent: AgentInstance, store: Store): Boolean = false

    /**
     * Returns true if this strategy expects additional context that the pipeline
     * should wait for (or proceed without on timeout).
     *
     * Used by the context-gathering gate to know whether all expected context
     * has arrived before executing the turn.
     */
    fun needsAdditionalContext(agent: AgentInstance): Boolean = false
}

/**
 * Result of the post-processing phase.
 *
 * @param newState The (possibly updated) cognitive state.
 * @param action The sentinel action controlling response handling.
 * @param displayHint Optional display label for the agent's post-turn state,
 *   shown on the avatar card for user visibility (e.g., "Booting", "Reflecting").
 *   Purely informational — does NOT affect runtime status ([AgentStatus]),
 *   auto-triggers, turn guards, or any other runtime behavior.
 *   Stored on [AgentStatusInfo.strategyDisplayHint]. Null clears any previous label.
 */
data class PostProcessResult(
    val newState: JsonElement,
    val action: SentinelAction,
    val displayHint: String? = null
)

enum class SentinelAction {
    /** Proceed with tool execution or publishing the response. */
    PROCEED,

    /**
     * The response was a Sentinel control code (e.g., Integrity Failure).
     * Do not show to user/ledger. Halt the loop.
     */
    HALT_AND_SILENCE,

    /**
     * A state transition occurred (e.g., BOOTING → AWAKE) and the new state
     * should be persisted. The response is posted normally — for Sovereign boot,
     * this is the persona's first conscious act. Functionally identical to
     * [PROCEED] for response handling; the distinction enables targeted logging
     * and future UI indicators (e.g., "state transition" badge on the turn).
     */
    PROCEED_WITH_UPDATE
}

/**
 * Describes a participant in a session, derived from ledger messages.
 */
data class SessionParticipant(
    val senderId: String,
    val senderName: String,
    /** e.g., "Human User", "AI Agent", "YOU (this agent)", "User/System" */
    val type: String,
    val messageCount: Int
)

/**
 * Describes a session the agent is subscribed to, enriched with display name,
 * output flag, and participant roster derived from the conversation log.
 */
data class SessionInfo(
    val uuid: String,
    val handle: String,
    val name: String,
    val isOutput: Boolean,
    val participants: List<SessionParticipant> = emptyList(),
    val messageCount: Int = 0
)

/**
 * Minimal context required by the strategy to build the prompt.
 */
data class AgentTurnContext(
    val agentName: String,
    val resolvedResources: Map<String, String>, // slotId → content
    val gatheredContexts: Map<String, String>,  // source → content
    /** All sessions the agent is subscribed to, enriched with names and output flag. */
    val subscribedSessions: List<SessionInfo> = emptyList(),
    /** The output session UUID (used by the pipeline for equality comparison). */
    val outputSessionUUID: String? = null,
    /** The output session handle (used by strategies for display in prompts). */
    val outputSessionHandle: String? = null
)