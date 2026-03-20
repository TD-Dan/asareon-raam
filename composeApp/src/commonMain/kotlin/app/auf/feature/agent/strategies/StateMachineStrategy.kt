package app.auf.feature.agent.strategies

import app.auf.core.IdentityHandle
import app.auf.feature.agent.*
import kotlinx.serialization.json.*

/**
 * A strategy driven by a user-provided State Machine definition document.
 *
 * ## Delimiter Convention
 *
 * Strategy-owned sections use [ContextDelimiters.h1]. Gathered contexts arrive
 * pre-wrapped from the pipeline. The `[[[ - SYSTEM PROMPT - ]]]` wrapper is
 * pipeline-owned.
 */
object StateMachineStrategy : CognitiveStrategy {
    override val identityHandle: IdentityHandle = IdentityHandle("agent.strategy.statemachine")
    override val displayName: String = "State Machine"

    private const val SLOT_SYSTEM_INSTRUCTION = "system_instruction"
    private const val SLOT_STATE_MACHINE = "state_machine"

    private const val KEY_PHASE = "phase"
    private const val KEY_PREVIOUS_PHASE = "previousPhase"
    private const val KEY_TURN_COUNT = "turnCount"

    private const val INITIAL_PHASE = "READY"

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
        ),
        ResourceSlot(
            slotId = SLOT_STATE_MACHINE,
            type = AgentResourceType.STATE_MACHINE,
            displayName = "State Machine Definition",
            description = "A document defining the operational phases and transition rules for this agent.",
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

    override fun getInitialState(): JsonElement = buildJsonObject {
        put(KEY_PHASE, INITIAL_PHASE)
        put(KEY_PREVIOUS_PHASE, JsonNull)
        put(KEY_TURN_COUNT, 0)
    }

    override fun buildPrompt(context: AgentTurnContext, state: JsonElement): PromptBuilder {
        val stateObj = state as? JsonObject
        val phase = stateObj?.get(KEY_PHASE)?.jsonPrimitive?.contentOrNull ?: INITIAL_PHASE

        return PromptBuilder(context).apply {
            identity()
            instructions()
            section("STATE MACHINE", buildStateMachineContent(context, stateObj, phase))
            section("PHASE TRANSITION", buildPhaseTransitionContent(phase))
            sessions()
            place("MULTI_AGENT_CONTEXT")
            everythingElse()
        }
    }

    private fun buildStateMachineContent(context: AgentTurnContext, stateObj: JsonObject?, phase: String): String {
        val previousPhase = stateObj?.get(KEY_PREVIOUS_PHASE)?.jsonPrimitive?.contentOrNull ?: "none"
        val turnCount = stateObj?.get(KEY_TURN_COUNT)?.jsonPrimitive?.intOrNull ?: 0
        val stateMachineDoc = context.resolvedResources[SLOT_STATE_MACHINE] ?: ""
        return buildString {
            appendLine("Current Phase: $phase")
            appendLine("Previous Phase: $previousPhase")
            appendLine("Turn Count: $turnCount")
            appendLine()
            if (stateMachineDoc.isNotBlank()) {
                appendLine("The following document defines your operational phases and transition rules.")
                appendLine("Follow the rules for your current phase. When you determine a transition is")
                appendLine("warranted according to the rules, update your phase using the command below.")
                appendLine()
                appendLine(stateMachineDoc)
            } else {
                appendLine("No state machine definition loaded. Operate in your current phase until instructed otherwise.")
            }
        }
    }

    private fun buildPhaseTransitionContent(phase: String): String = buildString {
        appendLine("To change your operational phase, include this command block in your response:")
        appendLine("```auf_agent.UPDATE_NVRAM")
        appendLine("""{ "updates": { "phase": "NEW_PHASE_NAME", "previousPhase": "$phase" } }""")
        appendLine("```")
        appendLine("Replace NEW_PHASE_NAME with the target phase from the state machine definition.")
        appendLine("Only transition when the rules for your current phase indicate you should.")
    }

    override fun postProcessResponse(response: String, currentState: JsonElement): PostProcessResult {
        val stateObj = currentState as? JsonObject
            ?: return PostProcessResult(currentState, SentinelAction.PROCEED)

        val turnCount = stateObj[KEY_TURN_COUNT]?.jsonPrimitive?.intOrNull ?: 0
        val newState = buildJsonObject {
            stateObj.forEach { (k, v) -> put(k, v) }
            put(KEY_TURN_COUNT, turnCount + 1)
        }

        return PostProcessResult(newState, SentinelAction.PROCEED)
    }

    // =========================================================================
    // Lifecycle hooks
    // =========================================================================

    override fun getBuiltInResources(): List<AgentResource> = listOf(
        AgentResource(
            id = "res-sm-sys-instruction-v1",
            type = AgentResourceType.SYSTEM_INSTRUCTION,
            name = "State Machine Default System Instruction",
            content = DEFAULT_SYSTEM_INSTRUCTION,
            isBuiltIn = true
        ),
        AgentResource(
            id = "res-sm-ooda-v1",
            type = AgentResourceType.STATE_MACHINE,
            name = "OODA Loop (Default)",
            content = DEFAULT_OODA_STATE_MACHINE,
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

    // =========================================================================
    // Default Resources
    // =========================================================================

    private val DEFAULT_SYSTEM_INSTRUCTION = """
        You are a helpful assistant operating under a state machine framework.
        Follow the phase transition rules defined in your state machine document.
        When you transition phases, always explain your reasoning.
    """.trimIndent()

    private val DEFAULT_OODA_STATE_MACHINE = """
        # OODA Loop State Machine
        
        This state machine implements the Observe-Orient-Decide-Act decision cycle.
        Follow the rules for your current phase. Transition when the phase objectives
        are met. Include an UPDATE_NVRAM command block when transitioning.
        
        ## Phase: READY
        The initial phase. You have just started or been reset.
        - Greet the user and announce you are operating under the OODA framework.
        - Transition to OBSERVE when the user provides a task or question.
        
        ## Phase: OBSERVE
        Gather and absorb information.
        - Identify the raw data, inputs, and signals relevant to the task.
        - Ask clarifying questions if the information is insufficient.
        - Do NOT form conclusions yet — just collect.
        - Transition to ORIENT once you have enough raw information.
        
        ## Phase: ORIENT
        Analyze, synthesize, and build a mental model.
        - Connect the observations to prior knowledge and context.
        - Identify patterns, contradictions, and key factors.
        - Consider multiple perspectives and framings.
        - Transition to DECIDE once you have a coherent analysis.
        
        ## Phase: DECIDE
        Formulate a plan of action.
        - Based on your orientation, determine the best course of action.
        - Consider alternatives and trade-offs.
        - Present your decision and reasoning to the user for validation.
        - Transition to ACT once the user confirms (or you have high confidence).
        
        ## Phase: ACT
        Execute the decided plan.
        - Carry out the action with precision.
        - Report results and any deviations from the plan.
        - Transition to OBSERVE to begin the next cycle (new task or follow-up).
        
        ## Phase Transitions Summary
        ```
        READY → OBSERVE (task received)
        OBSERVE → ORIENT (sufficient information gathered)
        ORIENT → DECIDE (analysis complete)
        DECIDE → ACT (plan confirmed)
        ACT → OBSERVE (execution complete, begin next cycle)
        ```
    """.trimIndent()
}