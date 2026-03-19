package app.auf.feature.agent.strategies

import app.auf.core.Action
import app.auf.core.IdentityHandle
import app.auf.core.Store
import app.auf.core.generated.ActionRegistry
import app.auf.feature.agent.*
import app.auf.util.LogLevel
import kotlinx.serialization.json.*

/**
 * Reference strategy for testing HKG (Holon Knowledge Graph) integration.
 *
 * Combines Vanilla-style session awareness with HKG context delivery via the
 * two-partition INDEX + FILES view.
 *
 * ## Delimiter Convention
 *
 * Strategy-owned sections use [ContextDelimiters.h1]. HKG partitions and other
 * gathered contexts arrive pre-wrapped from the pipeline. The strategy controls
 * ordering: HKG sections come first, then navigation, then sessions, then others.
 */
object HKGStrategy : CognitiveStrategy {
    override val identityHandle: IdentityHandle = IdentityHandle("agent.strategy.hkg")
    override val displayName: String = "HKG (Knowledge Graph)"

    private const val SLOT_SYSTEM_INSTRUCTION = "system_instruction"
    private const val KEY_TURN_COUNT = "turnCount"
    private const val KEY_KNOWLEDGE_GRAPH_ID = "knowledgeGraphId"
    private const val LOG_TAG = "HKGStrategy"

    // Keys for explicit ordering — strategy pulls these before the generic loop.
    private val EXPLICITLY_ORDERED_KEYS = setOf(
        "MULTI_AGENT_CONTEXT",
        "HOLON_KNOWLEDGE_GRAPH_INDEX",
        "HOLON_KNOWLEDGE_GRAPH_FILES"
    )

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
            // 1. Identity (strategy-owned, PROTECTED)
            val identityContent = buildString {
                appendLine("You are ${context.agentName}.")
                appendLine("You are a participant in a multi-user, multi-session agent environment.")
                appendLine("You have access to a Holon Knowledge Graph (HKG) — your persistent memory and identity layer.")
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

            // 3. HKG INDEX (pre-wrapped by pipeline, placed here for ordering)
            context.gatheredContexts["HOLON_KNOWLEDGE_GRAPH_INDEX"]?.let { append(it) }

            // 4. HKG FILES (pre-wrapped by pipeline)
            context.gatheredContexts["HOLON_KNOWLEDGE_GRAPH_FILES"]?.let { append(it) }

            // 5. HKG navigation instructions (strategy-owned, PROTECTED)
            if (context.gatheredContexts.containsKey("HOLON_KNOWLEDGE_GRAPH_INDEX")) {
                val navContent = buildString {
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
                }
                append(ContextDelimiters.h1("HKG NAVIGATION", navContent.length, ContextDelimiters.PROTECTED))
                append(navContent)
                append(ContextDelimiters.h1End("HKG NAVIGATION"))
            }

            // 6. Session subscription awareness (strategy-owned, PROTECTED)
            if (context.subscribedSessions.isNotEmpty()) {
                val sessContent = buildSubscribedSessionsContent(context)
                append(ContextDelimiters.h1("SUBSCRIBED SESSIONS", sessContent.length, ContextDelimiters.PROTECTED))
                append(sessContent)
                append(ContextDelimiters.h1End("SUBSCRIBED SESSIONS"))
            }

            // 7. Multi-agent context (pre-wrapped, placed before other contexts)
            context.gatheredContexts["MULTI_AGENT_CONTEXT"]?.let { append(it) }

            // 8. Remaining gathered contexts (pre-wrapped, excluding explicitly ordered ones)
            context.gatheredContexts
                .filterKeys { it !in EXPLICITLY_ORDERED_KEYS }
                .forEach { (_, content) -> append(content) }
        }
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
            id = "res-hkg-sys-instruction-v1",
            type = AgentResourceType.SYSTEM_INSTRUCTION,
            name = "HKG Strategy Default System Instruction",
            content = DEFAULT_SYSTEM_INSTRUCTION,
            isBuiltIn = true
        )
    )

    /**
     * After an HKG agent is created, ensure its knowledge graph reservation
     * is bootstrapped.
     */
    override fun onAgentRegistered(agent: AgentInstance, store: Store) {
        val agentState = store.state.value.featureStates["agent"] as? AgentRuntimeState ?: return
        ensureInfrastructure(agent, agentState, store)
    }

    /**
     * Detects HKG-specific configuration transitions:
     * - KG assignment: reserve the HKG
     * - KG revocation: release the HKG
     */
    override fun onAgentConfigChanged(old: AgentInstance, new: AgentInstance, store: Store) {
        val oldKgId = getKnowledgeGraphId(old)
        val newKgId = getKnowledgeGraphId(new)

        // KG assigned
        if (newKgId != null && oldKgId == null) {
            store.deferredDispatch(new.identityHandle.handle, Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_RESERVE_HKG,
                buildJsonObject { put("personaId", newKgId) }
            ))
        }

        // KG revoked
        if (oldKgId != null && newKgId == null) {
            store.deferredDispatch(new.identityHandle.handle, Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_RELEASE_HKG,
                buildJsonObject { put("personaId", oldKgId) }
            ))
        }
    }

    /**
     * Ensures the HKG reservation exists for this agent. Idempotent —
     * called on heartbeat ticks and after config changes.
     */
    override fun ensureInfrastructure(agent: AgentInstance, agentState: AgentRuntimeState, store: Store) {
        val kgId = getKnowledgeGraphId(agent) ?: return

        if (!agentState.hkgReservedIds.contains(kgId)) {
            store.deferredDispatch(agent.identityHandle.handle, Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_RESERVE_HKG,
                buildJsonObject { put("personaId", kgId) }
            ))
        }
    }

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

    override fun needsAdditionalContext(agent: AgentInstance): Boolean {
        return getKnowledgeGraphId(agent) != null
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

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