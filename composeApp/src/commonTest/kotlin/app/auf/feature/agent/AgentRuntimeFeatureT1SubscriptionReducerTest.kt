package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.IdentityUUID
import app.auf.core.generated.ActionRegistry
import app.auf.fakes.FakePlatformDependencies
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tier 1 Pure Reducer Tests for agent.ADD_SESSION_SUBSCRIPTION
 * and agent.REMOVE_SESSION_SUBSCRIPTION.
 *
 * These delta actions modify an agent's subscribedSessionIds list
 * without requiring callers to know the full subscription list,
 * preserving cross-feature decoupling.
 */
class AgentRuntimeFeatureT1SubscriptionReducerTest {

    private val platform = FakePlatformDependencies("test")

    private val sessionUUID1 = "a0000000-0000-0000-0000-000000000001"
    private val sessionUUID2 = "a0000000-0000-0000-0000-000000000002"
    private val sessionUUID3 = "a0000000-0000-0000-0000-000000000003"

    private val agentId = "b0000000-0000-0000-0000-000000000001"

    @BeforeTest
    fun setUp() {
        CognitiveStrategyRegistry.clearForTesting()
        CognitiveStrategyRegistry.register(app.auf.feature.agent.strategies.MinimalStrategy)
    }

    @AfterTest
    fun tearDown() {
        CognitiveStrategyRegistry.clearForTesting()
    }

    // ========================================================================
    // ADD_SESSION_SUBSCRIPTION
    // ========================================================================

    @Test
    fun `ADD_SESSION_SUBSCRIPTION adds session to empty subscription list`() {
        val agent = testAgent(
            id = agentId, name = "Agent",
            modelProvider = "p", modelName = "m",
            subscribedSessionIds = emptyList()
        )
        val state = AgentRuntimeState(agents = mapOf(uid(agentId) to agent))

        val action = Action(ActionRegistry.Names.AGENT_ADD_SESSION_SUBSCRIPTION, buildJsonObject {
            put("agentId", agentId)
            put("sessionId", sessionUUID1)
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        val updatedAgent = newState.agents[uid(agentId)]!!
        assertEquals(1, updatedAgent.subscribedSessionIds.size)
        assertTrue(updatedAgent.subscribedSessionIds.contains(IdentityUUID(sessionUUID1)))
    }

    @Test
    fun `ADD_SESSION_SUBSCRIPTION appends to existing subscription list`() {
        val agent = testAgent(
            id = agentId, name = "Agent",
            modelProvider = "p", modelName = "m",
            subscribedSessionIds = listOf(sessionUUID1)
        )
        val state = AgentRuntimeState(agents = mapOf(uid(agentId) to agent))

        val action = Action(ActionRegistry.Names.AGENT_ADD_SESSION_SUBSCRIPTION, buildJsonObject {
            put("agentId", agentId)
            put("sessionId", sessionUUID2)
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        val updatedAgent = newState.agents[uid(agentId)]!!
        assertEquals(2, updatedAgent.subscribedSessionIds.size)
        assertTrue(updatedAgent.subscribedSessionIds.contains(IdentityUUID(sessionUUID1)))
        assertTrue(updatedAgent.subscribedSessionIds.contains(IdentityUUID(sessionUUID2)))
    }

    @Test
    fun `ADD_SESSION_SUBSCRIPTION is idempotent for already subscribed session`() {
        val agent = testAgent(
            id = agentId, name = "Agent",
            modelProvider = "p", modelName = "m",
            subscribedSessionIds = listOf(sessionUUID1, sessionUUID2)
        )
        val state = AgentRuntimeState(agents = mapOf(uid(agentId) to agent))

        val action = Action(ActionRegistry.Names.AGENT_ADD_SESSION_SUBSCRIPTION, buildJsonObject {
            put("agentId", agentId)
            put("sessionId", sessionUUID1) // Already subscribed
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        // State should be unchanged (same reference)
        assertTrue(newState === state, "State should not change when adding an already-subscribed session")
    }

    @Test
    fun `ADD_SESSION_SUBSCRIPTION with unknown agent returns state unchanged`() {
        val state = AgentRuntimeState(agents = emptyMap())

        val action = Action(ActionRegistry.Names.AGENT_ADD_SESSION_SUBSCRIPTION, buildJsonObject {
            put("agentId", agentId)
            put("sessionId", sessionUUID1)
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        assertTrue(newState === state, "State should not change when agent is unknown")
    }

    @Test
    fun `ADD_SESSION_SUBSCRIPTION with missing sessionId returns state unchanged`() {
        val agent = testAgent(id = agentId, name = "Agent", modelProvider = "p", modelName = "m")
        val state = AgentRuntimeState(agents = mapOf(uid(agentId) to agent))

        val action = Action(ActionRegistry.Names.AGENT_ADD_SESSION_SUBSCRIPTION, buildJsonObject {
            put("agentId", agentId)
            // Missing sessionId
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        assertTrue(newState === state, "State should not change when sessionId is missing")
    }

    // ========================================================================
    // REMOVE_SESSION_SUBSCRIPTION
    // ========================================================================

    @Test
    fun `REMOVE_SESSION_SUBSCRIPTION removes session from subscription list`() {
        val agent = testAgent(
            id = agentId, name = "Agent",
            modelProvider = "p", modelName = "m",
            subscribedSessionIds = listOf(sessionUUID1, sessionUUID2)
        )
        val state = AgentRuntimeState(agents = mapOf(uid(agentId) to agent))

        val action = Action(ActionRegistry.Names.AGENT_REMOVE_SESSION_SUBSCRIPTION, buildJsonObject {
            put("agentId", agentId)
            put("sessionId", sessionUUID1)
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        val updatedAgent = newState.agents[uid(agentId)]!!
        assertEquals(1, updatedAgent.subscribedSessionIds.size)
        assertFalse(updatedAgent.subscribedSessionIds.contains(IdentityUUID(sessionUUID1)))
        assertTrue(updatedAgent.subscribedSessionIds.contains(IdentityUUID(sessionUUID2)))
    }

    @Test
    fun `REMOVE_SESSION_SUBSCRIPTION removes last session leaving empty list`() {
        val agent = testAgent(
            id = agentId, name = "Agent",
            modelProvider = "p", modelName = "m",
            subscribedSessionIds = listOf(sessionUUID1)
        )
        val state = AgentRuntimeState(agents = mapOf(uid(agentId) to agent))

        val action = Action(ActionRegistry.Names.AGENT_REMOVE_SESSION_SUBSCRIPTION, buildJsonObject {
            put("agentId", agentId)
            put("sessionId", sessionUUID1)
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        val updatedAgent = newState.agents[uid(agentId)]!!
        assertTrue(updatedAgent.subscribedSessionIds.isEmpty())
    }

    @Test
    fun `REMOVE_SESSION_SUBSCRIPTION is no-op for non-subscribed session`() {
        val agent = testAgent(
            id = agentId, name = "Agent",
            modelProvider = "p", modelName = "m",
            subscribedSessionIds = listOf(sessionUUID1)
        )
        val state = AgentRuntimeState(agents = mapOf(uid(agentId) to agent))

        val action = Action(ActionRegistry.Names.AGENT_REMOVE_SESSION_SUBSCRIPTION, buildJsonObject {
            put("agentId", agentId)
            put("sessionId", sessionUUID3) // Not in subscription list
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        assertTrue(newState === state, "State should not change when removing a non-subscribed session")
    }

    @Test
    fun `REMOVE_SESSION_SUBSCRIPTION with unknown agent returns state unchanged`() {
        val state = AgentRuntimeState(agents = emptyMap())

        val action = Action(ActionRegistry.Names.AGENT_REMOVE_SESSION_SUBSCRIPTION, buildJsonObject {
            put("agentId", agentId)
            put("sessionId", sessionUUID1)
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        assertTrue(newState === state)
    }

    @Test
    fun `REMOVE_SESSION_SUBSCRIPTION does not affect other agents`() {
        val agent1Id = "b0000000-0000-0000-0000-000000000001"
        val agent2Id = "b0000000-0000-0000-0000-000000000002"

        val agent1 = testAgent(
            id = agent1Id, name = "Agent 1",
            modelProvider = "p", modelName = "m",
            subscribedSessionIds = listOf(sessionUUID1, sessionUUID2)
        )
        val agent2 = testAgent(
            id = agent2Id, name = "Agent 2",
            modelProvider = "p", modelName = "m",
            subscribedSessionIds = listOf(sessionUUID1, sessionUUID2)
        )
        val state = AgentRuntimeState(agents = mapOf(
            uid(agent1Id) to agent1,
            uid(agent2Id) to agent2
        ))

        val action = Action(ActionRegistry.Names.AGENT_REMOVE_SESSION_SUBSCRIPTION, buildJsonObject {
            put("agentId", agent1Id)
            put("sessionId", sessionUUID1)
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        // Agent 1 should lose sessionUUID1
        assertEquals(1, newState.agents[uid(agent1Id)]!!.subscribedSessionIds.size)
        // Agent 2 should be unaffected
        assertEquals(2, newState.agents[uid(agent2Id)]!!.subscribedSessionIds.size)
    }
}