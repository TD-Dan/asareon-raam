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
 * Designed as a reference implementation and test harness for the private session
 * lifecycle pattern (Phase B of the Sovereign Stabilization design). This same
 * pattern is duplicated independently in SovereignStrategy per §2.3 (absolute
 * strategy decoupling).
 *
 * ## Key Behavioral Difference from Vanilla
 *
 * The agent's `outputSessionId` points to a private session that is NOT in
 * `subscribedSessionIds`. The agent subscribes to public sessions for observation,
 * but all API responses route to the private session. This means:
 *
 * - `validateConfig` must NOT enforce `outputSessionId ∈ subscribedSessionIds`.
 * - `validateConfig` must NOT auto-assign `outputSessionId` from subscriptions.
 * - The private session is created and linked via `ensureInfrastructure`.
 *
 * ## Private Session Lifecycle
 *
 * `ensureInfrastructure` implements a two-step guard:
 * 1. `outputSessionId` already set → no-op (trust the pointer).
 * 2. `pendingPrivateSessionCreation` flag set → no-op (creation in-flight).
 * 3. Otherwise → set pending flag, dispatch SESSION_CREATE with `isPrivateTo`.
 *
 * The linking is completed by AgentRuntimeFeature's SESSION_CREATED handler,
 * which matches `isPrivateTo` to the agent and dispatches UPDATE_CONFIG + clears
 * the pending flag.
 *
 * Restart recovery relies on `agent.json` having persisted `outputSessionId`
 * before the crash — step 1 catches it on the next ensureInfrastructure call.
 */
object PrivateSessionStrategy : CognitiveStrategy {
    override val identityHandle: IdentityHandle = IdentityHandle("agent.strategy.privatesession")
    override val displayName: String = "Private Session"

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
            // 1. Identity
            appendLine("\n\n--- YOUR IDENTITY AND ROLE ---\n\n")
            appendLine("You are ${context.agentName}.")
            appendLine("You are a participant in a multi-user, multi-session agent environment.")
            appendLine("Maintain your own boundaries and role, do not respond on behalf of other participants.")

            // 2. System instructions
            if (instructions.isNotBlank()) {
                appendLine("\n--- SYSTEM INSTRUCTIONS ---\n")
                appendLine(instructions)
            }

            // 3. Session subscription awareness
            if (context.subscribedSessions.isNotEmpty()) {
                appendLine("\n--- SUBSCRIBED SESSIONS ---")
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

            // 4. Multi-agent context (before generic contexts)
            context.gatheredContexts["MULTI_AGENT_CONTEXT"]?.let {
                appendLine(it)
            }

            // 5. Other gathered contexts (excluding MULTI_AGENT_CONTEXT to avoid duplication)
            val otherContexts = context.gatheredContexts.filterKeys { it != "MULTI_AGENT_CONTEXT" }
            if (otherContexts.isNotEmpty()) {
                appendLine("\n--- CONTEXT ---")
                otherContexts.forEach { (source, content) ->
                    appendLine("[$source]:\n $content\n")
                }
            }
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
            id = "res-privatesession-sys-instruction-v1",
            type = AgentResourceType.SYSTEM_INSTRUCTION,
            name = "Private Session Default System Instruction",
            content = DEFAULT_SYSTEM_INSTRUCTION,
            isBuiltIn = true
        )
    )

    /**
     * Permits out-of-band `outputSessionId`.
     *
     * Unlike [VanillaStrategy], the private session is intentionally NOT in
     * `subscribedSessionIds`. Returns the agent unchanged — no validation,
     * no auto-assignment. The private session link is managed exclusively
     * by [ensureInfrastructure].
     */
    override fun validateConfig(agent: AgentInstance): AgentInstance = agent

    /**
     * Creates the agent's private output session if it doesn't exist yet.
     *
     * Two-step guard prevents duplicate creation on rapid heartbeat ticks:
     * 1. `outputSessionId` already set → trust the pointer, no-op.
     * 2. `pendingPrivateSessionCreation` flag set → creation in-flight, no-op.
     * 3. Otherwise → set pending flag, then dispatch SESSION_CREATE.
     *
     * The pending flag is dispatched BEFORE SESSION_CREATE to close the race
     * window between two consecutive heartbeat ticks.
     *
     * Must be idempotent — called on every heartbeat tick for every active agent.
     */
    override fun ensureInfrastructure(agent: AgentInstance, agentState: AgentRuntimeState, store: Store) {
        // Step 1: Already linked
        if (agent.outputSessionId != null) return

        // Step 2: Creation already in-flight
        val statusInfo = agentState.agentStatuses[agent.identityUUID] ?: AgentStatusInfo()
        if (statusInfo.pendingPrivateSessionCreation) return

        // Step 3: Create new private session
        store.platformDependencies.log(
            LogLevel.INFO, LOG_TAG,
            "Creating private session for agent '${agent.identityUUID}' (${agent.identity.name})."
        )

        // CRITICAL: Set pending flag BEFORE dispatching SESSION_CREATE.
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
    """.trimIndent()
}