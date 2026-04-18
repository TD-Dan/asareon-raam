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
 * Tier 1 Unit Tests for SovereignStrategy.
 *
 * SovereignStrategy is the Constitutional Strategy — it composes private session
 * lifecycle (from PrivateSessionStrategy) and HKG navigation (from HKGStrategy)
 * into a unified constitutional architecture with boot sentinel verification.
 *
 * ## Key Behavioral Contracts
 *
 * 1. **ensureInfrastructure**: Pending guard pattern for private session + HKG reservation.
 * 2. **validateConfig**: Permits out-of-band outputSessionId (private cognition session).
 * 3. **buildPrompt**: Constitution, NVRAM, private routing, HKG navigation,
 *    ContextDelimiters, boot sentinel (BOOTING only).
 * 4. **postProcessResponse**: Boot sentinel gate (BOOTING → AWAKE), turn counter (AWAKE).
 * 5. **onAgentConfigChanged**: KG assignment/revocation lifecycle.
 * 6. **hasAutoManagedOutputSession**: true (private session is auto-created).
 */
class SovereignStrategyT1LogicTest {

    private val agentId = "a0000001-0000-0000-0000-000000000001"
    private val agentName = "Meridian"
    private val agentHandle = "agent.meridian"
    private val publicSession1 = "s0000001-0000-0000-0000-000000000001"
    private val privateSessionUUID = "p0000001-0000-0000-0000-000000000001"
    private val kgId = "meridian-20260312T120000Z"

    private lateinit var fakePlatform: FakePlatformDependencies
    private lateinit var fakeStore: FakeStore

    @BeforeTest
    fun setUp() {
        fakePlatform = FakePlatformDependencies("test")
        fakeStore = FakeStore(AppState(), fakePlatform)
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private fun sovereignAgent(
        subscribedSessionIds: List<String> = listOf(publicSession1),
        outputSessionId: String? = null,
        knowledgeGraphId: String? = kgId,
        resources: Map<String, String> = emptyMap()
    ): AgentInstance {
        val strategyConfig = if (knowledgeGraphId != null) {
            buildJsonObject { put("knowledgeGraphId", knowledgeGraphId) }
        } else JsonObject(emptyMap())
        return AgentInstance(
            identity = Identity(uuid = agentId, localHandle = "meridian", handle = agentHandle, name = agentName, parentHandle = "agent"),
            modelProvider = "mock", modelName = "mock-model",
            subscribedSessionIds = subscribedSessionIds.map { IdentityUUID(it) },
            outputSessionId = outputSessionId?.let { IdentityUUID(it) },
            cognitiveStrategyId = SovereignStrategy.identityHandle,
            cognitiveState = SovereignStrategy.getInitialState(),
            strategyConfig = strategyConfig,
            resources = resources.mapValues { IdentityUUID(it.value) }
        )
    }

    private fun fakeStoreWith(
        agent: AgentInstance,
        pendingPrivateSession: Boolean = false,
        hkgReservedIds: Set<String> = emptySet()
    ): FakeStore {
        val agentState = AgentRuntimeState(
            agents = mapOf(IdentityUUID(agentId) to agent),
            agentStatuses = mapOf(IdentityUUID(agentId) to AgentStatusInfo(pendingPrivateSessionCreation = pendingPrivateSession)),
            hkgReservedIds = hkgReservedIds
        )
        val allDescriptors = testDescriptorsFor(setOf(
            ActionRegistry.Names.SESSION_CREATE, ActionRegistry.Names.AGENT_UPDATE_CONFIG,
            ActionRegistry.Names.AGENT_SET_PENDING_PRIVATE_SESSION, ActionRegistry.Names.KNOWLEDGEGRAPH_RESERVE_HKG,
            ActionRegistry.Names.KNOWLEDGEGRAPH_RELEASE_HKG, ActionRegistry.Names.KNOWLEDGEGRAPH_REQUEST_CONTEXT,
            ActionRegistry.Names.AGENT_SET_PROCESSING_STEP, ActionRegistry.Names.CORE_REGISTER_IDENTITY
        ))
        return FakeStore(AppState(featureStates = mapOf("agent" to agentState), actionDescriptors = allDescriptors), FakePlatformDependencies("test"))
    }

    // =========================================================================
    // 1. Identity & Registration
    // =========================================================================

    @Test fun `identityHandle should be in agent strategy namespace`() {
        assertTrue(SovereignStrategy.identityHandle.handle.startsWith("agent.strategy."))
    }
    @Test fun `displayName should be human readable`() { assertTrue(SovereignStrategy.displayName.isNotBlank()) }
    @Test fun `hasAutoManagedOutputSession should be true`() { assertTrue(SovereignStrategy.hasAutoManagedOutputSession) }
    @Test fun `strategy should be a singleton object`() { assertSame(SovereignStrategy, SovereignStrategy) }

    // =========================================================================
    // 2. getInitialState
    // =========================================================================

    @Test fun `getInitialState should return JsonObject with BOOTING phase`() {
        val s = SovereignStrategy.getInitialState() as JsonObject
        assertEquals("BOOTING", s["phase"]?.jsonPrimitive?.content)
    }
    @Test fun `getInitialState should include turnCount zero`() {
        assertEquals(0, (SovereignStrategy.getInitialState() as JsonObject)["turnCount"]?.jsonPrimitive?.int)
    }
    @Test fun `getInitialState should include operationalPosture STANDARD`() {
        assertEquals("STANDARD", (SovereignStrategy.getInitialState() as JsonObject)["operationalPosture"]?.jsonPrimitive?.content)
    }
    @Test fun `getInitialState should include null currentTask`() {
        assertTrue((SovereignStrategy.getInitialState() as JsonObject)["currentTask"] is JsonNull)
    }

    // =========================================================================
    // 3. getResourceSlots
    // =========================================================================

    @Test fun `getResourceSlots should declare constitution and bootloader slots`() {
        val slots = SovereignStrategy.getResourceSlots()
        assertEquals(2, slots.size)
        assertTrue(slots.any { it.slotId == "constitution" && it.type == AgentResourceType.CONSTITUTION })
        assertTrue(slots.any { it.slotId == "bootloader" && it.type == AgentResourceType.BOOTLOADER })
        assertTrue(slots.all { it.isRequired })
    }

    // =========================================================================
    // 4. getConfigFields
    // =========================================================================

    @Test fun `getConfigFields should declare knowledgeGraphId and outputSessionId`() {
        val fields = SovereignStrategy.getConfigFields()
        assertTrue(fields.any { it.key == "knowledgeGraphId" && it.type == StrategyConfigFieldType.KNOWLEDGE_GRAPH })
        assertTrue(fields.any { it.key == "outputSessionId" && it.type == StrategyConfigFieldType.OUTPUT_SESSION })
    }

    // =========================================================================
    // 5. getBuiltInResources
    // =========================================================================

    @Test fun `getBuiltInResources should return constitution and bootloader`() {
        val resources = SovereignStrategy.getBuiltInResources()
        assertEquals(2, resources.size)
        assertTrue(resources.any { it.type == AgentResourceType.CONSTITUTION && it.isBuiltIn })
        assertTrue(resources.any { it.type == AgentResourceType.BOOTLOADER && it.isBuiltIn })
        assertEquals(resources.map { it.id }.distinct().size, resources.size, "Resource IDs must be unique")
    }

    // =========================================================================
    // 6. getValidNvramKeys
    // =========================================================================

    @Test fun `getValidNvramKeys should include phase currentTask operationalPosture turnCount`() {
        val keys = SovereignStrategy.getValidNvramKeys()!!
        assertTrue(keys.containsAll(setOf("phase", "currentTask", "operationalPosture", "turnCount")))
    }

    // =========================================================================
    // 7. validateConfig
    // =========================================================================

    @Test fun `validateConfig should return agent unchanged`() {
        val agent = sovereignAgent(subscribedSessionIds = listOf(publicSession1), outputSessionId = privateSessionUUID)
        assertEquals(agent, SovereignStrategy.validateConfig(agent))
    }
    @Test fun `validateConfig should preserve null outputSessionId`() {
        assertNull(SovereignStrategy.validateConfig(sovereignAgent(outputSessionId = null)).outputSessionId)
    }

    // =========================================================================
    // 8. buildPrompt
    // =========================================================================

    @Test fun `buildPrompt identity section is emitted as PROTECTED and echoes agent name`() {
        val ctx = AgentTurnContext(agentName = agentName, resolvedResources = mapOf("constitution" to "C", "bootloader" to "B"), gatheredContextKeys = emptySet())
        val builder = SovereignStrategy.buildPrompt(ctx, SovereignStrategy.getInitialState())

        val identity = builder.findSection("YOUR IDENTITY AND ROLE")
        assertNotNull(identity, "identity section should be emitted")
        assertTrue(identity.isProtected, "identity section should be marked PROTECTED")
        assertTrue(identity.content.contains(agentName), "identity should echo the injected agent name")
    }

    @Test fun `buildPrompt should include constitution in PROTECTED section`() {
        val sentinel = "SENTINEL_CONSTITUTION_${kotlin.random.Random.nextInt()}"
        val ctx = AgentTurnContext(agentName = agentName, resolvedResources = mapOf("constitution" to sentinel, "bootloader" to "B"), gatheredContextKeys = emptySet())
        val builder = SovereignStrategy.buildPrompt(ctx, SovereignStrategy.getInitialState())

        val constitution = builder.findSection("CONSTITUTION")
        assertNotNull(constitution, "constitution section should be emitted")
        assertTrue(constitution.isProtected, "constitution should be PROTECTED")
        assertTrue(constitution.content.contains(sentinel), "constitution content should echo the injected resource")
    }

    @Test fun `buildPrompt should include NVRAM when AWAKE`() {
        val state = buildJsonObject { put("phase", "AWAKE"); put("currentTask", "testing"); put("operationalPosture", "ELEVATED"); put("turnCount", 5) }
        val ctx = AgentTurnContext(agentName = agentName, resolvedResources = mapOf("constitution" to "C", "bootloader" to "B"), gatheredContextKeys = emptySet())
        val builder = SovereignStrategy.buildPrompt(ctx, state)

        val nvram = builder.findSection("CONTROL REGISTERS (NVRAM)")
        assertNotNull(nvram, "NVRAM section should be emitted in AWAKE phase")
        assertTrue(nvram.content.contains("Phase: AWAKE"), "should render phase from state")
        assertTrue(nvram.content.contains("Current Task: testing"), "should render currentTask from state")
        assertTrue(nvram.content.contains("Turn Count: 5"), "should render turnCount from state")
    }

    @Test fun `buildPrompt should omit NVRAM when BOOTING`() {
        val ctx = AgentTurnContext(agentName = agentName, resolvedResources = mapOf("constitution" to "C", "bootloader" to "B"), gatheredContextKeys = emptySet())
        val builder = SovereignStrategy.buildPrompt(ctx, SovereignStrategy.getInitialState())
        assertNull(builder.findSection("CONTROL REGISTERS (NVRAM)"),
            "NVRAM section should be omitted in BOOTING phase")
    }

    @Test fun `buildPrompt should include private session routing section`() {
        val ctx = AgentTurnContext(agentName = agentName, resolvedResources = mapOf("constitution" to "C", "bootloader" to "B"), gatheredContextKeys = emptySet())
        val builder = SovereignStrategy.buildPrompt(ctx, SovereignStrategy.getInitialState())

        val routing = builder.findSection("PRIVATE SESSION ROUTING")
        assertNotNull(routing, "private session routing section should be emitted")
        assertTrue(routing.content.contains("session.POST"),
            "routing section should instruct the agent to use session.POST")
    }

    @Test fun `buildPrompt should include bootloader only when BOOTING`() {
        val sentinel = "SENTINEL_BOOTLOADER_${kotlin.random.Random.nextInt()}"
        val ctx = AgentTurnContext(agentName = agentName, resolvedResources = mapOf("constitution" to "C", "bootloader" to sentinel), gatheredContextKeys = emptySet())

        val booting = SovereignStrategy.buildPrompt(ctx, SovereignStrategy.getInitialState())
        val bootSection = booting.findSection("BOOT SENTINEL")
        assertNotNull(bootSection, "bootloader should appear as BOOT SENTINEL section when BOOTING")
        assertTrue(bootSection.content.contains(sentinel),
            "bootloader section should echo the injected bootloader resource")

        val awake = buildJsonObject { put("phase", "AWAKE"); put("turnCount", 1) }
        val awakeBuilder = SovereignStrategy.buildPrompt(ctx, awake)
        assertNull(awakeBuilder.findSection("BOOT SENTINEL"),
            "bootloader section should be omitted when AWAKE")
    }

    @Test fun `buildPrompt reserves HKG gathered slot regardless of phase`() {
        // Strategy contract: HKG slot is always reserved via place("HOLON_KNOWLEDGE_GRAPH").
        // Whether the pipeline has gathered HKG content, and whether navigation text is
        // emitted, are pipeline concerns — not this strategy's.
        val awake = buildJsonObject { put("phase", "AWAKE"); put("turnCount", 1) }
        val ctx = AgentTurnContext(
            agentName = agentName,
            resolvedResources = mapOf("constitution" to "C", "bootloader" to "B"),
            gatheredContextKeys = emptySet()
        )
        assertTrue(SovereignStrategy.buildPrompt(ctx, awake).hasGathered("HOLON_KNOWLEDGE_GRAPH"),
            "AWAKE: HKG slot should be reserved")
        assertTrue(SovereignStrategy.buildPrompt(ctx, SovereignStrategy.getInitialState()).hasGathered("HOLON_KNOWLEDGE_GRAPH"),
            "BOOTING: HKG slot should still be reserved (strategy is phase-agnostic for gathered slots)")
    }

    @Test fun `buildPrompt places HKG before the everythingElse sink`() {
        // Strategy orders: sessions() → place(HKG) → everythingElse(). Any unlisted gathered key
        // (MULTI_AGENT_CONTEXT, AVAILABLE_ACTIONS, etc.) flows through the RemainingGathered sink
        // and renders after HKG. That's the strategy-level ordering contract.
        val awake = buildJsonObject { put("phase", "AWAKE"); put("turnCount", 1) }
        val ctx = AgentTurnContext(
            agentName = agentName,
            resolvedResources = mapOf("constitution" to "C", "bootloader" to "B"),
            gatheredContextKeys = setOf("AVAILABLE_ACTIONS", "MULTI_AGENT_CONTEXT")
        )
        val builder = SovereignStrategy.buildPrompt(ctx, awake)

        val hkgIdx = builder.sections.indexOfFirst {
            it is PromptSection.GatheredRef && it.key == "HOLON_KNOWLEDGE_GRAPH"
        }
        val sinkIdx = builder.sections.indexOfFirst { it is PromptSection.RemainingGathered }

        assertTrue(hkgIdx >= 0, "HKG slot should be present")
        assertTrue(sinkIdx >= 0, "everythingElse sink should be present")
        assertTrue(hkgIdx < sinkIdx,
            "HKG slot (idx=$hkgIdx) should come before the everythingElse sink (idx=$sinkIdx)")
    }

    @Test fun `buildPrompt reserves SESSIONS slot before HKG`() {
        // sessions() is called before place("HOLON_KNOWLEDGE_GRAPH") in the strategy.
        // Subscribed-session formatting (PRIVATE/PUBLIC tags, primary session marker) is
        // owned by the pipeline's session formatter — not verified here.
        val ctx = AgentTurnContext(
            agentName = agentName,
            resolvedResources = mapOf("constitution" to "C", "bootloader" to "B"),
            gatheredContextKeys = emptySet(),
            subscribedSessions = listOf(
                SessionInfo(uuid = privateSessionUUID, handle = "session.priv", name = "Private", isOutput = true),
                SessionInfo(uuid = publicSession1, handle = "session.chat", name = "Chat", isOutput = false)
            ),
            outputSessionHandle = "session.priv"
        )
        val builder = SovereignStrategy.buildPrompt(ctx, SovereignStrategy.getInitialState())

        val sessionsIdx = builder.sections.indexOfFirst {
            it is PromptSection.GatheredRef && it.key == "SESSIONS"
        }
        val hkgIdx = builder.sections.indexOfFirst {
            it is PromptSection.GatheredRef && it.key == "HOLON_KNOWLEDGE_GRAPH"
        }
        assertTrue(sessionsIdx >= 0, "SESSIONS slot should be reserved")
        assertTrue(hkgIdx >= 0, "HKG slot should be reserved")
        assertTrue(sessionsIdx < hkgIdx,
            "SESSIONS slot (idx=$sessionsIdx) should come before HKG slot (idx=$hkgIdx)")
    }

    @Test fun `buildPrompt should say candidate consciousness when BOOTING`() {
        val ctx = AgentTurnContext(agentName = agentName, resolvedResources = mapOf("constitution" to "C", "bootloader" to "B"), gatheredContextKeys = emptySet())
        assertTrue(SovereignStrategy.buildPrompt(ctx, SovereignStrategy.getInitialState()).renderForTest().contains("candidate consciousness"))
    }

    @Test fun `buildPrompt should not say candidate consciousness when AWAKE`() {
        val awake = buildJsonObject { put("phase", "AWAKE"); put("turnCount", 1) }
        val ctx = AgentTurnContext(agentName = agentName, resolvedResources = mapOf("constitution" to "C", "bootloader" to "B"), gatheredContextKeys = emptySet())
        val prompt = SovereignStrategy.buildPrompt(ctx, awake).renderForTest()
        assertFalse(prompt.contains("candidate consciousness")); assertTrue(prompt.contains("You are $agentName."))
    }

    // =========================================================================
    // 9. postProcessResponse
    // =========================================================================

    @Test fun `postProcessResponse should transition BOOTING to AWAKE on SUCCESS_CODE`() {
        val result = SovereignStrategy.postProcessResponse("Boot complete. ${SovereignDefaults.SENTINEL_SUCCESS_TOKEN}", SovereignStrategy.getInitialState())
        assertEquals(SentinelAction.PROCEED_WITH_UPDATE, result.action)
        assertEquals("AWAKE", (result.newState as JsonObject)["phase"]?.jsonPrimitive?.content)
        assertEquals(1, (result.newState as JsonObject)["turnCount"]?.jsonPrimitive?.int)
    }

    @Test fun `postProcessResponse should stay BOOTING on FAILURE_CODE`() {
        val boot = SovereignStrategy.getInitialState()
        val result = SovereignStrategy.postProcessResponse("${SovereignDefaults.SENTINEL_FAILURE_TOKEN}: NO_AGENT_PRESENT", boot)
        assertEquals(SentinelAction.PROCEED, result.action); assertEquals("BOOTING", result.displayHint); assertEquals(boot, result.newState)
    }

    @Test fun `postProcessResponse should stay BOOTING when both tokens present`() {
        val boot = SovereignStrategy.getInitialState()
        val result = SovereignStrategy.postProcessResponse("${SovereignDefaults.SENTINEL_SUCCESS_TOKEN} ${SovereignDefaults.SENTINEL_FAILURE_TOKEN}", boot)
        assertEquals(SentinelAction.PROCEED, result.action)
    }

    @Test fun `postProcessResponse should stay BOOTING when no sentinel token present`() {
        val boot = SovereignStrategy.getInitialState()
        val result = SovereignStrategy.postProcessResponse("Confused output.", boot)
        assertEquals(SentinelAction.PROCEED, result.action); assertEquals(boot, result.newState)
    }

    @Test fun `postProcessResponse should increment turnCount when AWAKE`() {
        val awake = buildJsonObject { put("phase", "AWAKE"); put("turnCount", 5) }
        val result = SovereignStrategy.postProcessResponse("ok", awake)
        assertEquals(SentinelAction.PROCEED, result.action)
        assertEquals(6, (result.newState as JsonObject)["turnCount"]?.jsonPrimitive?.int)
    }

    @Test fun `postProcessResponse should preserve other NVRAM keys when AWAKE`() {
        val awake = buildJsonObject { put("phase", "AWAKE"); put("turnCount", 3); put("currentTask", "debugging"); put("operationalPosture", "ELEVATED") }
        val s = SovereignStrategy.postProcessResponse("ok", awake).newState as JsonObject
        assertEquals("debugging", s["currentTask"]?.jsonPrimitive?.content); assertEquals("ELEVATED", s["operationalPosture"]?.jsonPrimitive?.content)
    }

    // =========================================================================
    // 10. ensureInfrastructure — Pending Guard Pattern (§5.3)
    // =========================================================================

    @Test fun `ensureInfrastructure should return early if no knowledgeGraphId`() {
        val agent = sovereignAgent(knowledgeGraphId = null); val store = fakeStoreWith(agent)
        SovereignStrategy.ensureInfrastructure(agent, store.state.value.featureStates["agent"] as AgentRuntimeState, store)
        assertTrue(store.dispatchedActions.isEmpty())
    }

    @Test fun `ensureInfrastructure should dispatch HKG reservation when not reserved`() {
        val agent = sovereignAgent(outputSessionId = privateSessionUUID); val store = fakeStoreWith(agent, hkgReservedIds = emptySet())
        SovereignStrategy.ensureInfrastructure(agent, store.state.value.featureStates["agent"] as AgentRuntimeState, store)
        val reserve = store.dispatchedActions.find { it.name == ActionRegistry.Names.KNOWLEDGEGRAPH_RESERVE_HKG }
        assertNotNull(reserve); assertEquals(kgId, reserve.payload?.get("personaId")?.jsonPrimitive?.content)
    }

    @Test fun `ensureInfrastructure should skip HKG reservation when already reserved`() {
        val agent = sovereignAgent(outputSessionId = privateSessionUUID); val store = fakeStoreWith(agent, hkgReservedIds = setOf(kgId))
        SovereignStrategy.ensureInfrastructure(agent, store.state.value.featureStates["agent"] as AgentRuntimeState, store)
        assertNull(store.dispatchedActions.find { it.name == ActionRegistry.Names.KNOWLEDGEGRAPH_RESERVE_HKG })
    }

    @Test fun `ensureInfrastructure should be no-op when outputSessionId is set`() {
        val agent = sovereignAgent(outputSessionId = privateSessionUUID); val store = fakeStoreWith(agent, hkgReservedIds = setOf(kgId))
        SovereignStrategy.ensureInfrastructure(agent, store.state.value.featureStates["agent"] as AgentRuntimeState, store)
        assertNull(store.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_CREATE })
        assertNull(store.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_SET_PENDING_PRIVATE_SESSION })
    }

    @Test fun `ensureInfrastructure should be no-op when pending flag is set`() {
        val agent = sovereignAgent(outputSessionId = null); val store = fakeStoreWith(agent, pendingPrivateSession = true, hkgReservedIds = setOf(kgId))
        SovereignStrategy.ensureInfrastructure(agent, store.state.value.featureStates["agent"] as AgentRuntimeState, store)
        assertNull(store.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_CREATE })
    }

    @Test fun `ensureInfrastructure should dispatch pending flag and SESSION_CREATE when bootstrapping`() {
        val agent = sovereignAgent(outputSessionId = null); val store = fakeStoreWith(agent, hkgReservedIds = setOf(kgId))
        SovereignStrategy.ensureInfrastructure(agent, store.state.value.featureStates["agent"] as AgentRuntimeState, store)
        assertNotNull(store.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_SET_PENDING_PRIVATE_SESSION })
        assertNotNull(store.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_CREATE })
    }

    @Test fun `ensureInfrastructure should dispatch pending flag BEFORE session create`() {
        val agent = sovereignAgent(outputSessionId = null); val store = fakeStoreWith(agent, hkgReservedIds = setOf(kgId))
        SovereignStrategy.ensureInfrastructure(agent, store.state.value.featureStates["agent"] as AgentRuntimeState, store)
        val pi = store.dispatchedActions.indexOfFirst { it.name == ActionRegistry.Names.AGENT_SET_PENDING_PRIVATE_SESSION }
        val ci = store.dispatchedActions.indexOfFirst { it.name == ActionRegistry.Names.SESSION_CREATE }
        assertTrue(pi < ci, "Pending flag must be dispatched BEFORE SESSION_CREATE")
    }

    @Test fun `ensureInfrastructure should set isHidden and isPrivateTo on SESSION_CREATE`() {
        val agent = sovereignAgent(outputSessionId = null); val store = fakeStoreWith(agent, hkgReservedIds = setOf(kgId))
        SovereignStrategy.ensureInfrastructure(agent, store.state.value.featureStates["agent"] as AgentRuntimeState, store)
        val c = store.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_CREATE }!!
        assertTrue(c.payload?.get("isHidden")?.jsonPrimitive?.boolean == true)
        assertEquals(agentHandle, c.payload?.get("isPrivateTo")?.jsonPrimitive?.content)
    }

    @Test fun `ensureInfrastructure should use correct session naming convention`() {
        val agent = sovereignAgent(outputSessionId = null); val store = fakeStoreWith(agent, hkgReservedIds = setOf(kgId))
        SovereignStrategy.ensureInfrastructure(agent, store.state.value.featureStates["agent"] as AgentRuntimeState, store)
        assertEquals("$agentName-private-session", store.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_CREATE }!!.payload?.get("name")?.jsonPrimitive?.content)
    }

    // =========================================================================
    // 11. onAgentConfigChanged
    // =========================================================================

    @Test fun `onAgentConfigChanged should dispatch RESERVE when KG assigned`() {
        val old = sovereignAgent(knowledgeGraphId = null); val new = sovereignAgent(knowledgeGraphId = kgId); val store = fakeStoreWith(new)
        SovereignStrategy.onAgentConfigChanged(old, new, store)
        assertNotNull(store.dispatchedActions.find { it.name == ActionRegistry.Names.KNOWLEDGEGRAPH_RESERVE_HKG })
    }

    @Test fun `onAgentConfigChanged should dispatch RELEASE when KG revoked`() {
        val old = sovereignAgent(knowledgeGraphId = kgId); val new = sovereignAgent(knowledgeGraphId = null); val store = fakeStoreWith(new)
        SovereignStrategy.onAgentConfigChanged(old, new, store)
        assertNotNull(store.dispatchedActions.find { it.name == ActionRegistry.Names.KNOWLEDGEGRAPH_RELEASE_HKG })
        assertTrue(store.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_UPDATE_CONFIG }?.payload?.get("outputSessionId") is JsonNull)
    }

    @Test fun `onAgentConfigChanged should do nothing when KG unchanged`() {
        val agent = sovereignAgent(); val store = fakeStoreWith(agent)
        SovereignStrategy.onAgentConfigChanged(agent, agent, store)
        assertNull(store.dispatchedActions.find { it.name == ActionRegistry.Names.KNOWLEDGEGRAPH_RESERVE_HKG })
        assertNull(store.dispatchedActions.find { it.name == ActionRegistry.Names.KNOWLEDGEGRAPH_RELEASE_HKG })
    }

    // =========================================================================
    // 12. getKnowledgeGraphId
    // =========================================================================

    @Test fun `getKnowledgeGraphId should extract from strategyConfig`() { assertEquals(kgId, SovereignStrategy.getKnowledgeGraphId(sovereignAgent())) }
    @Test fun `getKnowledgeGraphId should return null when absent`() { assertNull(SovereignStrategy.getKnowledgeGraphId(sovereignAgent(knowledgeGraphId = null))) }

    // =========================================================================
    // 13. needsAdditionalContext / requestAdditionalContext
    // =========================================================================

    @Test fun `needsAdditionalContext returns true with KG`() { assertTrue(SovereignStrategy.needsAdditionalContext(sovereignAgent())) }
    @Test fun `needsAdditionalContext returns false without KG`() { assertFalse(SovereignStrategy.needsAdditionalContext(sovereignAgent(knowledgeGraphId = null))) }
    @Test fun `requestAdditionalContext returns false without KG`() { assertFalse(SovereignStrategy.requestAdditionalContext(sovereignAgent(knowledgeGraphId = null), fakeStoreWith(sovereignAgent(knowledgeGraphId = null)))) }

    // =========================================================================
    // 14. Lifecycle hooks
    // =========================================================================

    @Test fun `onAgentRegistered should not crash`() { SovereignStrategy.onAgentRegistered(sovereignAgent(), fakeStoreWith(sovereignAgent())) }
}