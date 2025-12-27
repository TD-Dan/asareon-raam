package app.auf.feature.agent.strategies

import app.auf.feature.agent.AgentTurnContext
import app.auf.feature.agent.CognitiveStrategy
import app.auf.feature.agent.PostProcessResult
import app.auf.feature.agent.SentinelAction
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * The standard, lightweight strategy.
 * No state machine, no constitutional overhead. Just Context + Identity.
 */
object VanillaStrategy : CognitiveStrategy {
    override val id: String = "vanilla_v1"
    override val displayName: String = "Vanilla (Simple)"

    override fun getInitialState(): JsonElement = JsonNull

    override fun prepareSystemPrompt(context: AgentTurnContext, state: JsonElement): String {
        return buildString {
            appendLine("You are ${context.agentName}.")
            appendLine(context.systemInstructions)

            if (context.gatheredContexts.isNotEmpty()) {
                appendLine("\n--- CONTEXT ---\n")
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