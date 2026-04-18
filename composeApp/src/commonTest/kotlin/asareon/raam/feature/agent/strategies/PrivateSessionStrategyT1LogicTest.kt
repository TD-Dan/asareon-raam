package asareon.raam.feature.agent.strategies

import asareon.raam.core.*
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.fakes.FakePlatformDependencies
import asareon.raam.fakes.FakeStore
import asareon.raam.feature.agent.*
import asareon.raam.test.testDescriptorsFor
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Tier 1 Unit Tests for PrivateSessionStrategy.
 *
 * PrivateSessionStrategy is a reference strategy that validates the private session
 * lifecycle pattern in isolation (Phase B of the Sovereign Stabilization design).
 *
 * ## What PrivateSessionStrategy IS
 *
 * A strategy whose agent always has a dedicated private output session, linked by UUID.
 * The agent can subscribe to multiple public sessions (to observe), but all API
 * responses route to the private session. The private session is created on-demand
 * by ensureInfrastructure() and linked back via SESSION_CREATED → UPDATE_CONFIG.
 *
 * ## Key Behavioral Contracts
 *
 * 1. **Private session lifecycle (ensureInfrastructure)**
 *    - Creates a private session if none is linked and none is pending.
 *    - Uses `isPrivateTo = agent.identityHandle` for deterministic matching.
 *    - Sets the pending guard flag to prevent duplicate creation on rapid heartbeats.
 *    - Is a complete no-op when the session is already linked.
 *    - Restart recovery relies on agent.json having persisted outputSessionId.
 *
 * 2. **validateConfig: outputSessionId is NOT constrained to subscribedSessionIds**
 *    - Unlike Vanilla/Minimal, the output session is the private session, which
 *      is NOT in the public subscription list. validateConfig must NOT reset it.
 *    - If outputSessionId is null and subscriptions exist, it should NOT auto-assign
 *      (unlike Vanilla). The private session is assigned via ensureInfrastructure.
 *
 * 3. **buildPrompt: private session awareness**
 *    - Tags the private session as [PRIVATE] and public sessions as [PUBLIC].
 *    - Includes session subscription awareness (like Vanilla).
 *    - Includes multi-agent context (like Vanilla).
 *
 * 4. **postProcessResponse: stateless, always PROCEED**
 *    - No sentinel checks, no state transitions.
 *
 * Tests are organized by concern, each section testing one behavioral contract.
 * The test naming convention follows: `methodName should behavior when condition`.
 */
class PrivateSessionStrategyT1LogicTest {

    // =========================================================================
    // Test data constants
    // =========================================================================

    private val agentId = "a0000001-0000-0000-0000-000000000001"
    private val agentName = "TestBot"
    private val agentHandle = "agent.testbot"

    private val publicSession1 = "s0000001-0000-0000-0000-000000000001"
    private val publicSession2 = "s0000002-0000-0000-0000-000000000002"
    private val privateSessionUUID = "p0000001-0000-0000-0000-000000000001"

    // =========================================================================
    // Helper: build agents with the PrivateSession strategy
    // =========================================================================

    /**
     * Creates a PrivateSessionStrategy agent.
     *
     * Note: [outputSessionId] here represents the private session link.
     * It is intentionally NOT in [subscribedSessionIds] — that's the whole point.
     */
    private fun privateSessionAgent(
        subscribedSessionIds: List<String> = listOf(publicSession1),
        outputSessionId: String? = null,
        resources: Map<String, String> = emptyMap()
    ): AgentInstance = AgentInstance(
        identity = Identity(
            uuid = agentId,
            localHandle = agentName.lowercase(),
            handle = agentHandle,
            name = agentName,
            parentHandle = "agent"
        ),
        modelProvider = "mock",
        modelName = "mock-model",
        subscribedSessionIds = subscribedSessionIds.map { IdentityUUID(it) },
        outputSessionId = outputSessionId?.let { IdentityUUID(it) },
        cognitiveStrategyId = PrivateSessionStrategy.identityHandle,
        cognitiveState = PrivateSessionStrategy.getInitialState(),
        resources = resources.mapValues { IdentityUUID(it.value) }
    )

    /**
     * Creates a FakeStore pre-populated with the given agent state.
     * Used for ensureInfrastructure tests that need to dispatch actions.
     */
    private fun fakeStoreWith(
        agent: AgentInstance,
        pendingPrivateSession: Boolean = false
    ): FakeStore {
        val agentState = AgentRuntimeState(
            agents = mapOf(IdentityUUID(agentId) to agent),
            agentStatuses = mapOf(
                IdentityUUID(agentId) to AgentStatusInfo(
                    pendingPrivateSessionCreation = pendingPrivateSession
                )
            )
        )

        val allDescriptors = testDescriptorsFor(setOf(
            ActionRegistry.Names.SESSION_CREATE,
            ActionRegistry.Names.AGENT_UPDATE_CONFIG,
            ActionRegistry.Names.AGENT_SET_PENDING_PRIVATE_SESSION,
            ActionRegistry.Names.CORE_REGISTER_IDENTITY
        ))

        val appState = AppState(
            featureStates = mapOf("agent" to agentState),
            actionDescriptors = allDescriptors
        )

        return FakeStore(appState, FakePlatformDependencies("test"))
    }

    // =========================================================================
    // 1. Identity & Registration
    // =========================================================================

    @Test
    fun `identityHandle should be in agent strategy namespace`() {
        assertTrue(
            PrivateSessionStrategy.identityHandle.handle.startsWith("agent.strategy."),
            "Strategy handle must be in agent.strategy.* namespace"
        )
    }

    @Test
    fun `identityHandle should be unique and not collide with existing strategies`() {
        val existingHandles = setOf(
            "agent.strategy.vanilla",
            "agent.strategy.minimal",
            "agent.strategy.sovereign",
            "agent.strategy.statemachine"
        )
        assertFalse(
            PrivateSessionStrategy.identityHandle.handle in existingHandles,
            "PrivateSessionStrategy handle must not collide with existing strategies"
        )
    }

    @Test
    fun `displayName should be human readable`() {
        assertTrue(PrivateSessionStrategy.displayName.isNotBlank())
    }

    @Test
    fun `hasAutoManagedOutputSession should be true`() {
        assertTrue(PrivateSessionStrategy.hasAutoManagedOutputSession,
            "PrivateSessionStrategy creates and manages its own private output session")
    }

    // =========================================================================
    // 2. getInitialState
    // =========================================================================

    @Test
    fun `getInitialState should return JsonNull`() {
        assertEquals(JsonNull, PrivateSessionStrategy.getInitialState())
    }

    // =========================================================================
    // 3. getResourceSlots
    // =========================================================================

    @Test
    fun `getResourceSlots should declare system instruction slot`() {
        val slots = PrivateSessionStrategy.getResourceSlots()

        assertEquals(1, slots.size, "Should have exactly one resource slot")
        val slot = slots.first()
        assertEquals("system_instruction", slot.slotId)
        assertEquals(AgentResourceType.SYSTEM_INSTRUCTION, slot.type)
        assertTrue(slot.isRequired, "System instruction should be required")
    }

    // =========================================================================
    // 4. getConfigFields
    // =========================================================================

    @Test
    fun `getConfigFields should declare outputSessionId field`() {
        val fields = PrivateSessionStrategy.getConfigFields()

        assertTrue(fields.any { it.key == "outputSessionId" },
            "Should expose output session config field for the UI")
        val outputField = fields.first { it.key == "outputSessionId" }
        assertEquals(StrategyConfigFieldType.OUTPUT_SESSION, outputField.type)
    }

    // =========================================================================
    // 5. getBuiltInResources
    // =========================================================================

    @Test
    fun `getBuiltInResources should return default system instruction`() {
        val resources = PrivateSessionStrategy.getBuiltInResources()

        assertTrue(resources.isNotEmpty(), "Should provide at least one built-in resource")
        val sysInstruction = resources.find { it.type == AgentResourceType.SYSTEM_INSTRUCTION }
        assertNotNull(sysInstruction, "Should include a system instruction resource")
        assertTrue(sysInstruction.isBuiltIn)
        assertTrue(sysInstruction.content.isNotBlank())
    }

    @Test
    fun `getBuiltInResources should have unique IDs`() {
        val resources = PrivateSessionStrategy.getBuiltInResources()
        val ids = resources.map { it.id }
        assertEquals(ids.distinct().size, ids.size, "Resource IDs must be unique")
    }

    // =========================================================================
    // 6. validateConfig — the critical behavioral divergence from Vanilla
    // =========================================================================

    @Test
    fun `validateConfig should NOT reset outputSessionId when it is not in subscribedSessionIds`() {
        val agent = privateSessionAgent(
            subscribedSessionIds = listOf(publicSession1, publicSession2),
            outputSessionId = privateSessionUUID
        )

        val validated = PrivateSessionStrategy.validateConfig(agent)

        assertEquals(
            IdentityUUID(privateSessionUUID), validated.outputSessionId,
            "Must NOT reset outputSessionId — private session is intentionally out-of-band"
        )
    }

    @Test
    fun `validateConfig should NOT auto-assign outputSessionId when null`() {
        val agent = privateSessionAgent(
            subscribedSessionIds = listOf(publicSession1),
            outputSessionId = null
        )

        val validated = PrivateSessionStrategy.validateConfig(agent)

        assertNull(
            validated.outputSessionId,
            "Must NOT auto-assign — private session is created by ensureInfrastructure"
        )
    }

    @Test
    fun `validateConfig should preserve valid outputSessionId`() {
        val agent = privateSessionAgent(outputSessionId = privateSessionUUID)
        val validated = PrivateSessionStrategy.validateConfig(agent)
        assertEquals(IdentityUUID(privateSessionUUID), validated.outputSessionId)
    }

    @Test
    fun `validateConfig should preserve null outputSessionId with no subscriptions`() {
        val agent = privateSessionAgent(
            subscribedSessionIds = emptyList(),
            outputSessionId = null
        )
        val validated = PrivateSessionStrategy.validateConfig(agent)
        assertNull(validated.outputSessionId)
    }

    @Test
    fun `validateConfig should not modify subscribedSessionIds`() {
        val agent = privateSessionAgent(
            subscribedSessionIds = listOf(publicSession1, publicSession2),
            outputSessionId = privateSessionUUID
        )

        val validated = PrivateSessionStrategy.validateConfig(agent)

        assertEquals(
            listOf(IdentityUUID(publicSession1), IdentityUUID(publicSession2)),
            validated.subscribedSessionIds,
            "validateConfig must never modify subscriptions"
        )
    }

    // =========================================================================
    // 7. buildPrompt
    // =========================================================================

    @Test
    fun `buildPrompt should include agent name and identity section`() {
        val context = AgentTurnContext(
            agentName = agentName,
            resolvedResources = emptyMap(),
            gatheredContextKeys = emptySet()
        )

        val builder = PrivateSessionStrategy.buildPrompt(context, JsonNull)

        val identity = builder.findSection("YOUR IDENTITY AND ROLE")
        assertNotNull(identity, "identity section should be emitted")
        assertTrue(identity.content.contains(agentName),
            "identity content should echo the injected agent name")
    }

    @Test
    fun `buildPrompt should include system instructions when provided`() {
        val sentinel = "SENTINEL_SYSINSTR_${kotlin.random.Random.nextInt()}"
        val context = AgentTurnContext(
            agentName = agentName,
            resolvedResources = mapOf("system_instruction" to sentinel),
            gatheredContextKeys = emptySet()
        )

        val builder = PrivateSessionStrategy.buildPrompt(context, JsonNull)

        val instructions = builder.findSection("SYSTEM INSTRUCTIONS")
        assertNotNull(instructions, "instructions section should be emitted when resource provided")
        assertTrue(instructions.content.contains(sentinel),
            "instructions content should echo the injected resource verbatim")
    }

    @Test
    fun `buildPrompt should not include instructions section when empty`() {
        val context = AgentTurnContext(
            agentName = agentName,
            resolvedResources = emptyMap(),
            gatheredContextKeys = emptySet()
        )

        val builder = PrivateSessionStrategy.buildPrompt(context, JsonNull)

        assertNull(builder.findSection("SYSTEM INSTRUCTIONS"),
            "instructions section should be omitted when no resource is provided")
    }

    @Test
    fun `buildPrompt should include multi-user environment awareness`() {
        val context = AgentTurnContext(
            agentName = agentName,
            resolvedResources = emptyMap(),
            gatheredContextKeys = emptySet()
        )

        val prompt = PrivateSessionStrategy.buildPrompt(context, JsonNull).renderForTest()

        assertTrue(prompt.contains("multi-user"), "Should mention multi-user environment")
        assertTrue(prompt.contains("Maintain your own boundaries"),
            "Should include boundary instruction")
    }

    @Test
    fun `buildPrompt should include private session routing section`() {
        val context = AgentTurnContext(
            agentName = agentName,
            resolvedResources = emptyMap(),
            gatheredContextKeys = emptySet()
        )

        val builder = PrivateSessionStrategy.buildPrompt(context, JsonNull)

        val routing = builder.findSection("PRIVATE SESSION ROUTING")
        assertNotNull(routing, "private session routing section should be emitted")
        assertTrue(routing.content.contains("session.POST"),
            "routing section should instruct the agent to use session.POST for public communication")
        assertTrue(routing.content.contains("private session", ignoreCase = true),
            "routing section should explain that responses go to the private session")
    }

    @Test
    fun `buildPrompt should include session POST example without senderId`() {
        val context = AgentTurnContext(
            agentName = agentName,
            resolvedResources = emptyMap(),
            gatheredContextKeys = emptySet()
        )

        val prompt = PrivateSessionStrategy.buildPrompt(context, JsonNull).renderForTest()

        assertTrue(prompt.contains("${Version.APP_TOOL_PREFIX}session.POST"),
            "Should include a fenced code block example for session.POST")
        assertTrue(prompt.contains("\"message\""),
            "session.POST example should include a message field")
        assertFalse(prompt.contains("\"senderId\""),
            "session.POST example should NOT include senderId — it is filled from the originator")
    }

    @Test
    fun `buildPrompt reserves SESSIONS gathered slot`() {
        // PRIVATE/PUBLIC tagging of individual sessions is owned by SessionContextFormatter
        // (see SessionContextFormatterT1Test). The strategy-level contract is only that
        // a SESSIONS slot is reserved so the pipeline's formatter has somewhere to render.
        val context = AgentTurnContext(
            agentName = agentName,
            resolvedResources = emptyMap(),
            gatheredContextKeys = emptySet(),
            subscribedSessions = listOf(
                SessionInfo(
                    uuid = publicSession1,
                    handle = "session.chat",
                    name = "Chat",
                    isOutput = false
                ),
                SessionInfo(
                    uuid = privateSessionUUID,
                    handle = "session.testbot-private",
                    name = "TestBot Private",
                    isOutput = true
                )
            ),
            outputSessionHandle = "session.testbot-private"
        )

        val builder = PrivateSessionStrategy.buildPrompt(context, JsonNull)

        assertTrue(builder.hasGathered("SESSIONS"),
            "strategy should reserve the SESSIONS partition slot for pipeline-formatted session content")
    }

    @Test
    fun `buildPrompt should explain that direct output is invisible to others`() {
        val context = AgentTurnContext(
            agentName = agentName,
            resolvedResources = emptyMap(),
            gatheredContextKeys = emptySet()
        )

        val prompt = PrivateSessionStrategy.buildPrompt(context, JsonNull).renderForTest()

        assertTrue(prompt.contains("invisible to others") || prompt.contains("only you can see"),
            "Should clearly state that the private session is not visible to others")
    }

    // NOTE: Per-session participant listings, message counts, and "no messages yet" text
    // are pipeline-owned (SessionContextFormatter), not strategy-owned. See
    // SessionContextFormatterT1Test for those contracts.

    @Test
    fun `buildPrompt reserves an everythingElse sink for pipeline-gathered contexts`() {
        // The strategy doesn't explicitly place MULTI_AGENT_CONTEXT, WORKSPACE, etc. —
        // it trusts the pipeline to deliver them through the RemainingGathered sink.
        val context = AgentTurnContext(
            agentName = agentName,
            resolvedResources = emptyMap(),
            gatheredContextKeys = setOf("MULTI_AGENT_CONTEXT")
        )
        val builder = PrivateSessionStrategy.buildPrompt(context, JsonNull)

        assertTrue(builder.sections.any { it is PromptSection.RemainingGathered },
            "strategy should end with everythingElse() so unlisted gathered keys still get placed")
    }

    @Test
    fun `buildPrompt does not explicitly place MULTI_AGENT_CONTEXT`() {
        // The strategy should NOT duplicate gathered keys that the pipeline handles via
        // everythingElse(). MULTI_AGENT_CONTEXT in particular must not be explicitly placed
        // AND swept up by the sink — that would render it twice. Regression guard.
        val context = AgentTurnContext(
            agentName = agentName,
            resolvedResources = emptyMap(),
            gatheredContextKeys = setOf("MULTI_AGENT_CONTEXT", "WORKSPACE")
        )
        val builder = PrivateSessionStrategy.buildPrompt(context, JsonNull)

        assertFalse(builder.hasGathered("MULTI_AGENT_CONTEXT"),
            "MULTI_AGENT_CONTEXT should not be explicitly placed — the everythingElse sink picks it up exactly once")
    }

    // =========================================================================
    // 8. postProcessResponse
    // =========================================================================

    @Test
    fun `postProcessResponse should always return PROCEED`() {
        val result = PrivateSessionStrategy.postProcessResponse("Hello world.", JsonNull)
        assertEquals(SentinelAction.PROCEED, result.action)
    }

    @Test
    fun `postProcessResponse should not modify state`() {
        val result = PrivateSessionStrategy.postProcessResponse("Any response.", JsonNull)
        assertEquals(JsonNull, result.newState)
    }

    @Test
    fun `postProcessResponse should return PROCEED even for sentinel-like content`() {
        val result = PrivateSessionStrategy.postProcessResponse(
            "[FAILURE_CODE: integrity_check_failed]", JsonNull)
        assertEquals(SentinelAction.PROCEED, result.action)
    }

    @Test
    fun `postProcessResponse should have no displayHint`() {
        val result = PrivateSessionStrategy.postProcessResponse("Response.", JsonNull)
        assertNull(result.displayHint,
            "Simple strategy should not set display hints")
    }

    // =========================================================================
    // 9. getValidNvramKeys
    // =========================================================================

    @Test
    fun `getValidNvramKeys should return null for stateless strategy`() {
        assertNull(PrivateSessionStrategy.getValidNvramKeys(),
            "Stateless strategy should impose no NVRAM key restrictions")
    }

    // =========================================================================
    // 10. ensureInfrastructure — Private Session Lifecycle
    //
    // Two-step guard: linked → no-op, pending → no-op, else → create.
    // Restart recovery relies on agent.json having persisted outputSessionId.
    // =========================================================================

    @Test
    fun `ensureInfrastructure should dispatch SESSION_CREATE when no outputSessionId and not pending`() {
        val agent = privateSessionAgent(outputSessionId = null)
        val store = fakeStoreWith(agent)

        PrivateSessionStrategy.ensureInfrastructure(agent, store.state.value.featureStates["agent"] as AgentRuntimeState, store)

        val sessionCreate = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.SESSION_CREATE
        }
        assertNotNull(sessionCreate, "Should dispatch SESSION_CREATE for private session")

        val payload = sessionCreate.payload!!
        assertEquals(agentHandle, payload["isPrivateTo"]?.jsonPrimitive?.content,
            "SESSION_CREATE must set isPrivateTo to agent's identity handle")

        val sessionName = payload["name"]?.jsonPrimitive?.content
        assertNotNull(sessionName, "SESSION_CREATE must include a session name")
        assertTrue(sessionName.contains(agentName) || sessionName.contains(agentName.lowercase()),
            "Private session name should reference the agent name, got: '$sessionName'")
    }

    @Test
    fun `ensureInfrastructure should set pending flag when creating session`() {
        val agent = privateSessionAgent(outputSessionId = null)
        val store = fakeStoreWith(agent)

        PrivateSessionStrategy.ensureInfrastructure(agent, store.state.value.featureStates["agent"] as AgentRuntimeState, store)

        val pendingAction = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.AGENT_SET_PENDING_PRIVATE_SESSION
        }
        assertNotNull(pendingAction, "Should dispatch SET_PENDING_PRIVATE_SESSION")
        assertTrue(
            pendingAction.payload?.get("pending")?.jsonPrimitive?.boolean == true,
            "Should set pending to true"
        )
        assertEquals(
            agentId,
            pendingAction.payload?.get("agentId")?.jsonPrimitive?.content,
            "Should target the correct agent"
        )
    }

    @Test
    fun `ensureInfrastructure should be no-op when outputSessionId is already set`() {
        val agent = privateSessionAgent(outputSessionId = privateSessionUUID)
        val store = fakeStoreWith(agent)

        PrivateSessionStrategy.ensureInfrastructure(agent, store.state.value.featureStates["agent"] as AgentRuntimeState, store)

        assertTrue(store.dispatchedActions.isEmpty(),
            "Should dispatch nothing when private session is already linked")
    }

    @Test
    fun `ensureInfrastructure should be no-op when pending flag is set`() {
        val agent = privateSessionAgent(outputSessionId = null)
        val store = fakeStoreWith(agent, pendingPrivateSession = true)

        PrivateSessionStrategy.ensureInfrastructure(agent, store.state.value.featureStates["agent"] as AgentRuntimeState, store)

        assertTrue(store.dispatchedActions.isEmpty(),
            "Should dispatch nothing when session creation is already pending")
    }

    @Test
    fun `ensureInfrastructure should set SESSION_CREATE isHidden to true`() {
        val agent = privateSessionAgent(outputSessionId = null)
        val store = fakeStoreWith(agent)

        PrivateSessionStrategy.ensureInfrastructure(agent, store.state.value.featureStates["agent"] as AgentRuntimeState, store)

        val sessionCreate = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.SESSION_CREATE
        }
        assertNotNull(sessionCreate)
        assertTrue(
            sessionCreate.payload?.get("isHidden")?.jsonPrimitive?.boolean == true,
            "Private session should be hidden from public session list"
        )
    }

    @Test
    fun `ensureInfrastructure should dispatch pending flag BEFORE session create`() {
        val agent = privateSessionAgent(outputSessionId = null)
        val store = fakeStoreWith(agent)

        PrivateSessionStrategy.ensureInfrastructure(agent, store.state.value.featureStates["agent"] as AgentRuntimeState, store)

        val pendingIndex = store.dispatchedActions.indexOfFirst {
            it.name == ActionRegistry.Names.AGENT_SET_PENDING_PRIVATE_SESSION
        }
        val createIndex = store.dispatchedActions.indexOfFirst {
            it.name == ActionRegistry.Names.SESSION_CREATE
        }

        assertTrue(pendingIndex >= 0, "Should dispatch pending flag")
        assertTrue(createIndex >= 0, "Should dispatch session create")
        assertTrue(pendingIndex < createIndex,
            "Pending flag must be dispatched BEFORE SESSION_CREATE to prevent duplicate creation")
    }

    @Test
    fun `ensureInfrastructure should dispatch exactly two actions when creating`() {
        val agent = privateSessionAgent(outputSessionId = null)
        val store = fakeStoreWith(agent)

        PrivateSessionStrategy.ensureInfrastructure(agent, store.state.value.featureStates["agent"] as AgentRuntimeState, store)

        assertEquals(2, store.dispatchedActions.size,
            "Should dispatch exactly SET_PENDING + SESSION_CREATE, nothing else")
    }

    // =========================================================================
    // 11. needsAdditionalContext / requestAdditionalContext
    // =========================================================================

    @Test
    fun `needsAdditionalContext should return false`() {
        val agent = privateSessionAgent()
        assertFalse(PrivateSessionStrategy.needsAdditionalContext(agent))
    }

    @Test
    fun `requestAdditionalContext should return false`() {
        val agent = privateSessionAgent()
        val store = fakeStoreWith(agent)
        assertFalse(PrivateSessionStrategy.requestAdditionalContext(agent, store))
    }

    // =========================================================================
    // 12. Lifecycle hooks — no-op verification
    // =========================================================================

    @Test
    fun `onAgentRegistered should not crash`() {
        val agent = privateSessionAgent()
        val store = fakeStoreWith(agent)
        PrivateSessionStrategy.onAgentRegistered(agent, store)
    }

    @Test
    fun `onAgentConfigChanged should not crash`() {
        val oldAgent = privateSessionAgent(outputSessionId = null)
        val newAgent = privateSessionAgent(outputSessionId = privateSessionUUID)
        val store = fakeStoreWith(newAgent)
        PrivateSessionStrategy.onAgentConfigChanged(oldAgent, newAgent, store)
    }

    // =========================================================================
    // 13. Prompt structure edge cases
    // =========================================================================

    @Test
    fun `buildPrompt reserves SESSIONS slot even when no subscriptions`() {
        // The strategy is subscription-agnostic: it always reserves the SESSIONS slot.
        // Rendering an empty sessions section (or suppressing the whole thing) is
        // the pipeline formatter's job — see SessionContextFormatterT1Test.
        //
        // (Historical note: an earlier test asserted `!prompt.contains("SUBSCRIBED SESSIONS")`
        // here — it passed vacuously because section keys aren't emitted by renderForTest,
        // not because the strategy actually omitted the slot.)
        val context = AgentTurnContext(
            agentName = agentName,
            resolvedResources = mapOf("system_instruction" to "Test instructions."),
            gatheredContextKeys = emptySet(),
            subscribedSessions = emptyList()
        )

        val builder = PrivateSessionStrategy.buildPrompt(context, JsonNull)

        assertTrue(builder.hasGathered("SESSIONS"),
            "strategy reserves SESSIONS regardless of subscription count — empty-rendering is pipeline-owned")
    }

    // NOTE: Single-session handling and "first session as PRIVATE" fallback rules are
    // pipeline-formatter concerns — see SessionContextFormatterT1Test. The strategy only
    // reserves the SESSIONS gathered slot (tested above).

    @Test
    fun `buildPrompt routing section should appear before SESSIONS slot`() {
        // Structural ordering contract on the strategy: the routing Section must be emitted
        // BEFORE the SESSIONS GatheredRef so the agent reads the routing paradigm before
        // its session listing. Pipeline renders them in that order.
        val context = AgentTurnContext(
            agentName = agentName,
            resolvedResources = emptyMap(),
            gatheredContextKeys = emptySet(),
            subscribedSessions = listOf(
                SessionInfo(
                    uuid = publicSession1,
                    handle = "session.chat",
                    name = "Chat",
                    isOutput = false
                )
            )
        )
        val builder = PrivateSessionStrategy.buildPrompt(context, JsonNull)

        val routingIdx = builder.sections.indexOfFirst {
            it is PromptSection.Section && it.key == "PRIVATE SESSION ROUTING"
        }
        val sessionsIdx = builder.sections.indexOfFirst {
            it is PromptSection.GatheredRef && it.key == "SESSIONS"
        }

        assertTrue(routingIdx >= 0, "routing section should be present")
        assertTrue(sessionsIdx >= 0, "SESSIONS slot should be reserved")
        assertTrue(routingIdx < sessionsIdx,
            "routing (idx=$routingIdx) should come before SESSIONS slot (idx=$sessionsIdx)")
    }

    // =========================================================================
    // 14. Singleton check
    // =========================================================================

    @Test
    fun `strategy should be a singleton object`() {
        assertSame(PrivateSessionStrategy, PrivateSessionStrategy)
    }
}