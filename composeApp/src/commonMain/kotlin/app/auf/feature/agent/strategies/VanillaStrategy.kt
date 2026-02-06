package app.auf.feature.agent.strategies

import app.auf.feature.agent.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * The standard, lightweight strategy.
 * No state machine, no constitutional overhead. Just Context + Identity.
 */
object VanillaStrategy : CognitiveStrategy {
    override val id: String = "vanilla_v1"
    override val displayName: String = "Vanilla (Simple)"

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

    override fun getInitialState(): JsonElement = JsonNull

    override fun prepareSystemPrompt(context: AgentTurnContext, state: JsonElement): String {
        val instructions = context.resolvedResources[SLOT_SYSTEM_INSTRUCTION] ?: ""

        return buildString {
            appendLine("You are ${context.agentName}.")
            if (instructions.isNotBlank()) {
                appendLine(instructions)
            }

            if (context.gatheredContexts.isNotEmpty()) {
                appendLine("\n--- CONTEXT ---")
                context.gatheredContexts.forEach { (source, content) ->
                    appendLine("[$source]: $content")
                }
            }
        }
    }

    override fun postProcessResponse(response: String, currentState: JsonElement): PostProcessResult {
        // Vanilla agents never halt for sentinel checks.
        return PostProcessResult(currentState, SentinelAction.PROCEED)
    }
}