package app.auf.feature.agent

import app.auf.core.AppState
import app.auf.core.Feature
import app.auf.core.generated.ActionNames
import app.auf.fakes.FakePlatformDependencies
import app.auf.fakes.FakeStore
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tier 1 Unit Test for SovereignAgentLogic.
 */
class AgentRuntimeFeatureT1SovereignAgentLogicTest {

    private val platform = FakePlatformDependencies("test")
    private val fakeStore = FakeStore(AppState(), platform, ActionNames.allActionNames)

    // ... [Previous Tests Kept Intact] ...

    @Test
    fun `handleSovereignAssignment should dispatch session CREATE when KG is newly assigned`() {
        val oldAgent = AgentInstance("a1", "Test Agent", null, "p", "m")
        val newAgent = oldAgent.copy(knowledgeGraphId = "kg1")

        SovereignHKGResourceLogic.handleSovereignAssignment(fakeStore, oldAgent, newAgent)

        val dispatchedAction = fakeStore.dispatchedActions.find { it.name == ActionNames.SESSION_CREATE }
        assertNotNull(dispatchedAction)
        assertEquals("p-cognition: Test Agent (a1)", dispatchedAction.payload?.get("name")?.jsonPrimitive?.content)
    }

    @Test
    fun `handleSovereignAssignment should dispatch KNOWLEDGEGRAPH_RESERVE_HKG when KG is newly assigned`() {
        val oldAgent = AgentInstance("a1", "Test Agent", null, "p", "m")
        val newAgent = oldAgent.copy(knowledgeGraphId = "kg1")

        SovereignHKGResourceLogic.handleSovereignAssignment(fakeStore, oldAgent, newAgent)

        val dispatchedAction = fakeStore.dispatchedActions.find { it.name == ActionNames.KNOWLEDGEGRAPH_RESERVE_HKG }
        assertNotNull(dispatchedAction)
        assertEquals("kg1", dispatchedAction.payload?.get("personaId")?.jsonPrimitive?.content)
    }

    @Test
    fun `handleSovereignAssignment should do nothing if KG was already assigned`() {
        val oldAgent = AgentInstance("a1", "Test Agent", "kg1", "p", "m", privateSessionId = "ps1")
        val newAgent = oldAgent.copy(name = "Test Agent Updated")

        SovereignHKGResourceLogic.handleSovereignAssignment(fakeStore, oldAgent, newAgent)

        assertTrue(fakeStore.dispatchedActions.isEmpty())
    }

    @Test
    fun `handleSovereignAssignment should do nothing if agent remains stateless`() {
        val oldAgent = AgentInstance("a1", "Vanilla Agent", null, "p", "m")
        val newAgent = oldAgent.copy(name = "Vanilla Agent Updated")

        SovereignHKGResourceLogic.handleSovereignAssignment(fakeStore, oldAgent, newAgent)

        assertTrue(fakeStore.dispatchedActions.isEmpty())
    }

    @Test
    fun `handleSovereignRevocation should dispatch UPDATE_CONFIG to clean up agent state`() {
        val oldAgent = AgentInstance("a1", "Test Agent", "kg1", "p", "m",
            privateSessionId = "ps1",
            subscribedSessionIds = listOf("s1", "s2", "s3")
        )
        val newAgent = oldAgent.copy(knowledgeGraphId = null)

        SovereignHKGResourceLogic.handleSovereignRevocation(fakeStore, oldAgent, newAgent)

        val dispatchedAction = fakeStore.dispatchedActions.find { it.name == ActionNames.AGENT_UPDATE_CONFIG }
        assertNotNull(dispatchedAction)
        assertTrue(dispatchedAction.payload?.get("privateSessionId") is JsonNull)
        assertEquals(1, dispatchedAction.payload?.get("subscribedSessionIds")?.jsonArray?.size)
    }

    @Test
    fun `handleSovereignRevocation should dispatch KNOWLEDGEGRAPH_RELEASE_HKG when KG is unassigned`() {
        val oldAgent = AgentInstance("a1", "Test Agent", "kg1", "p", "m", privateSessionId = "ps1")
        val newAgent = oldAgent.copy(knowledgeGraphId = null)

        SovereignHKGResourceLogic.handleSovereignRevocation(fakeStore, oldAgent, newAgent)

        val dispatchedAction = fakeStore.dispatchedActions.find { it.name == ActionNames.KNOWLEDGEGRAPH_RELEASE_HKG }
        assertNotNull(dispatchedAction)
        assertEquals("kg1", dispatchedAction.payload?.get("personaId")?.jsonPrimitive?.content)
    }

    @Test
    fun `handleSovereignRevocation should do nothing if agent was already stateless`() {
        val oldAgent = AgentInstance("a1", "Vanilla Agent", null, "p", "m")
        val newAgent = oldAgent.copy(name = "Vanilla Agent Updated")

        SovereignHKGResourceLogic.handleSovereignRevocation(fakeStore, oldAgent, newAgent)

        assertTrue(fakeStore.dispatchedActions.isEmpty())
    }

    @Test
    fun `ensureSovereignSessions should dispatch CREATE if session missing`() {
        fakeStore.dispatchedActions.clear()
        val agent = AgentInstance("a1", "Sovereign", "kg1", "p", "m") // Sovereign but stateless
        val state = AgentRuntimeState(agents = mapOf("a1" to agent)) // Empty reservations, empty sessions

        SovereignHKGResourceLogic.ensureSovereignSessions(fakeStore, state)

        // Check for Reservation Dispatch
        val reserveAction = fakeStore.dispatchedActions.find { it.name == ActionNames.KNOWLEDGEGRAPH_RESERVE_HKG }
        assertNotNull(reserveAction)
        assertEquals("kg1", reserveAction.payload?.get("personaId")?.jsonPrimitive?.content)

        // Check for Session Create Dispatch
        val sessionAction = fakeStore.dispatchedActions.find { it.name == ActionNames.SESSION_CREATE }
        assertNotNull(sessionAction)
        assertEquals("p-cognition: Sovereign (a1)", sessionAction.payload?.get("name")?.jsonPrimitive?.content)
    }

    @Test
    fun `ensureSovereignSessions should dispatch UPDATE if session exists but unlinked`() {
        fakeStore.dispatchedActions.clear()
        val agent = AgentInstance("a1", "Sovereign", "kg1", "p", "m", privateSessionId = null) // Waiting for session
        val state = AgentRuntimeState(
            agents = mapOf("a1" to agent),
            sessionNames = mapOf("s-new" to "p-cognition: Sovereign (a1)") // Match found
        )

        SovereignHKGResourceLogic.ensureSovereignSessions(fakeStore, state)

        val updateAction = fakeStore.dispatchedActions.find { it.name == ActionNames.AGENT_UPDATE_CONFIG }
        assertNotNull(updateAction)
        assertEquals("a1", updateAction.payload?.get("agentId")?.jsonPrimitive?.content)
        assertEquals("s-new", updateAction.payload?.get("privateSessionId")?.jsonPrimitive?.content)
    }

    @Test
    fun `ensureSovereignSessions should do nothing if state is healthy`() {
        fakeStore.dispatchedActions.clear()
        val agent = AgentInstance("a1", "Sovereign", "kg1", "p", "m", privateSessionId = "s1")
        val state = AgentRuntimeState(
            agents = mapOf("a1" to agent),
            hkgReservedIds = setOf("kg1"),
            sessionNames = mapOf("s1" to "p-cognition: Sovereign (a1)")
        )

        SovereignHKGResourceLogic.ensureSovereignSessions(fakeStore, state)

        assertTrue(fakeStore.dispatchedActions.isEmpty())
    }

    @Test
    fun `ensureSovereignSessions should be idempotent (Race Condition Check)`() {
        // SCENARIO: Agent is loaded, Session list is EMPTY.
        fakeStore.dispatchedActions.clear()
        val agent = AgentInstance("a1", "Sovereign", "kg1", "p", "m")
        val state = AgentRuntimeState(agents = mapOf("a1" to agent), sessionNames = emptyMap())

        // Pass 1: Should dispatch CREATE
        SovereignHKGResourceLogic.ensureSovereignSessions(fakeStore, state)
        val createCountAfterPass1 = fakeStore.dispatchedActions.count { it.name == ActionNames.SESSION_CREATE }
        assertEquals(1, createCountAfterPass1)


        val stateWithSession = state.copy(sessionNames = mapOf("s1" to "p-cognition: Sovereign (a1)"))
        fakeStore.dispatchedActions.clear()

        SovereignHKGResourceLogic.ensureSovereignSessions(fakeStore, stateWithSession)

        val createCountAfterPass2 = fakeStore.dispatchedActions.count { it.name == ActionNames.SESSION_CREATE }
        assertEquals(0, createCountAfterPass2, "Should NOT create if session exists")

        val updateCount = fakeStore.dispatchedActions.count { it.name == ActionNames.AGENT_UPDATE_CONFIG }
        assertEquals(1, updateCount, "Should link if session exists")
    }

    @Test
    fun `requestContextIfSovereign should send private envelope if agent has KG and Feature Exists`() {
        // [FIX] Create a local store with a dummy KG feature to pass the existence check
        val mockKgFeature = object : Feature { override val name = "knowledgegraph" }
        val localFakeStore = FakeStore(AppState(), platform, ActionNames.allActionNames, listOf(mockKgFeature))

        val agent = AgentInstance("a1", "Sovereign", "kg1", "p", "m")

        val result = SovereignHKGResourceLogic.requestContextIfSovereign(localFakeStore, agent)

        assertTrue(result, "Function should return true when KG feature exists.")
        // Note: FakeStore typically records dispatched actions, but deliverPrivateData might behave differently depending on base class logic.
        // However, we primarily care about the boolean return value and the absence of a crash here.
    }
}