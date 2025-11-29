package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.generated.ActionNames
import app.auf.fakes.FakePlatformDependencies
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Tier 1 Unit Tests for AgentCrudLogic.
 * Verifies pure state transitions for configuration and persistence logic.
 */
class AgentRuntimeFeatureT1CrudLogicTest {

    private val platform = FakePlatformDependencies("test")

    @Test
    fun `CREATE should add new agent with valid defaults`() {
        val initialState = AgentRuntimeState()
        val action = Action(ActionNames.AGENT_CREATE, buildJsonObject {
            put("name", "My Agent")
        })

        val newState = AgentCrudLogic.reduce(initialState, action, platform)

        assertEquals(1, newState.agents.size)
        val agent = newState.agents.values.first()
        assertEquals("My Agent", agent.name)
        assertEquals("gemini", agent.modelProvider) // Default
        assertEquals(false, agent.automaticMode) // Default
        assertEquals(agent.id, newState.editingAgentId) // Auto-select for editing
    }

    @Test
    fun `UPDATE_CONFIG should filter out private sessions from subscriptions`() {
        // ARRANGE: Agent subscribed to a public session
        val agent = AgentInstance("a1", "Test", null, "p", "m", subscribedSessionIds = listOf("public-1"))
        val state = AgentRuntimeState(
            agents = mapOf("a1" to agent),
            sessionNames = mapOf(
                "public-1" to "Public Chat",
                "private-1" to "p-cognition: Secret"
            )
        )

        // ACT: Try to subscribe to a private session via update
        val action = Action(ActionNames.AGENT_UPDATE_CONFIG, buildJsonObject {
            put("agentId", "a1")
            put("subscribedSessionIds", buildJsonArray {
                add("public-1")
                add("private-1") // Should be filtered
            })
        })

        val newState = AgentCrudLogic.reduce(state, action, platform)
        val updatedAgent = newState.agents["a1"]!!

        // ASSERT
        assertEquals(1, updatedAgent.subscribedSessionIds.size)
        assertEquals("public-1", updatedAgent.subscribedSessionIds.first())
    }

    @Test
    fun `TOGGLE_AUTOMATIC_MODE should flip the boolean flag`() {
        val agent = AgentInstance("a1", "Test", null, "p", "m", automaticMode = false)
        val state = AgentRuntimeState(agents = mapOf("a1" to agent))

        val action = Action(ActionNames.AGENT_TOGGLE_AUTOMATIC_MODE, buildJsonObject { put("agentId", "a1") })
        val newState = AgentCrudLogic.reduce(state, action, platform)

        assertTrue(newState.agents["a1"]!!.automaticMode)
    }

    @Test
    fun `DELETE (internal confirm) should remove agent and avatar card info`() {
        val agent = AgentInstance("a1", "Test", null, "p", "m")
        // REFACTOR FIX: Use Map<SessionId, MessageId> instead of AvatarCardInfo
        val state = AgentRuntimeState(
            agents = mapOf("a1" to agent),
            agentAvatarCardIds = mapOf("a1" to mapOf("s-1" to "msg-1"))
        )

        val action = Action(ActionNames.AGENT_INTERNAL_CONFIRM_DELETE, buildJsonObject { put("agentId", "a1") })
        val newState = AgentCrudLogic.reduce(state, action, platform)

        assertNull(newState.agents["a1"])
        assertNull(newState.agentAvatarCardIds["a1"])
    }
}