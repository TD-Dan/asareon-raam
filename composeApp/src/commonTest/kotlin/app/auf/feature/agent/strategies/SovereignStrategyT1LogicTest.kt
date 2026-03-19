package app.auf.feature.agent.strategies

import app.auf.core.*
import app.auf.core.generated.ActionRegistry
import app.auf.fakes.FakePlatformDependencies
import app.auf.fakes.FakeStore
import app.auf.feature.agent.*
import app.auf.test.testDescriptorsFor
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
 * 3. **prepareSystemPrompt**: Constitution, NVRAM, private routing, HKG navigation,
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
    // 8. prepareSystemPrompt
    // =========================================================================

    @Test fun `prepareSystemPrompt should include agent name and identity section with PROTECTED badge`() {
        val ctx = AgentTurnContext(agentName = agentName, resolvedResources = mapOf("constitution" to "C", "bootloader" to "B"), gatheredContexts = emptyMap())
        val prompt = SovereignStrategy.prepareSystemPrompt(ctx, SovereignStrategy.getInitialState())
        assertTrue(prompt.contains(agentName)); assertTrue(prompt.contains("YOUR IDENTITY AND ROLE")); assertTrue(prompt.contains("[PROTECTED]"))
    }

    @Test fun `prepareSystemPrompt should include constitution in PROTECTED section`() {
        val ctx = AgentTurnContext(agentName = agentName, resolvedResources = mapOf("constitution" to "Supreme Law Here", "bootloader" to "B"), gatheredContexts = emptyMap())
        val prompt = SovereignStrategy.prepareSystemPrompt(ctx, SovereignStrategy.getInitialState())
        assertTrue(prompt.contains("CONSTITUTION")); assertTrue(prompt.contains("Supreme Law Here"))
    }

    @Test fun `prepareSystemPrompt should include NVRAM when AWAKE`() {
        val state = buildJsonObject { put("phase", "AWAKE"); put("currentTask", "testing"); put("operationalPosture", "ELEVATED"); put("turnCount", 5) }
        val ctx = AgentTurnContext(agentName = agentName, resolvedResources = mapOf("constitution" to "C", "bootloader" to "B"), gatheredContexts = emptyMap())
        val prompt = SovereignStrategy.prepareSystemPrompt(ctx, state)
        assertTrue(prompt.contains("CONTROL REGISTERS")); assertTrue(prompt.contains("Phase: AWAKE"))
        assertTrue(prompt.contains("Current Task: testing")); assertTrue(prompt.contains("Turn Count: 5"))
    }

    @Test fun `prepareSystemPrompt should omit NVRAM when BOOTING`() {
        val ctx = AgentTurnContext(agentName = agentName, resolvedResources = mapOf("constitution" to "C", "bootloader" to "B"), gatheredContexts = emptyMap())
        assertFalse(SovereignStrategy.prepareSystemPrompt(ctx, SovereignStrategy.getInitialState()).contains("CONTROL REGISTERS"))
    }

    @Test fun `prepareSystemPrompt should include private session routing section`() {
        val ctx = AgentTurnContext(agentName = agentName, resolvedResources = mapOf("constitution" to "C", "bootloader" to "B"), gatheredContexts = emptyMap())
        val prompt = SovereignStrategy.prepareSystemPrompt(ctx, SovereignStrategy.getInitialState())
        assertTrue(prompt.contains("PRIVATE SESSION ROUTING")); assertTrue(prompt.contains("session.POST"))
    }

    @Test fun `prepareSystemPrompt should include bootloader only when BOOTING`() {
        val ctx = AgentTurnContext(agentName = agentName, resolvedResources = mapOf("constitution" to "C", "bootloader" to "<boot_sentinel_protocol>CHECK</boot_sentinel_protocol>"), gatheredContexts = emptyMap())
        assertTrue(SovereignStrategy.prepareSystemPrompt(ctx, SovereignStrategy.getInitialState()).contains("<boot_sentinel_protocol>"))
        assertTrue(SovereignStrategy.prepareSystemPrompt(ctx, SovereignStrategy.getInitialState()).contains("BOOT SENTINEL"))
        val awake = buildJsonObject { put("phase", "AWAKE"); put("turnCount", 1) }
        assertFalse(SovereignStrategy.prepareSystemPrompt(ctx, awake).contains("<boot_sentinel_protocol>"))
        assertFalse(SovereignStrategy.prepareSystemPrompt(ctx, awake).contains("BOOT SENTINEL"))
    }

    @Test fun `prepareSystemPrompt should include HKG navigation when AWAKE and HKG present`() {
        val awake = buildJsonObject { put("phase", "AWAKE"); put("turnCount", 1) }
        val ctx = AgentTurnContext(agentName = agentName, resolvedResources = mapOf("constitution" to "C", "bootloader" to "B"),
            gatheredContexts = mapOf("HOLON_KNOWLEDGE_GRAPH_INDEX" to "[wrapped index]"))
        val prompt = SovereignStrategy.prepareSystemPrompt(ctx, awake)
        assertTrue(prompt.contains("HKG NAVIGATION")); assertTrue(prompt.contains("CONTEXT_UNCOLLAPSE"))
    }

    @Test fun `prepareSystemPrompt should omit HKG navigation when BOOTING`() {
        val ctx = AgentTurnContext(agentName = agentName, resolvedResources = mapOf("constitution" to "C", "bootloader" to "B"),
            gatheredContexts = mapOf("HOLON_KNOWLEDGE_GRAPH_INDEX" to "[index]"))
        assertFalse(SovereignStrategy.prepareSystemPrompt(ctx, SovereignStrategy.getInitialState()).contains("HKG NAVIGATION"))
    }

    @Test fun `prepareSystemPrompt should place HKG before other gathered contexts`() {
        val awake = buildJsonObject { put("phase", "AWAKE"); put("turnCount", 1) }
        val ctx = AgentTurnContext(agentName = agentName, resolvedResources = mapOf("constitution" to "C", "bootloader" to "B"),
            gatheredContexts = mapOf("AVAILABLE_ACTIONS" to "[actions]", "HOLON_KNOWLEDGE_GRAPH_INDEX" to "[index]", "HOLON_KNOWLEDGE_GRAPH_FILES" to "[files]"))
        val prompt = SovereignStrategy.prepareSystemPrompt(ctx, awake)
        assertTrue(prompt.indexOf("[index]") < prompt.indexOf("[actions]"))
        assertTrue(prompt.indexOf("[files]") < prompt.indexOf("[actions]"))
    }

    @Test fun `prepareSystemPrompt should include multi-agent context before other gathered contexts`() {
        val awake = buildJsonObject { put("phase", "AWAKE"); put("turnCount", 1) }
        val ctx = AgentTurnContext(agentName = agentName, resolvedResources = mapOf("constitution" to "C", "bootloader" to "B"),
            gatheredContexts = mapOf("MULTI_AGENT_CONTEXT" to "multi-info", "AVAILABLE_ACTIONS" to "[actions]"))
        val prompt = SovereignStrategy.prepareSystemPrompt(ctx, awake)
        assertTrue(prompt.indexOf("multi-info") < prompt.indexOf("[actions]"))
    }

    @Test fun `prepareSystemPrompt should include subscribed sessions with PRIVATE and PUBLIC tags`() {
        val ctx = AgentTurnContext(agentName = agentName, resolvedResources = mapOf("constitution" to "C", "bootloader" to "B"),
            gatheredContexts = emptyMap(),
            subscribedSessions = listOf(
                SessionInfo(uuid = privateSessionUUID, handle = "session.priv", name = "Private", isOutput = true),
                SessionInfo(uuid = publicSession1, handle = "session.chat", name = "Chat", isOutput = false)
            ), outputSessionHandle = "session.priv")
        val prompt = SovereignStrategy.prepareSystemPrompt(ctx, SovereignStrategy.getInitialState())
        assertTrue(prompt.contains("SUBSCRIBED SESSIONS")); assertTrue(prompt.contains("[PRIVATE")); assertTrue(prompt.contains("[PUBLIC"))
    }

    @Test fun `prepareSystemPrompt should say candidate consciousness when BOOTING`() {
        val ctx = AgentTurnContext(agentName = agentName, resolvedResources = mapOf("constitution" to "C", "bootloader" to "B"), gatheredContexts = emptyMap())
        assertTrue(SovereignStrategy.prepareSystemPrompt(ctx, SovereignStrategy.getInitialState()).contains("candidate consciousness"))
    }

    @Test fun `prepareSystemPrompt should not say candidate consciousness when AWAKE`() {
        val awake = buildJsonObject { put("phase", "AWAKE"); put("turnCount", 1) }
        val ctx = AgentTurnContext(agentName = agentName, resolvedResources = mapOf("constitution" to "C", "bootloader" to "B"), gatheredContexts = emptyMap())
        val prompt = SovereignStrategy.prepareSystemPrompt(ctx, awake)
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