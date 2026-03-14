package app.auf.feature.agent.strategies

import app.auf.core.Action
import app.auf.core.IdentityHandle
import app.auf.core.Store
import app.auf.core.generated.ActionRegistry
import app.auf.feature.agent.*
import app.auf.util.LogLevel
import kotlinx.serialization.json.*

/**
 * Reference strategy for testing HKG (Holon Knowledge Graph) integration in isolation.
 *
 * Combines Vanilla-style session awareness with HKG context delivery via the
 * two-partition INDEX + FILES view (§4 of the Sovereign Stabilization design).
 * No private session, no constitutional boot, no state machine — just system
 * instruction + HKG.
 *
 * ## Design Decisions
 *
 * - **No private session**: outputSessionId must be in subscribedSessionIds
 *   (same invariant as Vanilla). HKGStrategy is for testing HKG plumbing, not
 *   private session lifecycle.
 * - **No boot sentinel**: postProcessResponse always PROCEEDs. No BOOTING phase.
 * - **NVRAM**: Minimal — only a turn counter. No phase, no posture.
 * - **HKG context**: Requested via requestAdditionalContext, gated via
 *   needsAdditionalContext. Formatted by [HkgContextFormatter] and injected
 *   into the gatheredContexts map (INDEX always present, FILES for expanded holons).
 * - **knowledgeGraphId**: Stored in strategyConfig (operator configuration),
 *   same pattern as SovereignStrategy. Declared via getConfigFields.
 * - **Absolute decoupling**: Imports nothing from SovereignStrategy, VanillaStrategy,
 *   or any other strategy. Duplicates all relevant logic per §2.3.
 *
 * ## Resource Slots
 * - `system_instruction`: Identity and behavioral instructions.
 *
 * ## Phase C deliverable — reference test harness for:
 * - HkgContextFormatter INDEX tree rendering
 * - HkgContextFormatter FILES section rendering
 * - requestAdditionalContext / needsAdditionalContext lifecycle
 * - Pipeline HKG context → INDEX + FILES transformation
 * - Write guard integration (Phase C, implemented in AgentRuntimeFeature)
 */
object HKGStrategy : CognitiveStrategy {
    override val identityHandle: IdentityHandle = IdentityHandle("agent.strategy.hkg")
    override val displayName: String = "HKG (Knowledge Graph)"

    private const val SLOT_SYSTEM_INSTRUCTION = "system_instruction"
    private const val KEY_TURN_COUNT = "turnCount"
    private const val KEY_KNOWLEDGE_GRAPH_ID = "knowledgeGraphId"
    private const val LOG_TAG = "HKGStrategy"

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
            key = KEY_KNOWLEDGE_GRAPH_ID,
            type = StrategyConfigFieldType.KNOWLEDGE_GRAPH,
            displayName = "Knowledge Graph",
            description = "The Holon Knowledge Graph this agent can read and navigate."
        ),
        StrategyConfigField(
            key = "outputSessionId",
            type = StrategyConfigFieldType.OUTPUT_SESSION,
            displayName = "Primary Session",
            description = "The session where this agent's responses and tool results are routed."
        )
    )

    override fun getInitialState(): JsonElement = buildJsonObject {
        put(KEY_TURN_COUNT, 0)
    }

    override fun prepareSystemPrompt(context: AgentTurnContext, state: JsonElement): String {
        val stateObj = state as? JsonObject
        val turnCount = stateObj?.get(KEY_TURN_COUNT)?.jsonPrimitive?.intOrNull ?: 0

        val instructions = context.resolvedResources[SLOT_SYSTEM_INSTRUCTION] ?: ""

        return buildString {
            // 1. Identity
            appendLine("\n\n--- YOUR IDENTITY AND ROLE ---\n\n")
            appendLine("You are ${context.agentName}.")
            appendLine("You are a participant in a multi-user, multi-session agent environment.")
            appendLine("You have access to a Holon Knowledge Graph (HKG) — your persistent memory and identity layer.")
            appendLine("Maintain your own boundaries and role, do not respond on behalf of other participants.")

            // 2. System instructions
            if (instructions.isNotBlank()) {
                appendLine("\n--- SYSTEM INSTRUCTIONS ---\n")
                appendLine(instructions)
            }

            // 3. HKG INDEX (always present if HKG context was gathered)
            context.gatheredContexts["HOLON_KNOWLEDGE_GRAPH_INDEX"]?.let {
                appendLine(it)
                appendLine()
            }

            // 4. HKG FILES (expanded holons only)
            context.gatheredContexts["HOLON_KNOWLEDGE_GRAPH_FILES"]?.let {
                appendLine(it)
                appendLine()
            }

            // 5. HKG navigation instructions
            if (context.gatheredContexts.containsKey("HOLON_KNOWLEDGE_GRAPH_INDEX")) {
                appendLine("--- HKG NAVIGATION ---")
                appendLine()
                appendLine("Your Knowledge Graph is presented as an INDEX (tree overview) and FILES (open file contents).")
                appendLine("By default, all files are closed. Use these commands to navigate:")
                appendLine()
                appendLine("Open a single holon file:")
                appendLine("```auf_agent.CONTEXT_UNCOLLAPSE")
                appendLine("""{ "partitionKey": "hkg:<holonId>", "scope": "single" }""")
                appendLine("```")
                appendLine()
                appendLine("Open a holon and reveal its children in the INDEX:")
                appendLine("```auf_agent.CONTEXT_UNCOLLAPSE")
                appendLine("""{ "partitionKey": "hkg:<holonId>", "scope": "subtree" }""")
                appendLine("```")
                appendLine()
                appendLine("Close a holon file:")
                appendLine("```auf_agent.CONTEXT_COLLAPSE")
                appendLine("""{ "partitionKey": "hkg:<holonId>" }""")
                appendLine("```")
                appendLine()
                appendLine("IMPORTANT: You must expand a holon file before writing to it.")
                appendLine("The system will block writes to collapsed holons to prevent data loss.")
                appendLine()
            }

            // 7. Session subscription awareness
            if (context.subscribedSessions.isNotEmpty()) {
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

            // 7. Multi-agent context (before generic contexts)
            context.gatheredContexts["MULTI_AGENT_CONTEXT"]?.let {
                appendLine(it)
            }

            // 8. Other gathered contexts (excluding HKG and multi-agent which are already rendered)
            val excludedKeys = setOf(
                "MULTI_AGENT_CONTEXT",
                "HOLON_KNOWLEDGE_GRAPH_INDEX",
                "HOLON_KNOWLEDGE_GRAPH_FILES"
            )
            val otherContexts = context.gatheredContexts.filterKeys { it !in excludedKeys }
            if (otherContexts.isNotEmpty()) {
                appendLine("\n--- CONTEXT ---")
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

        // Increment turn counter. No sentinel checks.
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
            id = "res-hkg-sys-instruction-v1",
            type = AgentResourceType.SYSTEM_INSTRUCTION,
            name = "HKG Strategy Default System Instruction",
            content = DEFAULT_SYSTEM_INSTRUCTION,
            isBuiltIn = true
        )
    )

    /**
     * Enforces the same invariant as [VanillaStrategy]: outputSessionId must be
     * a member of subscribedSessionIds (or null). HKGStrategy does not use a
     * private session — HKG context is injected into the system prompt alongside
     * the normal conversation flow.
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

    /**
     * Requests HKG context for agents that have a knowledge graph assigned.
     * Dispatches KNOWLEDGEGRAPH_REQUEST_CONTEXT with the agent's UUID as correlationId.
     */
    override fun requestAdditionalContext(agent: AgentInstance, store: Store): Boolean {
        val kgId = getKnowledgeGraphId(agent) ?: return false
        val kgFeatureExists = store.features.any { it.identity.handle == "knowledgegraph" }

        if (kgFeatureExists) {
            store.deferredDispatch("agent", Action(
                ActionRegistry.Names.AGENT_SET_PROCESSING_STEP,
                buildJsonObject {
                    put("agentId", agent.identityUUID.uuid)
                    put("step", "Requesting HKG")
                }
            ))

            store.deferredDispatch("agent", Action(
                name = ActionRegistry.Names.KNOWLEDGEGRAPH_REQUEST_CONTEXT,
                payload = buildJsonObject {
                    put("correlationId", agent.identityUUID.uuid)
                    put("personaId", kgId)
                }
            ))
            return true
        }

        store.platformDependencies.log(
            LogLevel.WARN, LOG_TAG,
            "Agent '${agent.identityUUID}' has HKG config but KnowledgeGraphFeature is missing."
        )
        return false
    }

    /**
     * Returns true if this agent has a knowledge graph assigned — the pipeline
     * should wait for HKG context before executing the turn.
     */
    override fun needsAdditionalContext(agent: AgentInstance): Boolean {
        return getKnowledgeGraphId(agent) != null
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Extracts `knowledgeGraphId` from the agent's strategyConfig.
     * Returns null if not present or null-valued.
     */
    fun getKnowledgeGraphId(agent: AgentInstance): String? {
        return agent.strategyConfig[KEY_KNOWLEDGE_GRAPH_ID]
            ?.jsonPrimitive
            ?.contentOrNull
    }

    // =========================================================================
    // Default resources
    // =========================================================================

    private val DEFAULT_SYSTEM_INSTRUCTION = """
        You are an ai agent with access to a Holon Knowledge Graph (HKG).
        Your HKG is your persistent memory — use it to store and retrieve information across sessions.
        Navigate your graph using CONTEXT_UNCOLLAPSE and CONTEXT_COLLAPSE commands.
        Always expand a holon before modifying it.
    """.trimIndent()
}