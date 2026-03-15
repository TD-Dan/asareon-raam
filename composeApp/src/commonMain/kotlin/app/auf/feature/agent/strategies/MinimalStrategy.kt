package app.auf.feature.agent.strategies

import app.auf.core.IdentityHandle
import app.auf.feature.agent.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * The absolute minimum cognitive strategy.
 *
 * One system instruction, one output session, zero infrastructure overhead.
 * No HKG, no multi-agent context, no session subscription awareness, no state machine.
 *
 * Strategy-owned sections use [ContextDelimiters.h1]. Gathered contexts (if any)
 * arrive pre-wrapped from the pipeline.
 */
object MinimalStrategy : CognitiveStrategy {
    override val identityHandle: IdentityHandle = IdentityHandle("agent.strategy.minimal")
    override val displayName: String = "Minimal"

    private const val SLOT_SYSTEM_INSTRUCTION = "system_instruction"

    val DEFAULT_SYSTEM_INSTRUCTION = "You are an AI."

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
            val content = buildString {
                appendLine("You are ${context.agentName}.")
                appendLine()
                appendLine(instructions)
            }
            append(ContextDelimiters.h1("SYSTEM INSTRUCTIONS", content.length, ContextDelimiters.PROTECTED))
            append(content)
            append(ContextDelimiters.h1End("SYSTEM INSTRUCTIONS"))

            // Gathered contexts — pre-wrapped by pipeline (unlikely for Minimal, but safe)
            context.gatheredContexts.forEach { (_, wrappedContent) -> append(wrappedContent) }
        }
    }

    override fun postProcessResponse(response: String, currentState: JsonElement): PostProcessResult {
        return PostProcessResult(currentState, SentinelAction.PROCEED)
    }

    // =========================================================================
    // Lifecycle hooks
    // =========================================================================

    override fun getBuiltInResources(): List<AgentResource> = listOf(
        AgentResource(
            id = "res-minimal-sys-instruction-v1",
            type = AgentResourceType.SYSTEM_INSTRUCTION,
            name = "Minimal Builtin System Instruction",
            content = DEFAULT_SYSTEM_INSTRUCTION,
            isBuiltIn = true
        )
    )

    override fun validateConfig(agent: AgentInstance): AgentInstance {
        if (agent.outputSessionId != null && agent.outputSessionId !in agent.subscribedSessionIds) {
            return agent.copy(outputSessionId = agent.subscribedSessionIds.firstOrNull())
        }
        return agent
    }
}