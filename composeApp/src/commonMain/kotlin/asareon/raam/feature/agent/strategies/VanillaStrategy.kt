package app.auf.feature.agent.strategies

import app.auf.core.IdentityHandle
import app.auf.feature.agent.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * The standard, lightweight strategy.
 * No state machine, no constitutional overhead. Just Context + Identity.
 *
 * ## Delimiter Convention
 *
 * Strategy-owned sections (IDENTITY, INSTRUCTIONS, SUBSCRIBED SESSIONS) use
 * [ContextDelimiters.h1] for consistent formatting. Gathered contexts arrive
 * pre-wrapped with h1 headers from the pipeline — this strategy includes them
 * directly without additional wrapping.
 *
 * The outermost `[[[ - SYSTEM PROMPT - ]]]` wrapper is pipeline-owned.
 */
object VanillaStrategy : CognitiveStrategy {
    override val identityHandle: IdentityHandle = IdentityHandle("agent.strategy.vanilla")
    override val displayName: String = "Vanilla (Simple)"

    val DEFAULT_SYSTEM_INSTRUCTION_XML = """
        You are a helpful assistant.
    """.trimIndent()

    private const val SLOT_SYSTEM_INSTRUCTION = "system_instruction"

    override fun getResourceSlots(): List<ResourceSlot> = listOf(
        ResourceSlot(
            slotId = SLOT_SYSTEM_INSTRUCTION,
            type = AgentResourceType.SYSTEM_INSTRUCTION,
            displayName = "System Instructions",
            description = "The identity and behavior instructions for this agent.",
            isRequired = true
        )
    )

    override fun getConfigFields(): List<StrategyConfigField> = listOf(
        StrategyConfigField(
            key = "outputSessionId",
            type = StrategyConfigFieldType.OUTPUT_SESSION,
            displayName = "Primary Session",
            description = "The session where this agent's responses and tool results are routed."
        )
    )

    override fun getInitialState(): JsonElement = JsonNull

    override fun buildPrompt(context: AgentTurnContext, state: JsonElement) =
        PromptBuilder(context).apply {
            identity()
            instructions()
            sessions()
            sessionFiles()
            everythingElse()
        }

    override fun postProcessResponse(response: String, currentState: JsonElement): PostProcessResult {
        return PostProcessResult(currentState, SentinelAction.PROCEED)
    }

    // =========================================================================
    // Lifecycle hooks
    // =========================================================================

    override fun getBuiltInResources(): List<AgentResource> = listOf(
        AgentResource(
            id = "res-sys-instruction-v1",
            type = AgentResourceType.SYSTEM_INSTRUCTION,
            name = "Default Builtin System Instruction",
            content = DEFAULT_SYSTEM_INSTRUCTION_XML,
            isBuiltIn = true
        )
    )

    override fun validateConfig(agent: AgentInstance): AgentInstance {
        val outputId = agent.outputSessionId
        return when {
            outputId != null && outputId !in agent.subscribedSessionIds ->
                agent.copy(outputSessionId = agent.subscribedSessionIds.firstOrNull())
            outputId == null && agent.subscribedSessionIds.isNotEmpty() ->
                agent.copy(outputSessionId = agent.subscribedSessionIds.first())
            else -> agent
        }
    }
}