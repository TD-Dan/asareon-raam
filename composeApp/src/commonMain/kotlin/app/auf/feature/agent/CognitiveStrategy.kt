package app.auf.feature.agent

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
 */
interface CognitiveStrategy {

    /**
     * The unique identifier for this strategy (e.g., "vanilla_v1", "sovereign_v1").
     * Used for serialization and UI selection.
     */
    val id: String

    /**
     * The human-readable name (e.g., "Sovereign Constitutional").
     */
    val displayName: String

    /**
     * Declares the resource slots this strategy uses.
     * Used by the UI for slot selectors and by the Pipeline for validation.
     */
    fun getResourceSlots(): List<ResourceSlot>

    /**
     * Returns the initial "NVRAM" state for a freshly created agent.
     * e.g., { "phase": "BOOTING", "rigor": "STANDARD" }
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