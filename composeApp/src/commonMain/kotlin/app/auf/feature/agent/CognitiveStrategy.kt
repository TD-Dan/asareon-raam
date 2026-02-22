package app.auf.feature.agent

import app.auf.core.IdentityHandle
import app.auf.core.Store
import kotlinx.serialization.json.JsonElement

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
 * [PHASE 2] Strategies are now registered identities in the `agent.strategy.*`
 * namespace. The `identityHandle` replaces the old `id: String` and serves as
 * the stable, collision-proof contract enforced by the identity registry.
 *
 * [PHASE 4] Extended with lifecycle hooks. The core runtime now speaks exclusively
 * to this interface — all strategy-specific behavior is encapsulated within each
 * strategy's implementation of these hooks. No implicit strategy checks remain
 * in AgentRuntimeFeature, AgentCognitivePipeline, or AgentCrudLogic.
 */
interface CognitiveStrategy {

    /**
     * The identity handle for this strategy in the `agent.strategy.*` namespace.
     * Examples: `agent.strategy.vanilla`, `agent.strategy.sovereign`.
     *
     * Used as the key in [CognitiveStrategyRegistry], for serialization in
     * [AgentInstance.cognitiveStrategyId], and for UI selection.
     *
     * [PHASE 2] Replaces `val id: String`.
     */
    val identityHandle: IdentityHandle

    /**
     * The human-readable name (e.g., "Sovereign Constitutional").
     */
    val displayName: String

    // =========================================================================
    // Core methods (unchanged since Phase 0)
    // =========================================================================

    /**
     * Declares the resource slots this strategy uses.
     * Used by the UI for slot selectors and by the Pipeline for validation.
     */
    fun getResourceSlots(): List<ResourceSlot>

    /**
     * Returns the initial "NVRAM" state for a freshly created agent.
     * e.g., { "phase": "BOOTING", "rigor": "STANDARD", "knowledgeGraphId": null }
     *
     * [PHASE 4] Strategy-specific configuration that was previously on AgentInstance
     * (e.g., knowledgeGraphId) should be included here as well-known keys with
     * defined defaults. The strategy owns and manages these keys via cognitiveState.
     */
    fun getInitialState(): JsonElement

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
    // [PHASE 4] Lifecycle hooks — all have default no-op implementations
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
     * [E7] Strategy-owned config validation. Called by AgentCrudLogic after
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
     *
     * [PHASE 4] Replaces the implicit `SovereignHKGResourceLogic.requestContextIfSovereign`.
     */
    fun requestAdditionalContext(agent: AgentInstance, store: Store): Boolean = false

    /**
     * Returns true if this strategy expects additional context that the pipeline
     * should wait for (or proceed without on timeout).
     *
     * Used by the context-gathering gate to know whether all expected context
     * has arrived before executing the turn.
     *
     * [PHASE 4] Replaces the implicit `agent.knowledgeGraphId.isNullOrBlank()` check.
     */
    fun needsAdditionalContext(agent: AgentInstance): Boolean = false
}

/**
 * Result of the post-processing phase.
 */
data class PostProcessResult(
    val newState: JsonElement,
    val action: SentinelAction
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
     * The response was a Sentinel success message.
     * Proceed, but potentially hide the raw sentinel text if desired (strategy dependent).
     */
    PROCEED_WITH_UPDATE
}

/**
 * Minimal context required by the strategy to build the prompt.
 */
data class AgentTurnContext(
    val agentName: String,
    val resolvedResources: Map<String, String>, // slotId → content
    val gatheredContexts: Map<String, String>   // source → content
)