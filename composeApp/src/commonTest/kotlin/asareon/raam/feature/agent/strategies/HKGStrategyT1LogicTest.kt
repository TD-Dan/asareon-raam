package asareon.raam.feature.agent.strategies

import asareon.raam.core.*
import asareon.raam.fakes.FakePlatformDependencies
import asareon.raam.fakes.FakeStore
import asareon.raam.feature.agent.*
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Tier 1 Unit Tests for HKGStrategy.
 *
 * HKGStrategy is a reference strategy for testing HKG integration in isolation
 * (Phase C of the Sovereign Stabilization design). It combines Vanilla-style
 * session awareness with HKG context delivery via the two-partition INDEX + FILES view.
 *
 * ## Key Behavioral Contracts
 *
 * 1. **validateConfig**: outputSessionId ∈ subscribedSessionIds (same as Vanilla).
 *    No private session — unlike PrivateSession/Sovereign.
 *
 * 2. **buildPrompt**: places INDEX and FILES gathered partitions,
 *    HKG navigation instructions, session awareness, multi-agent context.
 *    Excludes HKG keys from the generic CONTEXT section.
 *
 * 3. **requestAdditionalContext / needsAdditionalContext**: dispatches
 *    KNOWLEDGEGRAPH_REQUEST_CONTEXT when knowledgeGraphId is set.
 *
 * 4. **postProcessResponse**: always PROCEED, increments turnCount.
 *
 * 5. **getConfigFields**: declares knowledgeGraphId (KG selector) and
 *    outputSessionId (session selector).
 */
class HKGStrategyT1LogicTest {

    // =========================================================================
    // Test data constants
    // =========================================================================

    private val agentId = "a0000001-0000-0000-0000-000000000001"
    private val agentName = "MapBot"
    private val agentHandle = "agent.mapbot"

    private val session1 = "s0000001-0000-0000-0000-000000000001"
    private val session2 = "s0000002-0000-0000-0000-000000000002"

    private val kgId = "meridian-20260312T120000Z"

    private lateinit var fakePlatform: FakePlatformDependencies
    private lateinit var fakeStore: FakeStore

    @BeforeTest
    fun setUp() {
        fakePlatform = FakePlatformDependencies("test")
        fakeStore = FakeStore(AppState(), fakePlatform)

        CognitiveStrategyRegistry.clearForTesting()
        CognitiveStrategyRegistry.register(HKGStrategy)
    }

    @AfterTest
    fun tearDown() {
        CognitiveStrategyRegistry.clearForTesting()
    }

    // =========================================================================
    // Helper: build agents with the HKG strategy
    // =========================================================================

    private fun hkgAgent(
        subscribedSessionIds: List<String> = listOf(session1),
        outputSessionId: String? = session1,
        knowledgeGraphId: String? = kgId,
        resources: Map<String, String> = emptyMap()
    ): AgentInstance = AgentInstance(
        identity = Identity(
            uuid = agentId,
            localHandle = "mapbot",
            handle = agentHandle,
            name = agentName,
            parentHandle = "agent"
        ),
        modelProvider = "anthropic",
        modelName = "claude-haiku-4-5-20251001",
        subscribedSessionIds = subscribedSessionIds.map { IdentityUUID(it) },
        outputSessionId = outputSessionId?.let { IdentityUUID(it) },
        cognitiveStrategyId = HKGStrategy.identityHandle,
        strategyConfig = buildJsonObject {
            if (knowledgeGraphId != null) put("knowledgeGraphId", knowledgeGraphId)
        },
        resources = resources.mapKeys { it.key }.mapValues { IdentityUUID(it.value) }
    )

    // =========================================================================
    // Identity & Registration
    // =========================================================================

    @Test
    fun `identityHandle should be in agent strategy namespace`() {
        assertTrue(HKGStrategy.identityHandle.handle.startsWith("agent.strategy."))
        assertEquals("agent.strategy.hkg", HKGStrategy.identityHandle.handle)
    }

    @Test
    fun `strategy should be a singleton object`() {
        assertSame(HKGStrategy, HKGStrategy)
    }

    @Test
    fun `hasAutoManagedOutputSession should be false`() {
        assertFalse(HKGStrategy.hasAutoManagedOutputSession,
            "HKG strategy uses operator-selected output session, not auto-managed")
    }

    // =========================================================================
    // getInitialState
    // =========================================================================

    @Test
    fun `getInitialState should return JsonObject with turnCount 0`() {
        val state = HKGStrategy.getInitialState()
        assertTrue(state is JsonObject)
        assertEquals(0, (state as JsonObject)["turnCount"]?.jsonPrimitive?.intOrNull)
    }

    // =========================================================================
    // getResourceSlots
    // =========================================================================

    @Test
    fun `getResourceSlots should declare system_instruction slot`() {
        val slots = HKGStrategy.getResourceSlots()
        assertEquals(1, slots.size)
        assertEquals("system_instruction", slots[0].slotId)
        assertEquals(AgentResourceType.SYSTEM_INSTRUCTION, slots[0].type)
        assertTrue(slots[0].isRequired)
    }

    // =========================================================================
    // getConfigFields
    // =========================================================================

    @Test
    fun `getConfigFields should declare knowledgeGraphId and outputSessionId`() {
        val fields = HKGStrategy.getConfigFields()
        assertEquals(2, fields.size)

        val kgField = fields.find { it.key == "knowledgeGraphId" }
        assertNotNull(kgField)
        assertEquals(StrategyConfigFieldType.KNOWLEDGE_GRAPH, kgField.type)

        val outputField = fields.find { it.key == "outputSessionId" }
        assertNotNull(outputField)
        assertEquals(StrategyConfigFieldType.OUTPUT_SESSION, outputField.type)
    }

    // =========================================================================
    // getBuiltInResources
    // =========================================================================

    @Test
    fun `getBuiltInResources should return default system instruction with unique ID`() {
        val resources = HKGStrategy.getBuiltInResources()
        assertEquals(1, resources.size)

        val resource = resources[0]
        assertEquals(AgentResourceType.SYSTEM_INSTRUCTION, resource.type)
        assertTrue(resource.isBuiltIn)
        assertTrue(resource.id.startsWith("res-hkg-"))
        assertTrue(resource.content.contains("Knowledge Graph"))
    }

    // =========================================================================
    // getValidNvramKeys
    // =========================================================================

    @Test
    fun `getValidNvramKeys should return set containing turnCount`() {
        val keys = HKGStrategy.getValidNvramKeys()
        assertNotNull(keys)
        assertTrue(keys.contains("turnCount"))
    }

    // =========================================================================
    // validateConfig — same invariant as Vanilla
    // =========================================================================

    @Test
    fun `validateConfig should correct outputSessionId when not in subscribedSessionIds`() {
        val agent = hkgAgent(
            subscribedSessionIds = listOf(session1, session2),
            outputSessionId = "s-nonexistent"
        )
        val validated = HKGStrategy.validateConfig(agent)
        assertEquals(IdentityUUID(session1), validated.outputSessionId)
    }

    @Test
    fun `validateConfig should auto-assign outputSessionId when null but subscriptions exist`() {
        val agent = hkgAgent(
            subscribedSessionIds = listOf(session1, session2),
            outputSessionId = null
        )
        val validated = HKGStrategy.validateConfig(agent)
        assertEquals(IdentityUUID(session1), validated.outputSessionId)
    }

    @Test
    fun `validateConfig should leave outputSessionId null when no subscriptions`() {
        val agent = hkgAgent(subscribedSessionIds = emptyList(), outputSessionId = null)
        val validated = HKGStrategy.validateConfig(agent)
        assertNull(validated.outputSessionId)
    }

    @Test
    fun `validateConfig should keep valid outputSessionId`() {
        val agent = hkgAgent(
            subscribedSessionIds = listOf(session1, session2),
            outputSessionId = session2
        )
        val validated = HKGStrategy.validateConfig(agent)
        assertEquals(IdentityUUID(session2), validated.outputSessionId)
    }

    // =========================================================================
    // buildPrompt
    // =========================================================================

    @Test
    fun `buildPrompt should include agent name and HKG awareness`() {
        val context = AgentTurnContext(
            agentName = agentName,
            resolvedResources = emptyMap(),
            gatheredContextKeys = emptySet()
        )
        val prompt = HKGStrategy.buildPrompt(context, HKGStrategy.getInitialState()).renderForTest()

        assertTrue(prompt.contains("You are $agentName."))
        assertTrue(prompt.contains("Holon Knowledge Graph"))
    }

    @Test
    fun `buildPrompt should include system instructions when provided`() {
        val context = AgentTurnContext(
            agentName = agentName,
            resolvedResources = mapOf("system_instruction" to "Be a great mapper."),
            gatheredContextKeys = emptySet()
        )
        val prompt = HKGStrategy.buildPrompt(context, HKGStrategy.getInitialState()).renderForTest()

        assertTrue(prompt.contains("SYSTEM INSTRUCTIONS"))
        assertTrue(prompt.contains("Be a great mapper."))
    }

    @Test
    fun `buildPrompt should include INDEX when present in gathered contexts`() {
        val context = AgentTurnContext(
            agentName = agentName,
            resolvedResources = emptyMap(),
            gatheredContextKeys = setOf("HOLON_KNOWLEDGE_GRAPH_INDEX")
        )
        val prompt = HKGStrategy.buildPrompt(context, HKGStrategy.getInitialState()).renderForTest()

        assertTrue(prompt.contains("[GATHERED:HOLON_KNOWLEDGE_GRAPH_INDEX]"))
        assertTrue(prompt.contains("HKG NAVIGATION"))
    }

    @Test
    fun `buildPrompt should include FILES when present in gathered contexts`() {
        val context = AgentTurnContext(
            agentName = agentName,
            resolvedResources = emptyMap(),
            gatheredContextKeys = setOf("HOLON_KNOWLEDGE_GRAPH_INDEX", "HOLON_KNOWLEDGE_GRAPH_FILES")
        )
        val prompt = HKGStrategy.buildPrompt(context, HKGStrategy.getInitialState()).renderForTest()

        assertTrue(prompt.contains("[GATHERED:HOLON_KNOWLEDGE_GRAPH_FILES]"))
    }

    @Test
    fun `buildPrompt should not show HKG navigation when no INDEX`() {
        val context = AgentTurnContext(
            agentName = agentName,
            resolvedResources = emptyMap(),
            gatheredContextKeys = emptySet()
        )
        val prompt = HKGStrategy.buildPrompt(context, HKGStrategy.getInitialState()).renderForTest()

        assertFalse(prompt.contains("HKG NAVIGATION"))
    }

    @Test
    fun `buildPrompt should include session subscription awareness`() {
        val context = AgentTurnContext(
            agentName = agentName,
            resolvedResources = emptyMap(),
            gatheredContextKeys = emptySet(),
            subscribedSessions = listOf(
                SessionInfo(uuid = session1, handle = "session.chat", name = "Chat", isOutput = true)
            ),
            outputSessionHandle = "session.chat"
        )
        val prompt = HKGStrategy.buildPrompt(context, HKGStrategy.getInitialState()).renderForTest()

        assertTrue(prompt.contains("SUBSCRIBED SESSIONS"))
        assertTrue(prompt.contains("Chat (session.chat)"))
        assertTrue(prompt.contains("PRIMARY"))
    }

    @Test
    fun `buildPrompt includes navigation instructions with expand and collapse examples`() {
        val context = AgentTurnContext(
            agentName = agentName,
            resolvedResources = emptyMap(),
            gatheredContextKeys = setOf("HOLON_KNOWLEDGE_GRAPH_INDEX")
        )
        val prompt = HKGStrategy.buildPrompt(context, HKGStrategy.getInitialState()).renderForTest()

        assertTrue(prompt.contains("CONTEXT_UNCOLLAPSE"))
        assertTrue(prompt.contains("CONTEXT_COLLAPSE"))
        assertTrue(prompt.contains("must expand a holon file before writing"))
    }

    // =========================================================================
    // postProcessResponse
    // =========================================================================

    @Test
    fun `postProcessResponse should always return PROCEED`() {
        val initial = HKGStrategy.getInitialState()
        val result = HKGStrategy.postProcessResponse("Any response.", initial)
        assertEquals(SentinelAction.PROCEED, result.action)
    }

    @Test
    fun `postProcessResponse should increment turnCount`() {
        val initial = HKGStrategy.getInitialState()
        val result = HKGStrategy.postProcessResponse("Response.", initial)
        assertEquals(1, (result.newState as JsonObject)["turnCount"]?.jsonPrimitive?.intOrNull)

        val result2 = HKGStrategy.postProcessResponse("Response 2.", result.newState)
        assertEquals(2, (result2.newState as JsonObject)["turnCount"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun `postProcessResponse should not set displayHint`() {
        val result = HKGStrategy.postProcessResponse("Response.", HKGStrategy.getInitialState())
        assertNull(result.displayHint)
    }

    // =========================================================================
    // requestAdditionalContext / needsAdditionalContext
    // =========================================================================

    @Test
    fun `needsAdditionalContext should return true when knowledgeGraphId is set`() {
        val agent = hkgAgent(knowledgeGraphId = kgId)
        assertTrue(HKGStrategy.needsAdditionalContext(agent))
    }

    @Test
    fun `needsAdditionalContext should return false when knowledgeGraphId is null`() {
        val agent = hkgAgent(knowledgeGraphId = null)
        assertFalse(HKGStrategy.needsAdditionalContext(agent))
    }

    @Test
    fun `requestAdditionalContext should return false when no knowledgeGraphId`() {
        val agent = hkgAgent(knowledgeGraphId = null)
        assertFalse(HKGStrategy.requestAdditionalContext(agent, fakeStore))
    }

    @Test
    fun `requestAdditionalContext should return false when KG feature not registered`() {
        // FakeStore has no features registered by default — simulates missing KG feature
        val agent = hkgAgent(knowledgeGraphId = kgId)
        val result = HKGStrategy.requestAdditionalContext(agent, fakeStore)
        assertFalse(result)
    }

    // =========================================================================
    // getKnowledgeGraphId helper
    // =========================================================================

    @Test
    fun `getKnowledgeGraphId extracts from strategyConfig`() {
        val agent = hkgAgent(knowledgeGraphId = kgId)
        assertEquals(kgId, HKGStrategy.getKnowledgeGraphId(agent))
    }

    @Test
    fun `getKnowledgeGraphId returns null when not set`() {
        val agent = hkgAgent(knowledgeGraphId = null)
        assertNull(HKGStrategy.getKnowledgeGraphId(agent))
    }

    // =========================================================================
    // Lifecycle hooks — default behavior
    // =========================================================================

    @Test
    fun `onAgentRegistered and onAgentConfigChanged should not crash`() {
        val agent = hkgAgent()
        // These have default no-op implementations; just verify no exception
        HKGStrategy.onAgentRegistered(agent, fakeStore)
        HKGStrategy.onAgentConfigChanged(agent, agent, fakeStore)
    }
}