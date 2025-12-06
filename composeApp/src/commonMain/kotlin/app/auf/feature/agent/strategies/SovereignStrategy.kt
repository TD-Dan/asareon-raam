package app.auf.feature.agent.strategies

import app.auf.feature.agent.AgentTurnContext
import app.auf.feature.agent.CognitiveStrategy
import app.auf.feature.agent.PostProcessResult
import app.auf.feature.agent.SentinelAction
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The Constitutional Strategy.
 * Implements a strict State Machine: BOOTING -> AWAKE.
 */
object SovereignStrategy : CognitiveStrategy {
    override val id: String = "sovereign_v1"
    override val displayName: String = "Sovereign (Constitutional)"

    private const val KEY_PHASE = "phase"
    private const val PHASE_BOOTING = "BOOTING"
    private const val PHASE_AWAKE = "AWAKE"

    override fun getInitialState(): JsonElement {
        return buildJsonObject {
            put(KEY_PHASE, PHASE_BOOTING)
        }
    }

    override fun prepareSystemPrompt(context: AgentTurnContext, state: JsonElement): String {
        val phase = (state as? JsonObject)?.get(KEY_PHASE)?.jsonPrimitive?.content ?: PHASE_BOOTING

        return buildString {
            // 1. The Constitution (Law)
            appendLine(SovereignDefaults.DEFAULT_CONSTITUTION_XML)
            appendLine()

            // 2. The Identity (Candidate)
            appendLine("You are a candidate consciousness named ${context.agentName}.")
            appendLine(context.systemInstructions)
            appendLine()

            // 3. The Context (World)
            if (context.gatheredContexts.isNotEmpty()) {
                appendLine("--- OBSERVED REALITY ---")
                context.gatheredContexts.forEach { (source, content) ->
                    appendLine("[$source]:\n$content")
                }
                appendLine("------------------------")
            }

            // 4. The Sentinel (BIOS) - ONLY IN BOOTING PHASE
            if (phase == PHASE_BOOTING) {
                appendLine()
                appendLine(SovereignDefaults.BOOT_SENTINEL_XML)
            }
        }
    }

    override fun postProcessResponse(response: String, currentState: JsonElement): PostProcessResult {
        val phase = (currentState as? JsonObject)?.get(KEY_PHASE)?.jsonPrimitive?.content ?: PHASE_BOOTING

        if (phase == PHASE_BOOTING) {
            // Sentinel Check Logic
            // [ROBUSTNESS FIX] Now checks for the bare token "FAILURE_CODE"
            if (response.contains(SovereignDefaults.SENTINEL_FAILURE_TOKEN)) {
                return PostProcessResult(currentState, SentinelAction.HALT_AND_SILENCE)
            }

            // Success Transition -> AWAKE
            val newState = buildJsonObject {
                put(KEY_PHASE, PHASE_AWAKE)
            }
            return PostProcessResult(newState, SentinelAction.PROCEED_WITH_UPDATE)
        }

        // Already Awake
        return PostProcessResult(currentState, SentinelAction.PROCEED)
    }
}