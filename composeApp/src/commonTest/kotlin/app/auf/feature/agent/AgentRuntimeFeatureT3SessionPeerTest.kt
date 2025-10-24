package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.generated.ActionNames
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreState
import app.auf.feature.session.LedgerEntry
import app.auf.feature.session.Session
import app.auf.feature.session.SessionFeature
import app.auf.feature.session.SessionState
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Tier 3 Peer Test for AgentRuntimeFeature <-> SessionFeature interaction.
 *
 * Mandate (P-TEST-001, T3): To test the runtime contract and emergent behavior
 * between these two collaborating features, verifying the integrity of the
 * "Public Ledger" and the Agent's UI presence within it.
 */
class AgentRuntimeFeatureT3SessionPeerTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val platform = FakePlatformDependencies("test")
    private val agentFeature = AgentRuntimeFeature(platform, scope)
    private val sessionFeature = SessionFeature(platform, scope)

    private val sessionA = Session("sid-A", "Session A", emptyList(), 1L)
    private val agent1 = AgentInstance("aid-1", "Agent 1", "", "", "", primarySessionId = "sid-A")

    @Test
    fun `when an agent is loaded it posts an IDLE card to the end of the ledger`() = runTest {
        val existingMessage = LedgerEntry("msg-1", 1L, "user", "Hello", emptyList())
        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withFeature(sessionFeature)
            .withInitialState("session", SessionState(sessions = mapOf(sessionA.id to sessionA.copy(ledger = listOf(existingMessage)))))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        // ACT: Simulate the agent being loaded from disk
        val loadAction = Action(ActionNames.AGENT_INTERNAL_AGENT_LOADED, Json.encodeToJsonElement(AgentInstance.serializer(), agent1) as JsonObject)
        harness.store.dispatch("agent", loadAction)

        // ASSERT
        val postAction = harness.processedActions.findLast { it.name == ActionNames.SESSION_POST }
        assertNotNull(postAction, "An action to post the avatar card should have been dispatched.")
        assertEquals("sid-A", postAction.payload?.get("session")?.jsonPrimitive?.content)
        assertNull(postAction.payload?.get("afterMessageId"), "Initial post should have no 'after' ID, appending to the end.")
        val finalSessionState = harness.store.state.value.featureStates["session"] as SessionState
        assertEquals(2, finalSessionState.sessions["sid-A"]?.ledger?.size)
        assertTrue(finalSessionState.sessions["sid-A"]?.ledger?.last()?.metadata?.get("render_as_partial")?.jsonPrimitive?.boolean ?: false)
    }

    @Test
    fun `when a new message arrives an IDLE agent transitions to WAITING and moves its card`() = runTest {
        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withFeature(sessionFeature)
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent1.id to agent1), agentAvatarCardIds = mapOf(agent1.id to "card-1")))
            .withInitialState("session", SessionState(sessions = mapOf(sessionA.id to sessionA)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        // ACT: User posts a new message
        val userPostAction = Action(ActionNames.SESSION_POST, buildJsonObject {
            put("session", "sid-A"); put("senderId", "user"); put("message", "New user message")
        })
        harness.store.dispatch("ui", userPostAction)

        // ASSERT
        val finalAgentState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
        assertEquals(AgentStatus.WAITING, finalAgentState.agents[agent1.id]?.status)

        val deleteAction = harness.processedActions.find { it.name == ActionNames.SESSION_DELETE_MESSAGE }
        assertNotNull(deleteAction)
        assertEquals("card-1", deleteAction.payload?.get("messageId")?.jsonPrimitive?.content)

        val newCardPostAction = harness.processedActions.findLast { it.name == ActionNames.SESSION_POST }
        assertNotNull(newCardPostAction)
        val finalSessionState = harness.store.state.value.featureStates["session"] as SessionState
        val userMessageId = finalSessionState.sessions["sid-A"]?.ledger?.find { it.senderId == "user" }?.id
        assertEquals(userMessageId, newCardPostAction.payload?.get("afterMessageId")?.jsonPrimitive?.content, "New card should be posted after the new user message.")
    }

    @Test
    fun `when triggered a WAITING agent transitions to PROCESSING and locks its card at the commitment frontier`() = runTest {
        val initialUserMessage = LedgerEntry("msg-user-1", 1L, "user", "Prompt", emptyList())
        val agent = agent1.copy(status = AgentStatus.WAITING, lastSeenMessageId = "msg-user-1")
        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withFeature(sessionFeature)
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent.id to agent), agentAvatarCardIds = mapOf(agent.id to "card-waiting")))
            .withInitialState("session", SessionState(sessions = mapOf(sessionA.id to sessionA.copy(ledger = listOf(initialUserMessage)))))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        // ACT: Trigger the agent's turn
        val triggerAction = Action(ActionNames.AGENT_TRIGGER_MANUAL_TURN, buildJsonObject { put("agentId", agent.id) })
        harness.store.dispatch("ui", triggerAction)

        // ASSERT
        val finalAgentState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
        assertEquals(AgentStatus.PROCESSING, finalAgentState.agents[agent.id]?.status)
        assertEquals("msg-user-1", finalAgentState.agents[agent.id]?.processingFrontierMessageId, "Commitment frontier must be locked.")

        val newCardPostAction = harness.processedActions.findLast { it.name == ActionNames.SESSION_POST }
        assertNotNull(newCardPostAction)
        assertEquals("msg-user-1", newCardPostAction.payload?.get("afterMessageId")?.jsonPrimitive?.content, "PROCESSING card should be posted after the commitment frontier.")
    }

    @Test
    fun `when a new message arrives while PROCESSING the awareness frontier updates but the card remains`() = runTest {
        val initialUserMessage = LedgerEntry("msg-user-1", 1L, "user", "Prompt", emptyList())
        val agent = agent1.copy(status = AgentStatus.PROCESSING, processingFrontierMessageId = "msg-user-1")
        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withFeature(sessionFeature)
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent.id to agent), agentAvatarCardIds = mapOf(agent.id to "card-processing")))
            .withInitialState("session", SessionState(sessions = mapOf(sessionA.id to sessionA.copy(ledger = listOf(initialUserMessage)))))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        // ACT: User posts another message while agent is busy
        val userPostAction = Action(ActionNames.SESSION_POST, buildJsonObject {
            put("session", "sid-A"); put("senderId", "user"); put("message", "Another message")
        })
        harness.store.dispatch("ui", userPostAction)

        // ASSERT
        val finalAgentState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
        val finalSessionState = harness.store.state.value.featureStates["session"] as SessionState
        val secondMessageId = finalSessionState.sessions["sid-A"]?.ledger?.last()?.id

        assertEquals(AgentStatus.PROCESSING, finalAgentState.agents[agent.id]?.status, "Agent should still be processing.")
        assertEquals(secondMessageId, finalAgentState.agents[agent.id]?.lastSeenMessageId, "Awareness frontier should update.")
        assertEquals("msg-user-1", finalAgentState.agents[agent.id]?.processingFrontierMessageId, "Commitment frontier should NOT change.")

        val deleteAction = harness.processedActions.find { it.name == ActionNames.SESSION_DELETE_MESSAGE }
        assertNull(deleteAction, "No card should be moved while agent is processing.")
    }
}