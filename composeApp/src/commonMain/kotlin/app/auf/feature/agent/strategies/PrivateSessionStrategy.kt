package app.auf.feature.agent.strategies

import app.auf.core.Action
import app.auf.core.IdentityHandle
import app.auf.core.Store
import app.auf.core.generated.ActionRegistry
import app.auf.feature.agent.*
import app.auf.util.LogLevel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A strategy that routes all agent output to a dedicated private session.
 *
 * ## Delimiter Convention
 *
 * Strategy-owned sections use [ContextDelimiters.h1]. Gathered contexts arrive
 * pre-wrapped from the pipeline. The `[[[ - SYSTEM PROMPT - ]]]` wrapper is
 * pipeline-owned.
 *
 * ## Private Session Lifecycle
 *
 * `ensureInfrastructure` implements a two-step guard:
 * 1. `outputSessionId` already set → no-op.
 * 2. `pendingPrivateSessionCreation` flag set → no-op.
 * 3. Otherwise → set pending flag, dispatch SESSION_CREATE with `isPrivateTo`.
 */
object PrivateSessionStrategy : CognitiveStrategy {
    override val identityHandle: IdentityHandle = IdentityHandle("agent.strategy.privatesession")
    override val displayName: String = "Private Session"
    override val hasAutoManagedOutputSession: Boolean = true

    private const val SLOT_SYSTEM_INSTRUCTION = "system_instruction"
    private const val LOG_TAG = "PrivateSessionStrategy"

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

    override fun getConfigFields(): List<StrategyConfigField> = listOf(
        StrategyConfigField(
            key = "outputSessionId",
            type = StrategyConfigFieldType.OUTPUT_SESSION,
            displayName = "Primary Session",
            description = "The private session where this agent's responses are routed. Managed automatically."
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

            // 3. Private session routing explanation (strategy-owned, PROTECTED)
            val routingContent = buildString {
                appendLine("Your responses are routed to your PRIVATE session. This session is your")
                appendLine("internal workspace — only you can see it. Other participants cannot read it.")
                appendLine()
                appendLine("To communicate with users and other agents, you MUST use the session.POST")
                appendLine("action to post messages to the public sessions you are subscribed to.")
                appendLine()
                appendLine("Example — posting to a public session:")
                appendLine("```auf_session.POST")
                appendLine("""{ "session": "<session name or handle>", "message": "Your message here." }""")
                appendLine("```")
                appendLine()
                appendLine("The conversation messages in your context come from ALL your subscribed sessions.")
                appendLine("Your direct response text goes to your private session (invisible to others).")
                appendLine("Always use session.POST when you want others to see your message.")
            }
            append(ContextDelimiters.h1("PRIVATE SESSION ROUTING", routingContent.length, ContextDelimiters.PROTECTED))
            append(routingContent)
            append(ContextDelimiters.h1End("PRIVATE SESSION ROUTING"))

            // 4. Session subscriptions with participants (strategy-owned, PROTECTED)
            if (context.subscribedSessions.isNotEmpty()) {
                val sessContent = buildPrivateSubscribedSessionsContent(context)
                append(ContextDelimiters.h1("SUBSCRIBED SESSIONS", sessContent.length, ContextDelimiters.PROTECTED))
                append(sessContent)
                append(ContextDelimiters.h1End("SUBSCRIBED SESSIONS"))
            }

            // 5. Gathered contexts — pre-wrapped by pipeline with h1 headers.
            // Multi-agent context first, then all others.
            context.gatheredContexts["MULTI_AGENT_CONTEXT"]?.let { append(it) }
            context.gatheredContexts
                .filterKeys { it != "MULTI_AGENT_CONTEXT" }
                .forEach { (_, content) -> append(content) }
        }
    }

    override fun buildPrompt(context: AgentTurnContext, state: JsonElement) =
        PromptBuilder(context).apply {
            identity()
            instructions()
            privateSessionRouting()
            sessions(SessionFormat.PRIVATE)
            place("MULTI_AGENT_CONTEXT")
            everythingElse()
        }

    override fun postProcessResponse(response: String, currentState: JsonElement): PostProcessResult {
        return PostProcessResult(currentState, SentinelAction.PROCEED)
    }

    // =========================================================================
    // Lifecycle hooks
    // =========================================================================

    override fun getBuiltInResources(): List<AgentResource> = listOf(
        AgentResource(
            id = "res-privatesession-sys-instruction-v1",
            type = AgentResourceType.SYSTEM_INSTRUCTION,
            name = "Private Session Default System Instruction",
            content = DEFAULT_SYSTEM_INSTRUCTION,
            isBuiltIn = true
        )
    )

    override fun validateConfig(agent: AgentInstance): AgentInstance = agent

    override fun ensureInfrastructure(agent: AgentInstance, agentState: AgentRuntimeState, store: Store) {
        if (agent.outputSessionId != null) return
        val statusInfo = agentState.agentStatuses[agent.identityUUID] ?: AgentStatusInfo()
        if (statusInfo.pendingPrivateSessionCreation) return

        store.platformDependencies.log(
            LogLevel.INFO, LOG_TAG,
            "Creating private session for agent '${agent.identityUUID}' (${agent.identity.name})."
        )

        store.dispatch("agent", Action(
            ActionRegistry.Names.AGENT_SET_PENDING_PRIVATE_SESSION,
            buildJsonObject {
                put("agentId", agent.identityUUID.uuid)
                put("pending", true)
            }
        ))

        store.dispatch("agent", Action(
            ActionRegistry.Names.SESSION_CREATE,
            buildJsonObject {
                put("name", "${agent.identity.name}-private-session")
                put("isPrivateTo", agent.identityHandle.handle)
                put("isHidden", true)
            }
        ))
    }

    // =========================================================================
    // Default resources
    // =========================================================================

    private val DEFAULT_SYSTEM_INSTRUCTION = """
        You are a helpful assistant with a private output session.
        Your responses are routed to your private session.
        You observe messages from your subscribed public sessions.
        Use your private session as your internal voice and staging ground before you answer to any sessions. You can also decide to not answer when there is nothing to say or you are the one that replied last.
    """.trimIndent()
}