package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.generated.ActionNames
import app.auf.fakes.FakePlatformDependencies
import app.auf.fakes.FakeStore
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tier 1 Unit Test for SovereignAgentLogic.
 *
 * Mandate (P-TEST-001, T1): To test the pure business logic for Sovereign agent
 * state transitions in complete isolation using a FakeStore.
 */
class AgentRuntimeFeatureT1SovereignAgentLogicTest {

    private val platform = FakePlatformDependencies("test")
    private val fakeStore = FakeStore(AppState(), platform, ActionNames.allActionNames)

    // --- BECOMING SOVEREIGN ---
    @Test
    fun `handleSovereignAssignment should dispatch session CREATE when KG is newly assigned`() {
        // ARRANGE: An agent transitions from Vanilla to Sovereign
        val oldAgent = AgentInstance("a1", "Test Agent", null, "p", "m")
        val newAgent = oldAgent.copy(knowledgeGraphId = "kg1")

        // ACT
        SovereignAgentLogic.handleSovereignAssignment(fakeStore, oldAgent, newAgent)

        // ASSERT
        val dispatchedAction = fakeStore.dispatchedActions.find { it.name == ActionNames.SESSION_CREATE }
        assertNotNull(dispatchedAction, "A session CREATE action should have been dispatched.")
        assertEquals(ActionNames.SESSION_CREATE, dispatchedAction.name)

        val payload = dispatchedAction.payload
        assertNotNull(payload)
        assertEquals("p-cognition: Test Agent (a1)", payload["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `handleSovereignAssignment should dispatch KNOWLEDGEGRAPH_RESERVE_HKG when KG is newly assigned`() {
        // ARRANGE: An agent transitions from Vanilla to Sovereign
        val oldAgent = AgentInstance("a1", "Test Agent", null, "p", "m")
        val newAgent = oldAgent.copy(knowledgeGraphId = "kg1")

        // ACT
        SovereignAgentLogic.handleSovereignAssignment(fakeStore, oldAgent, newAgent)

        // ASSERT
        val dispatchedAction = fakeStore.dispatchedActions.find { it.name == ActionNames.KNOWLEDGEGRAPH_RESERVE_HKG }
        assertNotNull(dispatchedAction, "A KNOWLEDGEGRAPH_RESERVE_HKG action should have been dispatched.")

        val payload = dispatchedAction.payload
        assertNotNull(payload)
        assertEquals("kg1", payload["personaId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `handleSovereignAssignment should do nothing if KG was already assigned`() {
        // ARRANGE: A Sovereign agent's name is updated, but its KG is unchanged.
        val oldAgent = AgentInstance("a1", "Test Agent", "kg1", "p", "m", privateSessionId = "ps1")
        val newAgent = oldAgent.copy(name = "Test Agent Updated")

        // ACT
        SovereignAgentLogic.handleSovereignAssignment(fakeStore, oldAgent, newAgent)

        // ASSERT
        assertTrue(fakeStore.dispatchedActions.isEmpty(), "No actions should be dispatched for a simple name change.")
    }

    @Test
    fun `handleSovereignAssignment should do nothing if agent remains stateless`() {
        // ARRANGE: A Vanilla agent's name is updated.
        val oldAgent = AgentInstance("a1", "Vanilla Agent", null, "p", "m")
        val newAgent = oldAgent.copy(name = "Vanilla Agent Updated")

        // ACT
        SovereignAgentLogic.handleSovereignAssignment(fakeStore, oldAgent, newAgent)

        // ASSERT
        assertTrue(fakeStore.dispatchedActions.isEmpty(), "No actions should be dispatched for a stateless agent update.")
    }

    // --- BECOMING VANILLA (DE-TRANSITION) ---
    @Test
    fun `handleSovereignRevocation should dispatch UPDATE_CONFIG to clean up agent state`() {
        // ARRANGE: A Sovereign agent with multiple subscriptions is de-transitioned.
        val oldAgent = AgentInstance("a1", "Test Agent", "kg1", "p", "m",
            privateSessionId = "ps1",
            subscribedSessionIds = listOf("s1", "s2", "s3")
        )
        val newAgent = oldAgent.copy(knowledgeGraphId = null) // The de-transition event

        // ACT
        SovereignAgentLogic.handleSovereignRevocation(fakeStore, oldAgent, newAgent)

        // ASSERT
        val dispatchedAction = fakeStore.dispatchedActions.find { it.name == ActionNames.AGENT_UPDATE_CONFIG }
        assertNotNull(dispatchedAction, "An UPDATE_CONFIG action should have been dispatched.")

        val payload = dispatchedAction.payload
        assertNotNull(payload)
        assertEquals("a1", payload["agentId"]?.jsonPrimitive?.content)
        // Verify private session is detached
        assertTrue(payload.containsKey("privateSessionId") && payload["privateSessionId"] is JsonNull, "privateSessionId should be explicitly set to null.")
        // Verify subscriptions are truncated
        val updatedSubs = payload["subscribedSessionIds"]?.jsonArray
        assertNotNull(updatedSubs)
        assertEquals(1, updatedSubs.size, "Subscribed sessions should be truncated to one.")
        assertEquals("s1", updatedSubs.first().jsonPrimitive.content)
    }

    @Test
    fun `handleSovereignRevocation should dispatch KNOWLEDGEGRAPH_RELEASE_HKG when KG is unassigned`() {
        // ARRANGE: A Sovereign agent is de-transitioned.
        val oldAgent = AgentInstance("a1", "Test Agent", "kg1", "p", "m", privateSessionId = "ps1")
        val newAgent = oldAgent.copy(knowledgeGraphId = null)

        // ACT
        SovereignAgentLogic.handleSovereignRevocation(fakeStore, oldAgent, newAgent)

        // ASSERT
        val dispatchedAction = fakeStore.dispatchedActions.find { it.name == ActionNames.KNOWLEDGEGRAPH_RELEASE_HKG }
        assertNotNull(dispatchedAction, "A KNOWLEDGEGRAPH_RELEASE_HKG action should have been dispatched.")

        val payload = dispatchedAction.payload
        assertNotNull(payload)
        assertEquals("kg1", payload["personaId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `handleSovereignRevocation should do nothing if agent was already stateless`() {
        // ARRANGE
        val oldAgent = AgentInstance("a1", "Vanilla Agent", null, "p", "m")
        val newAgent = oldAgent.copy(name = "Vanilla Agent Updated")

        // ACT
        SovereignAgentLogic.handleSovereignRevocation(fakeStore, oldAgent, newAgent)

        // ASSERT
        assertTrue(fakeStore.dispatchedActions.isEmpty(), "No actions should be dispatched.")
    }
}