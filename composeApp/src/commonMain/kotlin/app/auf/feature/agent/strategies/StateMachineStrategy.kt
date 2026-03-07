package app.auf.feature.agent.strategies

import app.auf.core.IdentityHandle
import app.auf.feature.agent.*
import kotlinx.serialization.json.*

/**
 * A strategy driven by a user-provided State Machine definition document.
 *
 * Designed as a clean test harness for NVRAM read/write cycles, and as a
 * general-purpose framework for phased agent behavior (OODA, plan-execute-reflect,
 * custom workflows, etc.).
 *
 * ## Design
 * - The state machine document (a markdown resource) defines phases and transition rules
 *   in natural language. The strategy does NOT parse the markdown structurally — it
 *   includes the full document in the system prompt and tells the agent its current phase.
 * - The agent reads the rules for its current phase and calls `UPDATE_NVRAM` when it
 *   determines a transition is warranted.
 * - `postProcessResponse` increments the turn counter but does NOT enforce transitions —
 *   phase changes are agent-driven via the NVRAM self-write path.
 *
 * ## NVRAM Schema (3 registers)
 * - `phase`: Current operational phase (string). Initialized to "READY".
 * - `previousPhase`: The phase before the last transition (string|null). For audit trail.
 * - `turnCount`: Monotonically increasing turn counter (int). Incremented by postProcess.
 *
 * ## Resource Slots
 * - `system_instruction`: Identity and behavioral instructions.
 * - `state_machine`: The state machine definition document (markdown).
 *
 * ## Lifecycle
 * - Session subscription awareness (like Vanilla).
 * - `validateConfig` enforces outputSessionId ∈ subscribedSessionIds (like Vanilla).
 * - Built-in resources provide a default OODA loop definition.
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

    override fun prepareSystemPrompt(context: AgentTurnContext, state: JsonElement): String {
        val stateObj = state as? JsonObject
        val phase = stateObj?.get(KEY_PHASE)?.jsonPrimitive?.contentOrNull ?: INITIAL_PHASE
        val previousPhase = stateObj?.get(KEY_PREVIOUS_PHASE)?.jsonPrimitive?.contentOrNull ?: "none"
        val turnCount = stateObj?.get(KEY_TURN_COUNT)?.jsonPrimitive?.intOrNull ?: 0

        val instructions = context.resolvedResources[SLOT_SYSTEM_INSTRUCTION] ?: ""
        val stateMachineDoc = context.resolvedResources[SLOT_STATE_MACHINE] ?: ""

        return buildString {
            // 1. Identity
            appendLine("--- YOUR IDENTITY AND ROLE ---")
            appendLine()
            appendLine("You are ${context.agentName}.")
            appendLine("You are a participant in a multi-user, multi-session agent environment.")
            appendLine("Maintain your own boundaries and role, do not respond on behalf of other participants.")

            // 2. System Instructions
            if (instructions.isNotBlank()) {
                appendLine()
                appendLine("--- SYSTEM INSTRUCTIONS ---")
                appendLine()
                appendLine(instructions)
            }

            // 3. State Machine — current state + full document
            appendLine()
            appendLine("--- STATE MACHINE ---")
            appendLine()
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

            // 4. Phase transition instruction
            appendLine()
            appendLine("--- PHASE TRANSITION ---")
            appendLine()
            appendLine("To change your operational phase, include this command block in your response:")
            appendLine("```auf_agent.UPDATE_NVRAM")
            appendLine("""{ "updates": { "phase": "NEW_PHASE_NAME", "previousPhase": "$phase" } }""")
            appendLine("```")
            appendLine("Replace NEW_PHASE_NAME with the target phase from the state machine definition.")
            appendLine("Only transition when the rules for your current phase indicate you should.")

            // 5. Session subscription awareness
            if (context.subscribedSessions.isNotEmpty()) {
                appendLine()
                appendLine("--- SUBSCRIBED SESSIONS ---")
                appendLine("You are currently subscribed to the following sessions:")
                context.subscribedSessions.forEach { session ->
                    val primaryTag = if (session.isOutput || (context.outputSessionHandle == null && session == context.subscribedSessions.first())) {
                        " [PRIMARY — Your output and tool results are routed here]"
                    } else {
                        ""
                    }
                    appendLine("  - ${session.name} (${session.handle})$primaryTag")
                }
                appendLine("You observe messages from all subscribed sessions. Your responses are posted to the primary session.")
                appendLine()
            }

            // 6. Multi-agent context
            context.gatheredContexts["MULTI_AGENT_CONTEXT"]?.let {
                appendLine(it)
            }

            // 7. Other contexts
            val otherContexts = context.gatheredContexts.filterKeys { it != "MULTI_AGENT_CONTEXT" }
            if (otherContexts.isNotEmpty()) {
                appendLine()
                appendLine("--- CONTEXT ---")
                otherContexts.forEach { (source, content) ->
                    appendLine("[$source]:\n $content\n")
                }
            }
        }
    }

    override fun postProcessResponse(response: String, currentState: JsonElement): PostProcessResult {
        val stateObj = currentState as? JsonObject
            ?: return PostProcessResult(currentState, SentinelAction.PROCEED)

        val turnCount = stateObj[KEY_TURN_COUNT]?.jsonPrimitive?.intOrNull ?: 0

        // Increment turn counter. Phase transitions are agent-driven via UPDATE_NVRAM.
        val newState = buildJsonObject {
            stateObj.forEach { (k, v) -> put(k, v) }
            put(KEY_TURN_COUNT, turnCount + 1)
        }

        return PostProcessResult(newState, SentinelAction.PROCEED)
    }

    // =========================================================================
    // Lifecycle hooks
    // =========================================================================

    /**
     * Provides built-in resources: a default system instruction and an OODA loop
     * state machine definition for out-of-the-box testing.
     */
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

    /**
     * Enforces the same invariant as [VanillaStrategy]: outputSessionId must be
     * a member of subscribedSessionIds (or null).
     */
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