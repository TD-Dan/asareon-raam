package app.auf.feature.agent

import app.auf.core.AppState
import app.auf.core.Identity
import app.auf.core.IdentityUUID
import app.auf.core.generated.ActionRegistry
import app.auf.feature.agent.strategies.SovereignStrategy
import app.auf.fakes.FakePlatformDependencies
import app.auf.fakes.FakeStore
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tier 1 Unit Test for SovereignStrategy lifecycle hooks and helpers.
 *
 * Tests cover:
 * - ensureInfrastructure (Trust or Bootstrap protocol)
 * - onAgentConfigChanged (KG assignment/revocation)
 * - getKnowledgeGraphId extraction
 * - needsAdditionalContext
 * - validateConfig (passthrough)
 */
class AgentRuntimeFeatureT1SovereignAgentLogicTest {

    private val platform = FakePlatformDependencies("test")
    private val fakeStore = FakeStore(AppState(), platform)

    // =========================================================================
    // ensureInfrastructure (Trust or Bootstrap)
    // =========================================================================

    @Test
    fun `ensureInfrastructure should dispatch CREATE if session missing and outputSessionId is null`() {
        fakeStore.dispatchedActions.clear()
        val agent = testAgent("a1", "Sovereign", "kg1", "p", "m", privateSessionId = null)
        val state = AgentRuntimeState(agents = mapOf(uid("a1") to agent))
        fakeStore.setState(AppState(featureStates = mapOf("agent" to state)))

        SovereignStrategy.ensureInfrastructure(agent, state, fakeStore)

        val sessionAction = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_CREATE }
        assertNotNull(sessionAction)
        assertEquals("p-cognition: Sovereign (a1)", sessionAction.payload?.get("name")?.jsonPrimitive?.content)
    }

    @Test
    fun `ensureInfrastructure should dispatch UPDATE if session exists but unlinked`() {
        fakeStore.dispatchedActions.clear()
        val agent = testAgent("a1", "Sovereign", "kg1", "p", "m", privateSessionId = null)
        val state = AgentRuntimeState(agents = mapOf(uid("a1") to agent))

        val sessionIdentity = Identity(
            uuid = "s-new", localHandle = "p-cognition-sovereign-a1",
            handle = "session.p-cognition-sovereign-a1",
            name = "p-cognition: Sovereign (a1)", parentHandle = "session"
        )
        fakeStore.setState(AppState(
            featureStates = mapOf("agent" to state),
            identityRegistry = mapOf(sessionIdentity.handle to sessionIdentity)
        ))

        SovereignStrategy.ensureInfrastructure(agent, state, fakeStore)

        val updateAction = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_UPDATE_CONFIG }
        assertNotNull(updateAction)
        assertEquals("a1", updateAction.payload?.get("agentId")?.jsonPrimitive?.content)
        assertEquals("s-new", updateAction.payload?.get("outputSessionId")?.jsonPrimitive?.content)
    }

    @Test
    fun `ensureInfrastructure should DO NOTHING if outputSessionId is set but session is missing`() {
        fakeStore.dispatchedActions.clear()
        val agent = testAgent("a1", "Sovereign", "kg1", "p", "m", privateSessionId = "broken-id-123")
        val state = AgentRuntimeState(
            agents = mapOf(uid("a1") to agent),
            subscribableSessionNames = emptyMap(),
            hkgReservedIds = setOf("kg1")
        )
        fakeStore.setState(AppState(featureStates = mapOf("agent" to state)))

        SovereignStrategy.ensureInfrastructure(agent, state, fakeStore)

        val sessionCreate = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_CREATE }
        val configUpdate = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_UPDATE_CONFIG }
        assertNull(sessionCreate)
        assertNull(configUpdate)
    }

    @Test
    fun `ensureInfrastructure should DO NOTHING if outputSessionId is set and valid`() {
        fakeStore.dispatchedActions.clear()
        val agent = testAgent("a1", "Sovereign", "kg1", "p", "m", privateSessionId = "s1")
        val state = AgentRuntimeState(
            agents = mapOf(uid("a1") to agent),
            subscribableSessionNames = mapOf(uid("s1") to "p-cognition: Sovereign (a1)"),
            hkgReservedIds = setOf("kg1")
        )
        fakeStore.setState(AppState(featureStates = mapOf("agent" to state)))

        SovereignStrategy.ensureInfrastructure(agent, state, fakeStore)

        assertNull(fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_CREATE })
        assertNull(fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_UPDATE_CONFIG })
    }

    @Test
    fun `ensureInfrastructure should dispatch HKG reservation if not already reserved`() {
        fakeStore.dispatchedActions.clear()
        val agent = testAgent("a1", "Sovereign", "kg1", "p", "m", privateSessionId = "s1")
        val state = AgentRuntimeState(
            agents = mapOf(uid("a1") to agent),
            hkgReservedIds = emptySet() // NOT reserved
        )
        fakeStore.setState(AppState(featureStates = mapOf("agent" to state)))

        SovereignStrategy.ensureInfrastructure(agent, state, fakeStore)

        val reserveAction = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.KNOWLEDGEGRAPH_RESERVE_HKG }
        assertNotNull(reserveAction)
        assertEquals("kg1", reserveAction.payload?.get("personaId")?.jsonPrimitive?.content)
    }

    @Test
    fun `ensureInfrastructure should skip HKG reservation if already reserved`() {
        fakeStore.dispatchedActions.clear()
        val agent = testAgent("a1", "Sovereign", "kg1", "p", "m", privateSessionId = "s1")
        val state = AgentRuntimeState(
            agents = mapOf(uid("a1") to agent),
            hkgReservedIds = setOf("kg1") // Already reserved
        )
        fakeStore.setState(AppState(featureStates = mapOf("agent" to state)))

        SovereignStrategy.ensureInfrastructure(agent, state, fakeStore)

        val reserveAction = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.KNOWLEDGEGRAPH_RESERVE_HKG }
        assertNull(reserveAction)
    }

    @Test
    fun `ensureInfrastructure should return early if agent has no knowledgeGraphId`() {
        fakeStore.dispatchedActions.clear()
        // Agent without KG — testAgent with null knowledgeGraphId produces JsonNull cognitiveState
        val agent = testAgent("a1", "Vanilla", null, "p", "m")
        val state = AgentRuntimeState(agents = mapOf(uid("a1") to agent))
        fakeStore.setState(AppState(featureStates = mapOf("agent" to state)))

        SovereignStrategy.ensureInfrastructure(agent, state, fakeStore)

        assertTrue(fakeStore.dispatchedActions.isEmpty())
    }

    // =========================================================================
    // onAgentConfigChanged
    // =========================================================================

    @Test
    fun `onAgentConfigChanged should dispatch RESERVE_HKG when KG is assigned`() {
        fakeStore.dispatchedActions.clear()
        val old = testAgent("a1", "Agent", null, "p", "m") // No KG
        val new = testAgent("a1", "Agent", "kg-new", "p", "m", cognitiveStrategyId = "sovereign_v1")

        SovereignStrategy.onAgentConfigChanged(old, new, fakeStore)

        val reserveAction = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.KNOWLEDGEGRAPH_RESERVE_HKG }
        assertNotNull(reserveAction)
        assertEquals("kg-new", reserveAction.payload?.get("personaId")?.jsonPrimitive?.content)
    }

    @Test
    fun `onAgentConfigChanged should dispatch RELEASE_HKG and truncate when KG is revoked`() {
        fakeStore.dispatchedActions.clear()
        val old = testAgent("a1", "Agent", "kg-old", "p", "m",
            subscribedSessionIds = listOf("s1", "s2", "s3"),
            cognitiveStrategyId = "sovereign_v1"
        )
        val new = testAgent("a1", "Agent", null, "p", "m") // KG revoked

        SovereignStrategy.onAgentConfigChanged(old, new, fakeStore)

        // Should release the old KG
        val releaseAction = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.KNOWLEDGEGRAPH_RELEASE_HKG }
        assertNotNull(releaseAction)
        assertEquals("kg-old", releaseAction.payload?.get("personaId")?.jsonPrimitive?.content)

        // Should dispatch UPDATE_CONFIG with truncated subscriptions and nulled outputSessionId
        val updateAction = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_UPDATE_CONFIG }
        assertNotNull(updateAction)
        assertEquals("a1", updateAction.payload?.get("agentId")?.jsonPrimitive?.content)
        assertTrue(updateAction.payload?.get("outputSessionId") is JsonNull)
    }

    @Test
    fun `onAgentConfigChanged should do nothing when KG stays the same`() {
        fakeStore.dispatchedActions.clear()
        val old = testAgent("a1", "Agent", "kg1", "p", "m", cognitiveStrategyId = "sovereign_v1")
        val new = testAgent("a1", "Agent", "kg1", "p", "m", cognitiveStrategyId = "sovereign_v1")

        SovereignStrategy.onAgentConfigChanged(old, new, fakeStore)

        // No reserve or release actions
        assertNull(fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.KNOWLEDGEGRAPH_RESERVE_HKG })
        assertNull(fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.KNOWLEDGEGRAPH_RELEASE_HKG })
    }

    @Test
    fun `onAgentConfigChanged should do nothing when both old and new have no KG`() {
        fakeStore.dispatchedActions.clear()
        val old = testAgent("a1", "Agent", null, "p", "m")
        val new = testAgent("a1", "Agent", null, "p", "m")

        SovereignStrategy.onAgentConfigChanged(old, new, fakeStore)

        assertTrue(fakeStore.dispatchedActions.isEmpty())
    }

    // =========================================================================
    // getKnowledgeGraphId
    // =========================================================================

    @Test
    fun `getKnowledgeGraphId should extract ID from cognitiveState`() {
        val agent = testAgent("a1", "Sovereign", "my-kg", "p", "m", cognitiveStrategyId = "sovereign_v1")
        assertEquals("my-kg", SovereignStrategy.getKnowledgeGraphId(agent))
    }

    @Test
    fun `getKnowledgeGraphId should return null for vanilla agent`() {
        val agent = testAgent("a1", "Vanilla", null, "p", "m")
        assertNull(SovereignStrategy.getKnowledgeGraphId(agent))
    }

    @Test
    fun `getKnowledgeGraphId should return null when cognitiveState has null KG`() {
        val agent = testAgent("a1", "Test", null, "p", "m", cognitiveStrategyId = "sovereign_v1")
            .copy(cognitiveState = buildJsonObject {
                put("phase", "BOOTING")
                put("knowledgeGraphId", JsonNull)
            })
        assertNull(SovereignStrategy.getKnowledgeGraphId(agent))
    }

    // =========================================================================
    // needsAdditionalContext
    // =========================================================================

    @Test
    fun `needsAdditionalContext should return true for sovereign agent with KG`() {
        val agent = testAgent("a1", "Sovereign", "kg1", "p", "m", cognitiveStrategyId = "sovereign_v1")
        assertTrue(SovereignStrategy.needsAdditionalContext(agent))
    }

    @Test
    fun `needsAdditionalContext should return false for agent without KG`() {
        val agent = testAgent("a1", "Vanilla", null, "p", "m")
        assertFalse(SovereignStrategy.needsAdditionalContext(agent))
    }

    // =========================================================================
    // validateConfig (passthrough)
    // =========================================================================

    @Test
    fun `validateConfig should return agent unchanged (Sovereign permits out-of-band outputSessionId)`() {
        val agent = testAgent("a1", "Sovereign", "kg1", "p", "m",
            subscribedSessionIds = listOf("s1"),
            privateSessionId = "s-private" // NOT in subscribedSessionIds — intentional
        )

        val validated = SovereignStrategy.validateConfig(agent)

        // Should be returned as-is: Sovereign permits out-of-band outputSessionId
        assertEquals(agent, validated)
        assertEquals(uid("s-private"), validated.outputSessionId)
    }
}