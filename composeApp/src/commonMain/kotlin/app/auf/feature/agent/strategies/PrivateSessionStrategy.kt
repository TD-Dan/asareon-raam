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