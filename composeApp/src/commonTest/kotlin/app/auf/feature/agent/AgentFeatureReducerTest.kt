package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.AppState
import app.auf.fakes.FakePlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

class AgentFeatureReducerTest {

    private val testAppVersion = "2.0.0-test"

    private fun createFeatureAndInitialState(vararg initialAgents: AgentInstance): Pair<AgentRuntimeFeature, AppState> {
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val feature = AgentRuntimeFeature(fakePlatform, CoroutineScope(Dispatchers.Unconfined))
        val initialAgentMap = initialAgents.associateBy { it.id }
        val initialState = AppState(
            featureStates = mapOf(feature.name to AgentRuntimeState(agents = initialAgentMap))
        )
        return Pair(feature, initialState)
    }

    @Test
    fun `reducer for agent CREATE adds a new agent correctly`() {
        // ARRANGE
        val (feature, initialState) = createFeatureAndInitialState()
        val payload = buildJsonObject {
            put("name", "Test Agent")
            put("personaId", "persona-test-123")
            put("modelProvider", "gemini")
            put("modelName", "gemini-2.5-pro")
            put("primarySessionId", "session-abc")
        }
        val action = Action("agent.CREATE", payload)

        // ACT
        val newState = feature.reducer(initialState, action)

        // ASSERT
        val agentState = newState.featureStates[feature.name] as? AgentRuntimeState
        assertNotNull(agentState)
        assertEquals(1, agentState.agents.size)
        val newAgent = agentState.agents["fake-uuid-1"]
        assertNotNull(newAgent)
        assertEquals("Test Agent", newAgent.name)
        assertEquals("persona-test-123", newAgent.personaId)
        assertEquals("gemini-2.5-pro", newAgent.modelName)
        assertEquals("session-abc", newAgent.primarySessionId)
        assertEquals(AgentStatus.IDLE, newAgent.status)
    }

    @Test
    fun `reducer for agent DELETE removes the correct agent`() {
        // ARRANGE
        val agent1 = AgentInstance("agent-1", "Agent One", "p1", "m_prov", "m_name")
        val agent2 = AgentInstance("agent-2", "Agent Two", "p2", "m_prov", "m_name")
        val (feature, initialState) = createFeatureAndInitialState(agent1, agent2)
        val payload = buildJsonObject { put("agentId", "agent-1") }
        val action = Action("agent.DELETE", payload)

        // ACT
        val newState = feature.reducer(initialState, action)

        // ASSERT
        val agentState = newState.featureStates[feature.name] as? AgentRuntimeState
        assertNotNull(agentState)
        assertEquals(1, agentState.agents.size)
        assertNull(agentState.agents["agent-1"])
        assertNotNull(agentState.agents["agent-2"])
    }

    @Test
    fun `reducer for agent UPDATE_CONFIG modifies an existing agent`() {
        // ARRANGE
        val agent1 = AgentInstance("agent-1", "Agent One", "p1", "gemini", "gemini-pro", "session-1")
        val (feature, initialState) = createFeatureAndInitialState(agent1)
        val payload = buildJsonObject {
            put("agentId", "agent-1")
            put("name", "Updated Name")
            put("primarySessionId", "session-2")
        }
        val action = Action("agent.UPDATE_CONFIG", payload)

        // ACT
        val newState = feature.reducer(initialState, action)

        // ASSERT
        val agentState = newState.featureStates[feature.name] as? AgentRuntimeState
        assertNotNull(agentState)
        val updatedAgent = agentState.agents["agent-1"]
        assertNotNull(updatedAgent)
        assertEquals("Updated Name", updatedAgent.name) // Changed
        assertEquals("session-2", updatedAgent.primarySessionId) // Changed
        assertEquals("gemini", updatedAgent.modelProvider) // Unchanged
    }

    @Test
    fun `reducer for session DELETE nullifies primarySessionId for subscribed agents`() {
        // ARRANGE
        val agent1 = AgentInstance("agent-1", "A1", "p", "m", "m", primarySessionId = "session-to-delete")
        val agent2 = AgentInstance("agent-2", "A2", "p", "m", "m", primarySessionId = "session-safe")
        val agent3 = AgentInstance("agent-3", "A3", "p", "m", "m", primarySessionId = "session-to-delete")
        val (feature, initialState) = createFeatureAndInitialState(agent1, agent2, agent3)
        val payload = buildJsonObject { put("sessionId", "session-to-delete") }
        val action = Action("session.DELETE", payload)

        // ACT
        val newState = feature.reducer(initialState, action)

        // ASSERT
        val agentState = newState.featureStates[feature.name] as? AgentRuntimeState
        assertNotNull(agentState)
        assertNull(agentState.agents["agent-1"]?.primarySessionId)
        assertEquals("session-safe", agentState.agents["agent-2"]?.primarySessionId)
        assertNull(agentState.agents["agent-3"]?.primarySessionId)
    }

    @Test
    fun `reducer for internal SET_STATUS updates agent status`() {
        // ARRANGE
        val agent1 = AgentInstance("agent-1", "Agent One", "p1", "m", "m", status = AgentStatus.IDLE)
        val (feature, initialState) = createFeatureAndInitialState(agent1)
        val payload = buildJsonObject {
            put("agentId", "agent-1")
            put("status", "PROCESSING")
        }
        val action = Action("agent.internal.SET_STATUS", payload)

        // ACT
        val newState = feature.reducer(initialState, action)

        // ASSERT
        val agentState = newState.featureStates[feature.name] as? AgentRuntimeState
        assertNotNull(agentState)
        assertEquals(AgentStatus.PROCESSING, agentState.agents["agent-1"]?.status)
    }

    @Test
    fun `reducer gracefully handles actions for non-existent agents`() {
        // ARRANGE
        val agent1 = AgentInstance("agent-1", "Agent One", "p1", "m_prov", "m_name")
        val (feature, initialState) = createFeatureAndInitialState(agent1)
        val deletePayload = buildJsonObject { put("agentId", "agent-999") }
        val updatePayload = buildJsonObject {
            put("agentId", "agent-999")
            put("name", "Should Not Apply")
        }
        val deleteAction = Action("agent.DELETE", deletePayload)
        val updateAction = Action("agent.UPDATE_CONFIG", updatePayload)

        // ACT & ASSERT
        val stateAfterDelete = feature.reducer(initialState, deleteAction)
        assertEquals(initialState, stateAfterDelete, "DELETE should not change state for unknown agent.")

        val stateAfterUpdate = feature.reducer(initialState, updateAction)
        assertEquals(initialState, stateAfterUpdate, "UPDATE_CONFIG should not change state for unknown agent.")
    }
}