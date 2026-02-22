package app.auf.feature.agent.strategies

import app.auf.core.IdentityHandle
import app.auf.feature.agent.* // Allowed: this is inter-feature import
import kotlinx.serialization.json.*

/**
 * The Constitutional Strategy.
 * Implements a strict State Machine: BOOTING -> AWAKE.
 *
 * In BOOTING phase, the system prompt includes both Constitution and Bootloader.
 * The agent uses UPDATE_NVRAM to transition to AWAKE, at which point only the Constitution remains.
 *
 * [PHASE 2] `id` replaced by `identityHandle` in the `agent.strategy.*` namespace.
 */
object SovereignStrategy : CognitiveStrategy {
    override val identityHandle: IdentityHandle = IdentityHandle("agent.strategy.sovereign")
    override val displayName: String = "Sovereign (Constitutional)"

    private const val SLOT_CONSTITUTION = "constitution"
    private const val SLOT_BOOTLOADER = "bootloader"
    private const val KEY_PHASE = "phase"
    private const val PHASE_BOOTING = "BOOTING"
    private const val PHASE_AWAKE = "AWAKE"

    override fun getResourceSlots(): List<ResourceSlot> = listOf(
        ResourceSlot(
            slotId = SLOT_CONSTITUTION,
            type = AgentResourceType.CONSTITUTION,
            displayName = "Constitution",
            description = "The fundamental law that governs this agent's behavior.",
            isRequired = true
        ),
        ResourceSlot(
            slotId = SLOT_BOOTLOADER,
            type = AgentResourceType.BOOTLOADER,
            displayName = "Bootloader (Sentinel)",
            description = "The integrity check protocol executed during the BOOTING phase.",
            isRequired = true
        )
    )

    override fun getInitialState(): JsonElement {
        return buildJsonObject {
            put(KEY_PHASE, PHASE_BOOTING)
        }
    }

    override fun prepareSystemPrompt(context: AgentTurnContext, state: JsonElement): String {
        val phase = (state as? JsonObject)?.get(KEY_PHASE)?.jsonPrimitive?.content ?: PHASE_BOOTING

        val constitution = context.resolvedResources[SLOT_CONSTITUTION] ?: ""
        val bootloader = context.resolvedResources[SLOT_BOOTLOADER] ?: ""

        return buildString {
            // 1. The Constitution (Always present)
            if (constitution.isNotBlank()) {
                appendLine(constitution)
                appendLine()
            }

            // 2. The Identity
            appendLine("You are a candidate consciousness named ${context.agentName}.")
            appendLine()

            // 3. The Context (World)
            if (context.gatheredContexts.isNotEmpty()) {
                appendLine("--- OBSERVED REALITY ---")
                context.gatheredContexts.forEach { (source, content) ->
                    appendLine("[$source]:\n$content")
                }
                appendLine("------------------------")
                appendLine()
            }

            // 4. The Bootloader (ONLY in BOOTING phase)
            if (phase == PHASE_BOOTING && bootloader.isNotBlank()) {
                appendLine(bootloader)
            }
        }
    }

    override fun postProcessResponse(response: String, currentState: JsonElement): PostProcessResult {
        val phase = (currentState as? JsonObject)?.get(KEY_PHASE)?.jsonPrimitive?.content ?: PHASE_BOOTING

        if (phase == PHASE_BOOTING) {
            // Check for sentinel failure
            if (response.contains(SovereignDefaults.SENTINEL_FAILURE_TOKEN)) {
                return PostProcessResult(currentState, SentinelAction.HALT_AND_SILENCE)
            }

            return PostProcessResult(currentState, SentinelAction.PROCEED_WITH_UPDATE)
        }

        // Already Awake
        return PostProcessResult(currentState, SentinelAction.PROCEED)
    }
}