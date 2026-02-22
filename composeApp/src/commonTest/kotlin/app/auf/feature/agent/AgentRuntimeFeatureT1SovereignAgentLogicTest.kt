package app.auf.feature.agent

import app.auf.core.AppState
import app.auf.core.Identity
import app.auf.core.IdentityUUID
import app.auf.core.generated.ActionRegistry
import app.auf.feature.agent.strategies.SovereignStrategy
import app.auf.fakes.FakePlatformDependencies
import app.auf.fakes.FakeStore
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tier 1 Unit Test for SovereignStrategy infrastructure lifecycle hooks.
 *
 * These tests verify the "Trust or Bootstrap" protocol in
 * [SovereignStrategy.ensureInfrastructure], which replaced the old
 * SovereignHKGResourceLogic.ensureSovereignSessions.
 *
 * Key API differences from the old tests:
 * - Method is per-agent: `ensureInfrastructure(agent, agentState, store)`
 * - Session lookup uses `store.state.value.identityRegistry` instead of
 *   `subscribableSessionNames`.
 * - `privateSessionId` is now `outputSessionId` on AgentInstance.
 * - `knowledgeGraphId` is embedded in `cognitiveState` (handled by testAgent factory).
 */
class AgentRuntimeFeatureT1SovereignAgentLogicTest {

    private val platform = FakePlatformDependencies("test")
    private val fakeStore = FakeStore(AppState(), platform)

    @Test
    fun `ensureInfrastructure should dispatch CREATE if session missing and outputSessionId is null`() {
        fakeStore.dispatchedActions.clear()
        val agent = testAgent("a1", "Sovereign", "kg1", "p", "m", privateSessionId = null)
        val state = AgentRuntimeState(agents = mapOf(uid("a1") to agent))

        // Set up AppState with empty identity registry (no matching session)
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

        // Set up identity registry with a session matching the expected name
        val sessionIdentity = Identity(
            uuid = "s-new",
            localHandle = "p-cognition-sovereign-a1",
            handle = "session.p-cognition-sovereign-a1",
            name = "p-cognition: Sovereign (a1)",
            parentHandle = "session"
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
        // [CRITICAL TEST]: This verifies the "Broken Link" tolerance.
        // ensureInfrastructure returns early when outputSessionId is non-null.
        fakeStore.dispatchedActions.clear()
        val agent = testAgent("a1", "Sovereign", "kg1", "p", "m", privateSessionId = "broken-id-123")
        val state = AgentRuntimeState(
            agents = mapOf(uid("a1") to agent),
            subscribableSessionNames = emptyMap(), // Session is truly missing from the list
            hkgReservedIds = setOf("kg1")
        )
        fakeStore.setState(AppState(featureStates = mapOf("agent" to state)))

        SovereignStrategy.ensureInfrastructure(agent, state, fakeStore)

        // It should NOT try to create or update sessions.
        // (HKG reservation dispatch is allowed since that's separate infrastructure)
        val sessionCreate = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_CREATE }
        val configUpdate = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_UPDATE_CONFIG }
        assertTrue(sessionCreate == null, "Should not create a session when outputSessionId is set")
        assertTrue(configUpdate == null, "Should not update config when outputSessionId is set")
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

        // No session creation or config update should be dispatched
        val sessionCreate = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_CREATE }
        val configUpdate = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_UPDATE_CONFIG }
        assertTrue(sessionCreate == null, "Should not create a session when outputSessionId is already linked")
        assertTrue(configUpdate == null, "Should not update config when outputSessionId is already linked")
    }
}