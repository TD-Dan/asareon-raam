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

    override fun prepareSystemPrompt(context: AgentTurnContext, state: JsonElement): String {
        val instructions = context.resolvedResources[SLOT_SYSTEM_INSTRUCTION] ?: ""

        return buildString {
            // 1. Identity (strategy-owned, PROTECTED)
            val identityContent = buildString {
                appendLine("You are ${context.agentName}.")
                appendLine("You are a participant in a multi-user, multi-session agent environment.")
                appendLine("Maintain your own boundaries and role, do not respond on behalf of other participants.")
            }
            append(ContextDelimiters.h1("YOUR IDENTITY AND ROLE", identityContent.length, ContextDelimiters.PROTECTED))
            append(identityContent)
            append(ContextDelimiters.h1End("YOUR IDENTITY AND ROLE"))

            // 2. System instructions (strategy-owned, PROTECTED)
            if (instructions.isNotBlank()) {
                append(ContextDelimiters.h1("SYSTEM INSTRUCTIONS", instructions.length, ContextDelimiters.PROTECTED))
                appendLine(instructions)
                append(ContextDelimiters.h1End("SYSTEM INSTRUCTIONS"))
            }

            // 3. Session subscription awareness (strategy-owned, PROTECTED)
            if (context.subscribedSessions.isNotEmpty()) {
                val sessContent = buildSubscribedSessionsContent(context)
                append(ContextDelimiters.h1("SUBSCRIBED SESSIONS", sessContent.length, ContextDelimiters.PROTECTED))
                append(sessContent)
                append(ContextDelimiters.h1End("SUBSCRIBED SESSIONS"))
            }

            // 4. Gathered contexts — pre-wrapped by pipeline with h1 headers.
            // Multi-agent context first, then all others.
            context.gatheredContexts["MULTI_AGENT_CONTEXT"]?.let { append(it) }
            context.gatheredContexts
                .filterKeys { it != "MULTI_AGENT_CONTEXT" }
                .forEach { (_, content) -> append(content) }
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

// =============================================================================
// Shared helpers for session subscription rendering across strategies.
// =============================================================================

/**
 * Builds the inner content for the SUBSCRIBED SESSIONS section.
 * Each session is an h2 with participant roster. Reused by all strategies
 * that show session awareness (Vanilla, HKG, StateMachine, PrivateSession).
 */
internal fun buildSubscribedSessionsContent(context: AgentTurnContext): String = buildString {
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
}

/**
 * Builds the inner content for the SUBSCRIBED SESSIONS section with
 * private session routing tags. Used by PrivateSessionStrategy.
 */
internal fun buildPrivateSubscribedSessionsContent(context: AgentTurnContext): String = buildString {
    context.subscribedSessions.forEach { session ->
        val tag = if (session.isOutput || (context.outputSessionHandle == null && session == context.subscribedSessions.first())) {
            "PRIVATE — Your direct output is routed here, invisible to others"
        } else {
            "PUBLIC — Use session.POST to communicate here"
        }

        val sessionHeader = "${session.name} (${session.handle}) [$tag] | ${session.messageCount} messages"
        append(ContextDelimiters.h2(sessionHeader))

        if (session.participants.isNotEmpty()) {
            session.participants.forEach { participant ->
                appendLine("  - ${participant.senderName} (${participant.senderId}): ${participant.type}, ${participant.messageCount} messages")
            }
        } else {
            appendLine("  (no messages yet)")
        }

        append(ContextDelimiters.h2End("SESSION"))
    }
}