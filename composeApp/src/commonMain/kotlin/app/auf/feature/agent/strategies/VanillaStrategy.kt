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

    override fun getInitialState(): JsonElement = JsonNull

    override fun prepareSystemPrompt(context: AgentTurnContext, state: JsonElement): String {
        val instructions = context.resolvedResources[SLOT_SYSTEM_INSTRUCTION] ?: ""

        return buildString {
            appendLine("\n\n--- YOUR IDENTITY AND ROLE ---\n\n")
            appendLine("You are ${context.agentName}.")
            appendLine("You are a participant in a multi-user, multi-session agent environment named AUF-App.")

            if (instructions.isNotBlank()) {
                appendLine("\n\n--- SYSTEM INSTRUCTIONS ---\n\n")
                appendLine(instructions)
            }

            if (context.gatheredContexts.isNotEmpty()) {
                appendLine("\n\n--- CONTEXT ---\n\n")
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