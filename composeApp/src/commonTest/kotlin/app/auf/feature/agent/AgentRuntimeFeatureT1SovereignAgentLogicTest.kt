package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.generated.ActionNames
import app.auf.fakes.FakePlatformDependencies
import app.auf.fakes.FakeStore
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tier 1 Unit Test for SovereignAgentLogic.
 *
 * Mandate (P-TEST-001, T1): To test the pure business logic for Sovereign agent
 * state transitions in complete isolation using a FakeStore.
 */
class AgentRuntimeFeatureT1SovereignAgentLogicTest {

    private val platform = FakePlatformDependencies("test")
    private val fakeStore = FakeStore(AppState(), platform, ActionNames.allActionNames)

    @Test
    fun `handleSovereignAssignment should dispatch session CREATE when KG is newly assigned`() {
        // ARRANGE: An agent transitions from Vanilla to Sovereign
        val oldAgent = AgentInstance("a1", "Test Agent", null, "p", "m")
        val newAgent = oldAgent.copy(knowledgeGraphId = "kg1")

        // ACT
        SovereignAgentLogic.handleSovereignAssignment(fakeStore, oldAgent, newAgent)

        // ASSERT
        val dispatchedAction = fakeStore.dispatchedActions.firstOrNull()
        assertNotNull(dispatchedAction, "An action should have been dispatched.")
        assertEquals(ActionNames.SESSION_CREATE, dispatchedAction.name)

        val payload = dispatchedAction.payload
        assertNotNull(payload)
        assertEquals("p-cognition: Test Agent", payload["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `handleSovereignAssignment should do nothing if KG was already assigned`() {
        // ARRANGE: A Sovereign agent's name is updated, but its KG is unchanged.
        val oldAgent = AgentInstance("a1", "Test Agent", "kg1", "p", "m", privateSessionId = "ps1")
        val newAgent = oldAgent.copy(name = "Test Agent Updated")

        // ACT
        SovereignAgentLogic.handleSovereignAssignment(fakeStore, oldAgent, newAgent)

        // ASSERT
        assertEquals(0, fakeStore.dispatchedActions.size, "No actions should be dispatched for a simple name change.")
    }

    @Test
    fun `handleSovereignAssignment should do nothing if agent remains stateless`() {
        // ARRANGE: A Vanilla agent's name is updated.
        val oldAgent = AgentInstance("a1", "Vanilla Agent", null, "p", "m")
        val newAgent = oldAgent.copy(name = "Vanilla Agent Updated")

        // ACT
        SovereignAgentLogic.handleSovereignAssignment(fakeStore, oldAgent, newAgent)

        // ASSERT
        assertEquals(0, fakeStore.dispatchedActions.size, "No actions should be dispatched for a stateless agent update.")
    }

    @Test
    fun `handleSovereignAssignment should do nothing if KG is unassigned`() {
        // ARRANGE: An agent transitions from Sovereign back to Vanilla.
        val oldAgent = AgentInstance("a1", "Test Agent", "kg1", "p", "m", privateSessionId = "ps1")
        val newAgent = oldAgent.copy(knowledgeGraphId = null)

        // ACT
        SovereignAgentLogic.handleSovereignAssignment(fakeStore, oldAgent, newAgent)

        // ASSERT
        assertEquals(0, fakeStore.dispatchedActions.size, "No actions should be dispatched when an agent becomes stateless.")
    }
}