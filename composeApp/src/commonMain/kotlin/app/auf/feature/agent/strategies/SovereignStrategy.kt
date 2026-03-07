package app.auf.feature.agent.strategies

import app.auf.core.Action
import app.auf.core.IdentityHandle
import app.auf.core.Store
import app.auf.core.generated.ActionRegistry
import app.auf.feature.agent.*
import app.auf.util.LogLevel
import kotlinx.serialization.json.*

/**
 * The Constitutional Strategy.
 * Implements a strict State Machine: BOOTING -> AWAKE.
 *
 * In BOOTING phase, the system prompt includes both Constitution and Bootloader.
 * The agent uses UPDATE_NVRAM to transition to AWAKE, at which point only the Constitution remains.
 *
 * Lifecycle hooks encapsulate ALL Sovereign-specific behavior:
 * - HKG reservation and release
 * - Private cognition session linking
 * - HKG context requests during the cognitive pipeline
 * - Built-in resource provisioning
 * - Config validation permitting out-of-band outputSessionId
 *
 * `knowledgeGraphId` is owned by this strategy via `strategyConfig` (operator configuration).
 * NVRAM (`cognitiveState`) holds only agent-written runtime state (e.g., phase).
 */
object SovereignStrategy : CognitiveStrategy {
    override val identityHandle: IdentityHandle = IdentityHandle("agent.strategy.sovereign")
    override val displayName: String = "Sovereign (Constitutional)"

    private const val SLOT_CONSTITUTION = "constitution"
    private const val SLOT_BOOTLOADER = "bootloader"
    private const val KEY_PHASE = "phase"
    private const val KEY_KNOWLEDGE_GRAPH_ID = "knowledgeGraphId"
    private const val PHASE_BOOTING = "BOOTING"
    private const val PHASE_AWAKE = "AWAKE"

    private const val LOG_TAG = "SovereignStrategy"

    // =========================================================================
    // Core methods (unchanged)
    // =========================================================================

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

    override fun getConfigFields(): List<StrategyConfigField> = listOf(
        StrategyConfigField(
            key = KEY_KNOWLEDGE_GRAPH_ID,
            type = StrategyConfigFieldType.KNOWLEDGE_GRAPH,
            displayName = "Knowledge Graph",
            description = "The Holon Knowledge Graph reserved for this agent's long-term memory."
        )
    )

    /**
     * Initial NVRAM state — pure runtime cognitive state.
     * `knowledgeGraphId` is operator configuration, stored in `strategyConfig`.
     */
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

            // 3. Session Subscription Awareness
            if (context.subscribedSessions.isNotEmpty() || context.outputSessionHandle != null) {
                appendLine("--- SUBSCRIBED SESSIONS ---")

                // Mark the private cognition session as primary output
                if (context.outputSessionHandle != null) {
                    val outputInSubscribed = context.subscribedSessions.find { it.handle == context.outputSessionHandle }
                    if (outputInSubscribed != null) {
                        appendLine("  - ${outputInSubscribed.name} (${outputInSubscribed.handle}) [PRIMARY OUTPUT — Your private cognition session. Responses and tool results are routed here]")
                    } else {
                        appendLine("  - ${context.outputSessionHandle} [PRIMARY OUTPUT — Your private cognition session. Responses and tool results are routed here]")
                    }
                }

                // List other subscribed sessions
                context.subscribedSessions.forEach { session ->
                    if (session.handle != context.outputSessionHandle) {
                        appendLine("  - ${session.name} (${session.handle}) [OBSERVED — You receive messages from this session]")
                    }
                }

                appendLine("You observe messages from all subscribed sessions. Your responses are posted to the primary output session.")
                appendLine()
            }

            // 4. The Context (World)
            if (context.gatheredContexts.isNotEmpty()) {
                appendLine("--- OBSERVED REALITY ---")
                context.gatheredContexts.forEach { (source, content) ->
                    appendLine("[$source]:\n$content")
                }
                appendLine("------------------------")
                appendLine()
            }

            // 5. The Bootloader (ONLY in BOOTING phase)
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

    // =========================================================================
    // Lifecycle hooks
    // =========================================================================

    /**
     * Returns Sovereign-specific built-in resources: Constitution and Boot Sentinel.
     */
    override fun getBuiltInResources(): List<AgentResource> = listOf(
        AgentResource(
            id = "res-sovereign-constitution-v1",
            type = AgentResourceType.CONSTITUTION,
            name = "Sovereign Constitution (v5.9)",
            content = SovereignDefaults.DEFAULT_CONSTITUTION_XML,
            isBuiltIn = true
        ),
        AgentResource(
            id = "res-boot-sentinel-v1",
            type = AgentResourceType.BOOTLOADER,
            name = "Boot Sentinel (v1.0)",
            content = SovereignDefaults.BOOT_SENTINEL_XML,
            isBuiltIn = true
        )
    )

    /**
     * After a Sovereign agent is created, ensure its infrastructure (HKG reservation,
     * private cognition session) is bootstrapped.
     */
    override fun onAgentRegistered(agent: AgentInstance, store: Store) {
        val agentState = store.state.value.featureStates["agent"] as? AgentRuntimeState ?: return
        ensureInfrastructure(agent, agentState, store)
    }

    /**
     * Detects Sovereign-specific configuration transitions:
     * - KG assignment: reserve the HKG
     * - KG revocation: release the HKG and truncate subscriptions
     */
    override fun onAgentConfigChanged(old: AgentInstance, new: AgentInstance, store: Store) {
        val oldKgId = getKnowledgeGraphId(old)
        val newKgId = getKnowledgeGraphId(new)

        // Just became Sovereign (KG assigned)
        if (newKgId != null && oldKgId == null) {
            store.deferredDispatch("agent", Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_RESERVE_HKG,
                buildJsonObject { put("personaId", newKgId) }
            ))
        }

        // Just lost Sovereign status (KG revoked)
        if (oldKgId != null && newKgId == null) {
            val truncatedSubscriptions = old.subscribedSessionIds.take(1)
            store.deferredDispatch("agent", Action(
                ActionRegistry.Names.AGENT_UPDATE_CONFIG,
                buildJsonObject {
                    put("agentId", new.identityUUID.uuid)
                    put("outputSessionId", JsonNull)
                    put("subscribedSessionIds", buildJsonArray {
                        truncatedSubscriptions.forEach { add(it.uuid) }
                    })
                }
            ))
            store.deferredDispatch("agent", Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_RELEASE_HKG,
                buildJsonObject { put("personaId", oldKgId) }
            ))
        }
    }

    /**
     * Implements the "Trust or Bootstrap" protocol for Sovereign infrastructure.
     * Idempotent — called on heartbeat ticks and after config changes.
     *
     * 1. HKG Reservation: Ensures the KG is reserved for this agent.
     * 2. Session Linking: Ensures the private cognition session exists and is linked.
     */
    override fun ensureInfrastructure(agent: AgentInstance, agentState: AgentRuntimeState, store: Store) {
        val kgId = getKnowledgeGraphId(agent) ?: return // Not a Sovereign agent (no KG assigned)

        // 1. HKG Reservation — ensure reservation exists
        if (!agentState.hkgReservedIds.contains(kgId)) {
            store.deferredDispatch("agent", Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_RESERVE_HKG,
                buildJsonObject { put("personaId", kgId) }
            ))
        }

        // 2. Session Linking — Trust or Bootstrap

        // [RULE 1] The Existing Pointer Boundary
        if (agent.outputSessionId != null) {
            return
        }

        // [RULE 2] The Void (Bootstrap)
        val expectedSessionName = "p-cognition: ${agent.identity.name} (${agent.identityUUID})"

        // A. Check for Name Match in the identity registry (to link an existing but unlinked session)
        val existingSessionIdentity = store.state.value.identityRegistry.values.find {
            it.parentHandle == "session" && it.name == expectedSessionName
        }

        if (existingSessionIdentity != null) {
            // FOUND: Link by UUID (immutable, survives renames).
            val sessionUUID = existingSessionIdentity.uuid
            if (sessionUUID != null) {
                store.deferredDispatch("agent", Action(
                    ActionRegistry.Names.AGENT_UPDATE_CONFIG,
                    buildJsonObject {
                        put("agentId", agent.identityUUID.uuid)
                        put("outputSessionId", sessionUUID)
                    }
                ))
            } else {
                store.platformDependencies.log(
                    LogLevel.WARN, LOG_TAG,
                    "Found session '${existingSessionIdentity.handle}' for agent '${agent.identityUUID}' but it has no UUID. Cannot link."
                )
            }
        } else {
            // NOT FOUND: Create it.
            store.deferredDispatch("agent", Action(
                ActionRegistry.Names.SESSION_CREATE,
                buildJsonObject {
                    put("name", expectedSessionName)
                    put("isHidden", true)
                    put("isPrivateTo", agent.identityHandle.handle)
                }
            ))
        }
    }

    /**
     * Sovereign permits out-of-band outputSessionId — the private cognition session
     * is deliberately NOT in subscribedSessionIds. No correction applied.
     */
    override fun validateConfig(agent: AgentInstance): AgentInstance = agent

    /**
     * Requests HKG context for Sovereign agents that have a knowledge graph assigned.
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
            "Agent '${agent.identityUUID}' has HKG but KnowledgeGraphFeature is missing."
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
     * This is the canonical accessor for knowledgeGraphId, owned by this strategy.
     */
    fun getKnowledgeGraphId(agent: AgentInstance): String? {
        return agent.strategyConfig[KEY_KNOWLEDGE_GRAPH_ID]
            ?.jsonPrimitive
            ?.contentOrNull
    }
}