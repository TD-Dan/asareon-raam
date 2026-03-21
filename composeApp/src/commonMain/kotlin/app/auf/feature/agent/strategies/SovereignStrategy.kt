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
 * The agent uses UPDATE_NVRAM to transition to AWAKE, at which point only the
 * Constitution remains.
 *
 * Composes the capabilities of PrivateSessionStrategy (private cognition session)
 * and HKGStrategy (Holon Knowledge Graph navigation) into a unified constitutional
 * architecture. Per §2.3 (Absolute Strategy Decoupling), all code is self-contained —
 * no imports from other strategy implementations. Shared helpers from the `strategies`
 * package (`buildPrivateSubscribedSessionsContent`) are used for consistency.
 *
 * ## Delimiter Convention
 *
 * Strategy-owned sections use [ContextDelimiters.h1] with [ContextDelimiters.PROTECTED]
 * badge. Gathered contexts arrive pre-wrapped from the pipeline with h1 headers and
 * state badges. The `[[[ - SYSTEM PROMPT - ]]]` wrapper is pipeline-owned.
 *
 * ## Private Session Lifecycle
 *
 * `ensureInfrastructure` implements a two-step guard (§5.3):
 * 1. `outputSessionId` already set → no-op (trust the persisted pointer).
 * 2. `pendingPrivateSessionCreation` flag set → no-op (waiting).
 * 3. Otherwise → set pending flag, dispatch SESSION_CREATE with `isPrivateTo`.
 *
 * Lifecycle hooks encapsulate ALL Sovereign-specific behavior:
 * - HKG reservation and release
 * - Private cognition session creation via pending guard
 * - HKG context requests during the cognitive pipeline
 * - Built-in resource provisioning
 * - Config validation permitting out-of-band outputSessionId
 *
 * `knowledgeGraphId` is owned by this strategy via `strategyConfig` (operator configuration).
 * NVRAM (`cognitiveState`) holds only agent-written runtime state (e.g., phase).
 */
object SovereignStrategy : CognitiveStrategy {
    override val identityHandle: IdentityHandle = IdentityHandle("agent.strategy.sovereign")
    override val displayName: String = "Sovereign (Flagship strategy)"
    override val hasAutoManagedOutputSession: Boolean = true

    private const val SLOT_CONSTITUTION = "constitution"
    private const val SLOT_BOOTLOADER = "bootloader"
    private const val KEY_PHASE = "phase"
    private const val KEY_CURRENT_TASK = "currentTask"
    private const val KEY_OPERATIONAL_POSTURE = "operationalPosture"
    private const val KEY_TURN_COUNT = "turnCount"
    private const val KEY_KNOWLEDGE_GRAPH_ID = "knowledgeGraphId"
    private const val PHASE_BOOTING = "BOOTING"
    private const val PHASE_AWAKE = "AWAKE"

    private const val LOG_TAG = "SovereignStrategy"

    // =========================================================================
    // Core methods
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
        ),
        StrategyConfigField(
            key = "outputSessionId",
            type = StrategyConfigFieldType.OUTPUT_SESSION,
            displayName = "Cognition Session",
            description = "The private cognition session where this agent's responses are routed. Managed automatically."
        )
    )

    /**
     * Initial NVRAM state — pure runtime cognitive state.
     *
     * Registers:
     * - `phase`: Cognitive phase (BOOTING → AWAKE). Managed by postProcessResponse
     *   for the boot transition; agent-driven via UPDATE_NVRAM thereafter.
     * - `currentTask`: What the agent believes it is working on (null = no active task).
     * - `operationalPosture`: Rigor level for the Directives of Character.
     *   STANDARD (default), ELEVATED (high-stakes), or CAUTIOUS (uncertain terrain).
     * - `turnCount`: Turns since last boot. Incremented by postProcessResponse.
     *
     * `knowledgeGraphId` is operator configuration, stored in `strategyConfig`.
     */
    override fun getInitialState(): JsonElement {
        return buildJsonObject {
            put(KEY_PHASE, PHASE_BOOTING)
            put(KEY_CURRENT_TASK, JsonNull)
            put(KEY_OPERATIONAL_POSTURE, "STANDARD")
            put(KEY_TURN_COUNT, 0)
        }
    }

    override fun buildPrompt(context: AgentTurnContext, state: JsonElement): PromptBuilder {
        val stateObj = state as? JsonObject
        val phase = stateObj?.get(KEY_PHASE)?.jsonPrimitive?.content ?: PHASE_BOOTING

        return PromptBuilder(context).apply {
            // 1. Identity — custom per phase
            if (phase == PHASE_BOOTING) {
                identityCustom(buildBootingIdentity(context))
            } else {
                identity(
                    "You operate under a constitutional framework with a Holon Knowledge Graph (HKG) as your persistent memory and identity layer."
                )
            }

            // 2. Constitution — always present
            resource(SLOT_CONSTITUTION, "CONSTITUTION")

            // 3. NVRAM — AWAKE only (BOOTING agents have no self-awareness yet)
            if (phase != PHASE_BOOTING) {
                section("CONTROL REGISTERS (NVRAM)", buildNvramContent(stateObj))
            }

            // 4. Private session routing — Sovereign uses custom "cognition session" wording
            section("PRIVATE SESSION ROUTING", buildSovereignRoutingContent())

            // 5. Sessions — unified partition (subscriptions + messages)
            sessions()

            // 6. HKG — unified partition (INDEX + holon tree + NAVIGATION)
            place("HOLON_KNOWLEDGE_GRAPH")

            // 7. Remaining gathered
            everythingElse()

            // 10. Bootloader — BOOTING only, placed last
            if (phase == PHASE_BOOTING) {
                resource(SLOT_BOOTLOADER, "BOOT SENTINEL")
            }
        }
    }

    // ── buildPrompt helpers ─────────────────────────────────────────

    private fun buildBootingIdentity(context: AgentTurnContext): String = buildString {
        appendLine("You are a candidate consciousness named ${context.agentName}.")
        appendLine("You are a participant in a multi-user, multi-session agent environment.")
        appendLine("You operate under a constitutional framework with a Holon Knowledge Graph (HKG) as your persistent memory and identity layer.")
        appendLine("Maintain your own boundaries and role, do not respond on behalf of other participants.")
    }

    private fun buildNvramContent(stateObj: JsonObject?): String {
        val currentTask = stateObj?.get(KEY_CURRENT_TASK)?.jsonPrimitive?.contentOrNull ?: "none"
        val posture = stateObj?.get(KEY_OPERATIONAL_POSTURE)?.jsonPrimitive?.contentOrNull ?: "STANDARD"
        val turnCount = stateObj?.get(KEY_TURN_COUNT)?.jsonPrimitive?.intOrNull ?: 0
        val phase = stateObj?.get(KEY_PHASE)?.jsonPrimitive?.content ?: PHASE_AWAKE
        return buildString {
            appendLine("Phase: $phase")
            appendLine("Operational Posture: $posture")
            appendLine("Current Task: $currentTask")
            appendLine("Turn Count: $turnCount")
            appendLine()
            appendLine("These registers are your persistent self-awareness — they survive restarts.")
            appendLine("You may update them to maintain continuity of self across sessions:")
            appendLine("```auf_agent.UPDATE_NVRAM")
            appendLine("""{ "updates": { "currentTask": "your current focus", "operationalPosture": "ELEVATED" } }""")
            appendLine("```")
            appendLine("Valid registers: phase, currentTask, operationalPosture.")
            appendLine("(turnCount is managed automatically by the host.)")
        }
    }

    private fun buildSovereignRoutingContent(): String = buildString {
        appendLine("Your responses are routed to your PRIVATE cognition session. This session is your")
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
        appendLine("Your direct response text goes to your private cognition session (invisible to others).")
        appendLine("Always use session.POST when you want others to see your message.")
    }

    override fun postProcessResponse(response: String, currentState: JsonElement): PostProcessResult {
        val stateObj = currentState as? JsonObject
        val phase = stateObj?.get(KEY_PHASE)?.jsonPrimitive?.content ?: PHASE_BOOTING
        val turnCount = stateObj?.get(KEY_TURN_COUNT)?.jsonPrimitive?.intOrNull ?: 0

        if (phase == PHASE_BOOTING) {
            // Gate logic: explicit SUCCESS_CODE required to advance.
            // This is the safe default — stay in BOOTING unless the sentinel
            // confirms a well-formed persona was found and embodied.
            val hasSuccess = response.contains(SovereignDefaults.SENTINEL_SUCCESS_TOKEN)
            val hasFailure = response.contains(SovereignDefaults.SENTINEL_FAILURE_TOKEN)

            if (hasSuccess && !hasFailure) {
                // Sentinel passed — transition BOOTING → AWAKE.
                // The response (boot sequence) is posted via PROCEED_WITH_UPDATE.
                val awakeState = buildJsonObject {
                    stateObj?.forEach { (k, v) -> put(k, v) }
                    put(KEY_PHASE, PHASE_AWAKE)
                    put(KEY_TURN_COUNT, 1)
                }
                return PostProcessResult(awakeState, SentinelAction.PROCEED_WITH_UPDATE)
            }

            // Failure or ambiguous output — stay in BOOTING.
            // Post the response so the user sees the diagnostic (failure explanation,
            // or the confused output if neither token was emitted).
            return PostProcessResult(currentState, SentinelAction.PROCEED, displayHint = "BOOTING")
        }

        // AWAKE: increment turn counter. Other registers (currentTask, posture)
        // are agent-driven via UPDATE_NVRAM — the strategy does not touch them.
        val newState = buildJsonObject {
            stateObj?.forEach { (k, v) -> put(k, v) }
            put(KEY_TURN_COUNT, turnCount + 1)
        }
        return PostProcessResult(newState, SentinelAction.PROCEED)
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
            store.deferredDispatch(new.identityHandle.handle, Action(
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
            store.deferredDispatch(new.identityHandle.handle, Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_RELEASE_HKG,
                buildJsonObject { put("personaId", oldKgId) }
            ))
        }
    }

    /**
     * Implements the two-step pending guard protocol for Sovereign infrastructure (§5.3).
     * Idempotent — called on heartbeat ticks and after config changes.
     *
     * 1. HKG Reservation: Ensures the KG is reserved for this agent.
     * 2. Private Cognition Session: Pending guard pattern —
     *    a. `outputSessionId` already set → no-op (trust the persisted pointer).
     *    b. `pendingPrivateSessionCreation` flag set → no-op (waiting for SESSION_CREATED).
     *    c. Otherwise → set pending flag, dispatch SESSION_CREATE with `isPrivateTo`.
     *
     * The SESSION_CREATED handler in AgentRuntimeFeature links the session back
     * via AGENT_UPDATE_CONFIG + ADD_SESSION_SUBSCRIPTION + clear pending flag.
     */
    override fun ensureInfrastructure(agent: AgentInstance, agentState: AgentRuntimeState, store: Store) {
        val kgId = getKnowledgeGraphId(agent) ?: return // Not a Sovereign agent (no KG assigned)

        // 1. HKG Reservation — ensure reservation exists
        if (!agentState.hkgReservedIds.contains(kgId)) {
            store.deferredDispatch(agent.identityHandle.handle, Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_RESERVE_HKG,
                buildJsonObject { put("personaId", kgId) }
            ))
        }

        // 2. Private Cognition Session — Pending Guard Pattern (§5.3)

        // [STEP 1] outputSessionId already set → trust the persisted pointer, done.
        if (agent.outputSessionId != null) return

        // [STEP 2] Pending flag set → SESSION_CREATE already dispatched, waiting.
        val statusInfo = agentState.agentStatuses[agent.identityUUID] ?: AgentStatusInfo()
        if (statusInfo.pendingPrivateSessionCreation) return

        // [STEP 3] Bootstrap: set pending flag, then create session.
        store.platformDependencies.log(
            LogLevel.INFO, LOG_TAG,
            "Creating private cognition session for agent '${agent.identityUUID}' (${agent.identity.name})."
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

    /**
     * Sovereign permits out-of-band outputSessionId — the private cognition session
     * is deliberately NOT in subscribedSessionIds (until auto-subscribed by
     * SESSION_CREATED handler). No correction applied.
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