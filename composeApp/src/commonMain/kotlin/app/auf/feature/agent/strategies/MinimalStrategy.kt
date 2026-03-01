package app.auf.feature.agent.strategies

import app.auf.core.IdentityHandle
import app.auf.feature.agent.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * The absolute minimum cognitive strategy.
 *
 * Designed for the simplest possible agent: one system instruction, one output
 * session, zero infrastructure overhead. No HKG, no multi-agent context, no
 * session subscription awareness, no state machine.
 *
 * Use this when you need an agent to respond in a session and nothing else.
 *
 * Compared to [VanillaStrategy]:
 * - No session subscription awareness injected into the prompt.
 * - No multi-agent context gathering.
 * - No [getConfigFields] — the output session is the sole subscribed session
 *   and is enforced by [validateConfig], same as Vanilla.
 * - System prompt is the raw system instruction only, prefixed by the agent name.
 */
object MinimalStrategy : CognitiveStrategy {
    override val identityHandle: IdentityHandle = IdentityHandle("agent.strategy.minimal")
    override val displayName: String = "Minimal"

    private const val SLOT_SYSTEM_INSTRUCTION = "system_instruction"

    val DEFAULT_SYSTEM_INSTRUCTION = "You are an AI."

    // =========================================================================
    // Core methods
    // =========================================================================

    override fun getResourceSlots(): List<ResourceSlot> = listOf(
        ResourceSlot(
            slotId = SLOT_SYSTEM_INSTRUCTION,
            type = AgentResourceType.SYSTEM_INSTRUCTION,
            displayName = "System Instructions",
            description = "The identity and behavior instructions for this agent.",
            isRequired = true
        )
    )

    override fun getInitialState(): JsonElement = JsonNull

    override fun prepareSystemPrompt(context: AgentTurnContext, state: JsonElement): String {
        val instructions = context.resolvedResources[SLOT_SYSTEM_INSTRUCTION]
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_SYSTEM_INSTRUCTION

        return buildString {
            appendLine("You are ${context.agentName}.")
            appendLine()
            appendLine(instructions)
        }
    }

    override fun postProcessResponse(response: String, currentState: JsonElement): PostProcessResult {
        return PostProcessResult(currentState, SentinelAction.PROCEED)
    }

    // =========================================================================
    // Lifecycle hooks
    // =========================================================================

    /**
     * Provides the built-in default system instruction resource.
     */
    override fun getBuiltInResources(): List<AgentResource> = listOf(
        AgentResource(
            id = "res-minimal-sys-instruction-v1",
            type = AgentResourceType.SYSTEM_INSTRUCTION,
            name = "Minimal Builtin System Instruction",
            content = DEFAULT_SYSTEM_INSTRUCTION,
            isBuiltIn = true
        )
    )

    /**
     * Enforces the same invariant as [VanillaStrategy]: outputSessionId must be
     * a member of subscribedSessionIds (or null). Since Minimal exposes no
     * separate output session config field, the output session is always expected
     * to be the sole subscribed session.
     */
    override fun validateConfig(agent: AgentInstance): AgentInstance {
        if (agent.outputSessionId != null && agent.outputSessionId !in agent.subscribedSessionIds) {
            return agent.copy(outputSessionId = agent.subscribedSessionIds.firstOrNull())
        }
        return agent
    }
}