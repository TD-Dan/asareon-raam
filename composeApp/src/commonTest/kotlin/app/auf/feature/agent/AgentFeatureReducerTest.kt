package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.AppState
import app.auf.fakes.FakePlatformDependencies
import app.auf.fakes.FakeStore
import app.auf.util.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class AgentFeatureReducerTest {

    private val testAppVersion = "2.0.0-test"

    private fun createFeatureAndInitialState(
        agents: List<AgentInstance> = emptyList(),
        avatarCardIds: Map<String, Map<AgentStatus, String>> = emptyMap()
    ): Pair<AgentRuntimeFeature, AppState> {
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val feature = AgentRuntimeFeature(fakePlatform, CoroutineScope(Dispatchers.Unconfined))
        val initialAgentMap = agents.associateBy { it.id }
        val initialState = AppState(
            featureStates = mapOf(feature.name to AgentRuntimeState(agents = initialAgentMap, agentAvatarCardIds = avatarCardIds))
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
            put("modelName", "gemini-pro")
            put("primarySessionId", "session-abc")
            put("automaticMode", true)
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
        assertEquals("gemini-pro", newAgent.modelName)
        assertEquals("session-abc", newAgent.primarySessionId)
        assertEquals(true, newAgent.automaticMode)
        assertEquals(AgentStatus.IDLE, newAgent.status)
        assertEquals("fake-uuid-1", agentState.editingAgentId, "Newly created agent should be in edit mode.")
    }

    @Test
    fun `reducer for agent DELETE removes agent and its avatar card tracking`() {
        // ARRANGE
        val agent1 = AgentInstance("agent-1", "Agent One", "p1", "m_prov", "m_name")
        val agent2 = AgentInstance("agent-2", "Agent Two", "p2", "m_prov", "m_name")
        val avatarMap = mapOf("agent-1" to mapOf(AgentStatus.IDLE to "msg-123"))
        val (feature, initialState) = createFeatureAndInitialState(listOf(agent1, agent2), avatarMap)
        val payload = buildJsonObject { put("agentId", "agent-1") }
        val action = Action("agent.DELETE", payload)

        // ACT
        val newState = feature.reducer(initialState, action)

        // ASSERT
        val agentState = newState.featureStates[feature.name] as? AgentRuntimeState
        assertNotNull(agentState)
        assertEquals(1, agentState.agents.size, "Agent map should have one less agent.")
        assertNull(agentState.agents["agent-1"])
        assertNotNull(agentState.agents["agent-2"])
        assertFalse(agentState.agentAvatarCardIds.containsKey("agent-1"), "Avatar card tracking should be removed for the deleted agent.")
    }


    @Test
    fun `reducer for agent UPDATE_CONFIG modifies an existing agent`() {
        // ARRANGE
        val agent1 = AgentInstance("agent-1", "Agent One", "p1", "gemini", "gemini-pro", "session-1", false)
        val (feature, initialState) = createFeatureAndInitialState(listOf(agent1))
        val payload = buildJsonObject {
            put("agentId", "agent-1")
            put("name", "Updated Name")
            put("primarySessionId", "session-2")
            put("automaticMode", true)
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
        assertEquals(true, updatedAgent.automaticMode) // Changed
        assertEquals("gemini", updatedAgent.modelProvider) // Unchanged
    }

    @Test
    fun `reducer for session DELETE nullifies primarySessionId for subscribed agents`() {
        // ARRANGE
        val agent1 = AgentInstance("agent-1", "A1", "p", "m", "m", primarySessionId = "session-to-delete")
        val agent2 = AgentInstance("agent-2", "A2", "p", "m", "m", primarySessionId = "session-safe")
        val agent3 = AgentInstance("agent-3", "A3", "p", "m", "m", primarySessionId = "session-to-delete")
        val (feature, initialState) = createFeatureAndInitialState(listOf(agent1, agent2, agent3))
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
        val (feature, initialState) = createFeatureAndInitialState(listOf(agent1))
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
    fun `reducer for internal AGENT_LOADED adds agent from payload`() {
        // ARRANGE
        val (feature, initialState) = createFeatureAndInitialState() // Start with zero agents
        val agentFromDisk = AgentInstance("agent-from-disk", "Disk Agent", "p-disk", "prov", "model")
        val payload = Json.encodeToJsonElement(agentFromDisk).jsonObject
        val action = Action("agent.internal.AGENT_LOADED", payload)

        // ACT
        val newState = feature.reducer(initialState, action)

        // ASSERT
        val agentState = newState.featureStates[feature.name] as? AgentRuntimeState
        assertNotNull(agentState)
        assertEquals(1, agentState.agents.size)
        assertNotNull(agentState.agents["agent-from-disk"])
        assertEquals("Disk Agent", agentState.agents["agent-from-disk"]?.name)
    }

    @Test
    fun `reducer for session POST from agent with avatar metadata updates agentAvatarCardIds`() {
        // ARRANGE
        val agent = AgentInstance("agent-1", "Test", "p", "m", "m")
        val (feature, initialState) = createFeatureAndInitialState(listOf(agent))
        val payload = buildJsonObject {
            put("senderId", "agent-1")
            put("messageId", "msg-abc")
            put("metadata", buildJsonObject {
                put("render_as_partial", true)
                // THE FIX: The reducer needs the status to know which "bucket" to put the ID in.
                put("agentStatus", "PROCESSING")
            })
        }
        val action = Action("session.POST", payload)

        // ACT
        val newState = feature.reducer(initialState, action)

        // ASSERT
        val agentState = newState.featureStates[feature.name] as? AgentRuntimeState
        assertNotNull(agentState)
        val trackedCards = agentState.agentAvatarCardIds["agent-1"]
        assertNotNull(trackedCards, "Avatar card tracking should be initialized for the agent.")
        assertEquals("msg-abc", trackedCards[AgentStatus.PROCESSING], "The message ID should be tracked under the correct status.")
    }

    @Test
    fun `reducer for session DELETE_MESSAGE removes entry from agentAvatarCardIds`() {
        // ARRANGE
        val agent = AgentInstance("agent-1", "Test", "p", "m", "m")
        val avatarMap = mapOf("agent-1" to mapOf(AgentStatus.IDLE to "msg-to-delete", AgentStatus.PROCESSING to "msg-safe"))
        val (feature, initialState) = createFeatureAndInitialState(listOf(agent), avatarMap)
        val payload = buildJsonObject {
            put("messageId", "msg-to-delete")
            // THE FIX: Removed the senderId hack. The reducer must now find the agent on its own.
            put("metadata", buildJsonObject {
                put("render_as_partial", true)
            })
        }
        val action = Action("session.DELETE_MESSAGE", payload)

        // ACT
        val newState = feature.reducer(initialState, action)

        // ASSERT
        val agentState = newState.featureStates[feature.name] as? AgentRuntimeState
        assertNotNull(agentState)
        val trackedCards = agentState.agentAvatarCardIds["agent-1"]
        assertNotNull(trackedCards)
        assertEquals(1, trackedCards.size, "Only one card should remain.")
        assertFalse(trackedCards.containsKey(AgentStatus.IDLE), "The IDLE card should have been removed.")
        assertEquals("msg-safe", trackedCards[AgentStatus.PROCESSING], "The PROCESSING card should remain.")
    }

    @Test
    fun `reducer gracefully handles actions for non-existent agents`() {
        // ARRANGE
        val agent1 = AgentInstance("agent-1", "Agent One", "p1", "m_prov", "m_name")
        val (feature, initialState) = createFeatureAndInitialState(listOf(agent1))
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

    @Test
    fun `onPrivateData with corrupted config content logs an error and does not dispatch LOADED`() {
        // ARRANGE
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val feature = AgentRuntimeFeature(fakePlatform, CoroutineScope(Dispatchers.Unconfined))
        val store = FakeStore(AppState(), fakePlatform, setOf("agent.internal.AGENT_LOADED"))
        val corruptedJsonContent = """{"id":"bad-agent","name":"Bad Agent",}""" // Invalid trailing comma
        val fileContentPayload = buildJsonObject {
            put("subpath", "bad-agent-1.json")
            put("content", corruptedJsonContent)
        }

        // ACT
        feature.onPrivateData(fileContentPayload, store)

        // ASSERT
        val loadedAction = store.dispatchedActions.find { it.name == "agent.internal.AGENT_LOADED" }
        assertNull(loadedAction, "AGENT_LOADED should not be dispatched for a corrupted config.")

        val log = fakePlatform.capturedLogs.find { it.level == LogLevel.ERROR }
        assertNotNull(log, "An error message should have been logged.")
        assertTrue(log.message.contains("Failed to parse agent config"), "The log message should indicate a parsing failure.")
    }
}