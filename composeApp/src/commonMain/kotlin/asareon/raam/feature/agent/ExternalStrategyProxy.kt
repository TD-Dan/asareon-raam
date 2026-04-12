package asareon.raam.feature.agent

import asareon.raam.core.IdentityHandle
import asareon.raam.core.Store
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * A CognitiveStrategy implementation that delegates turn execution to an external
 * feature via the action bus. Created at runtime when a feature dispatches
 * `agent.REGISTER_EXTERNAL_STRATEGY`.
 *
 * ## Protocol
 * 1. Pipeline assembles context normally (buildPrompt is called, partitions built).
 * 2. In `executeTurn()`, the pipeline detects this is an ExternalStrategyProxy and
 *    dispatches `agent.EXTERNAL_TURN_REQUEST` targeted to [featureHandle].
 * 3. The external feature processes the turn (inspect/modify partitions, run script)
 *    and responds with `agent.EXTERNAL_TURN_RESULT`.
 * 4. Pipeline handles the result based on mode: advance (gateway), custom (direct post),
 *    or error (abort).
 *
 * ## Graceful Degradation
 * If the external feature is unavailable, the turn request goes unhandled and the
 * pipeline's context-gathering timeout fires. The agent shows an error status.
 */
class ExternalStrategyProxy(
    override val identityHandle: IdentityHandle,
    override val displayName: String,
    /** The bus handle of the feature providing this strategy (e.g., "lua"). */
    val featureHandle: String,
    private val declaredSlots: List<ResourceSlot>,
    private val declaredConfigFields: List<StrategyConfigField>,
    private val declaredInitialState: JsonElement
) : CognitiveStrategy {

    override fun getResourceSlots(): List<ResourceSlot> = declaredSlots

    override fun getConfigFields(): List<StrategyConfigField> = declaredConfigFields

    override fun getInitialState(): JsonElement = declaredInitialState

    override fun getValidNvramKeys(): Set<String>? {
        // External strategies manage their own NVRAM — accept all keys
        return null
    }

    /**
     * Build the prompt normally. The assembled result is sent to the external feature
     * as context partitions in EXTERNAL_TURN_REQUEST. The external feature can modify
     * and return them.
     */
    override fun buildPrompt(context: AgentTurnContext, state: JsonElement): PromptBuilder {
        return PromptBuilder(context).apply {
            identity()
            instructions()
            sessions()
            sessionFiles()
            everythingElse()
        }
    }

    /**
     * Post-processing for the gateway response (only used in "advance" mode where the
     * gateway is invoked after the external feature modifies the prompt).
     * Default behavior: pass through without state changes.
     */
    override fun postProcessResponse(response: String, currentState: JsonElement): PostProcessResult {
        return PostProcessResult(currentState, SentinelAction.PROCEED)
    }

    // No additional context from strategy side — the external turn dispatch
    // happens in executeTurn() after assembly, not during context gathering.
    override fun requestAdditionalContext(agent: AgentInstance, store: Store): Boolean = false
    override fun needsAdditionalContext(agent: AgentInstance): Boolean = false

    override fun getBuiltInResources(): List<AgentResource> = emptyList()

    override fun validateConfig(agent: AgentInstance): AgentInstance {
        val outputId = agent.outputSessionId
        if (outputId != null && outputId !in agent.subscribedSessionIds) {
            return agent.copy(outputSessionId = agent.subscribedSessionIds.firstOrNull())
        }
        return agent
    }
}
