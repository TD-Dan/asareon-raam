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
import kotlinx.serialization.json.encodeToJsonElement
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
    // *** MODIFIED: Use new data model
    private val agent1 = AgentInstance("aid-1", "Agent 1", "", "", "", subscribedSessionIds = listOf("sid-A"))

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
        val loadAction = Action(ActionNames.AGENT_INTERNAL_AGENT_LOADED, Json.encodeToJsonElement(agent1) as JsonObject)
        harness.store.dispatch("agent", loadAction)

        // ASSERT: Verify actions
        val postAction = harness.processedActions.findLast { it.name == ActionNames.SESSION_POST }
        assertNotNull(postAction, "An action to post the avatar card should have been dispatched.")
        assertEquals("sid-A", postAction.payload?.get("session")?.jsonPrimitive?.content)
        assertNull(postAction.payload?.get("afterMessageId"), "Initial post should have no 'after' ID, appending to the end.")

        // REFACTOR: Close the Assertion Gap. Verify the final state of the SessionFeature.
        val finalSessionState = harness.store.state.value.featureStates["session"] as SessionState
        val finalLedger = finalSessionState.sessions["sid-A"]!!.ledger
        assertEquals(2, finalLedger.size, "Ledger should contain the original message and the new avatar card.")
        assertTrue(finalLedger.last().metadata?.get("render_as_partial")?.jsonPrimitive?.boolean ?: false, "The last item should be an avatar card.")
    }

    @Test
    fun `when a new message arrives an IDLE agent transitions to WAITING and moves its card`() = runTest {
        val initialAvatarCards = mapOf(agent1.id to AgentRuntimeState.AvatarCardInfo("card-1", "sid-A"))
        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withFeature(sessionFeature)
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent1.id to agent1), agentAvatarCardIds = initialAvatarCards))
            .withInitialState("session", SessionState(sessions = mapOf(sessionA.id to sessionA)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        // ACT: User posts a new message
        val userPostAction = Action(ActionNames.SESSION_POST, buildJsonObject {
            put("session", "sid-A"); put("senderId", "user"); put("message", "New user message")
        })
        harness.store.dispatch("ui", userPostAction)

        // ASSERT (Agent State)
        val finalAgentState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
        assertEquals(AgentStatus.WAITING, finalAgentState.agents[agent1.id]?.status)

        // ASSERT (Actions)
        val deleteAction = harness.processedActions.find { it.name == ActionNames.SESSION_DELETE_MESSAGE }
        assertNotNull(deleteAction)
        assertEquals("card-1", deleteAction.payload?.get("messageId")?.jsonPrimitive?.content)

        // REFACTOR: Close the Assertion Gap. Verify the final state of the SessionFeature.
        val finalSessionState = harness.store.state.value.featureStates["session"] as SessionState
        val finalLedger = finalSessionState.sessions["sid-A"]!!.ledger
        val userMessageId = finalLedger.find { it.senderId == "user" }?.id
        val newCard = finalLedger.last()

        assertEquals(2, finalLedger.size, "Ledger should contain the user message and one avatar card.")
        assertEquals(1, finalLedger.count { it.metadata?.get("render_as_partial")?.jsonPrimitive?.boolean == true }, "There should be exactly one avatar card.")
        assertEquals(userMessageId, finalAgentState.agents[agent1.id]?.lastSeenMessageId, "Agent's awareness frontier should be the user message.")
        assertEquals("WAITING", newCard.metadata?.get("agentStatus")?.jsonPrimitive?.content)
    }

    @Test
    fun `when triggered a WAITING agent transitions to PROCESSING and locks its card at the commitment frontier`() = runTest {
        val initialUserMessage = LedgerEntry("msg-user-1", 1L, "user", "Prompt", emptyList())
        val agent = agent1.copy(status = AgentStatus.WAITING, lastSeenMessageId = "msg-user-1")
        val initialAvatarCards = mapOf(agent.id to AgentRuntimeState.AvatarCardInfo("card-waiting", "sid-A"))
        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withFeature(sessionFeature)
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent.id to agent), agentAvatarCardIds = initialAvatarCards))
            .withInitialState("session", SessionState(sessions = mapOf(sessionA.id to sessionA.copy(ledger = listOf(initialUserMessage)))))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        // ACT: Trigger the agent's turn
        val triggerAction = Action(ActionNames.AGENT_INITIATE_TURN, buildJsonObject { put("agentId", agent.id) })
        harness.store.dispatch("ui", triggerAction)

        // ASSERT (Agent State)
        val finalAgentState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
        assertEquals(AgentStatus.PROCESSING, finalAgentState.agents[agent.id]?.status)
        assertEquals("msg-user-1", finalAgentState.agents[agent.id]?.processingFrontierMessageId, "Commitment frontier must be locked.")

        // REFACTOR: Close the Assertion Gap. Verify the final state of the SessionFeature.
        val finalSessionState = harness.store.state.value.featureStates["session"] as SessionState
        val finalLedger = finalSessionState.sessions["sid-A"]!!.ledger
        val newCard = finalLedger.last()

        assertEquals(2, finalLedger.size, "Ledger should contain the user message and one PROCESSING avatar card.")
        assertEquals(1, finalLedger.count { it.metadata?.get("render_as_partial")?.jsonPrimitive?.boolean == true }, "There should be exactly one avatar card.")

        // THE FIX: Assert on the card's actual position in the ledger, not a debug field.
        val userMessageIndex = finalLedger.indexOf(initialUserMessage)
        val newCardIndex = finalLedger.indexOf(newCard)
        assertEquals(userMessageIndex + 1, newCardIndex, "PROCESSING card should be positioned directly after the commitment frontier message.")
        assertEquals("PROCESSING", newCard.metadata?.get("agentStatus")?.jsonPrimitive?.content)
    }

    @Test
    fun `when a new message arrives while PROCESSING the awareness frontier updates but the card remains`() = runTest {
        val initialUserMessage = LedgerEntry("msg-user-1", 1L, "user", "Prompt", emptyList())
        // THE FIX: The test setup must be internally consistent. The initial Session ledger must contain the card
        // that the AgentRuntimeState believes exists.
        val processingCard = LedgerEntry("card-processing", 2L, agent1.id, "", emptyList(), metadata = buildJsonObject { put("render_as_partial", true) })

        val agent = agent1.copy(status = AgentStatus.PROCESSING, processingFrontierMessageId = "msg-user-1")
        val initialAvatarCards = mapOf(agent.id to AgentRuntimeState.AvatarCardInfo("card-processing", "sid-A"))

        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withFeature(sessionFeature)
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent.id to agent), agentAvatarCardIds = initialAvatarCards))
            .withInitialState("session", SessionState(sessions = mapOf(sessionA.id to sessionA.copy(ledger = listOf(initialUserMessage, processingCard)))))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        // ACT: User posts another message while agent is busy
        val userPostAction = Action(ActionNames.SESSION_POST, buildJsonObject {
            put("session", "sid-A"); put("senderId", "user"); put("message", "Another message")
        })
        harness.store.dispatch("ui", userPostAction)

        // ASSERT (Agent State)
        val finalAgentState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
        val finalSessionState = harness.store.state.value.featureStates["session"] as SessionState
        val secondMessageId = finalSessionState.sessions["sid-A"]?.ledger?.last { it.senderId == "user" }?.id

        assertEquals(AgentStatus.PROCESSING, finalAgentState.agents[agent.id]?.status, "Agent should still be processing.")
        assertEquals(secondMessageId, finalAgentState.agents[agent.id]?.lastSeenMessageId, "Awareness frontier should update.")
        assertEquals("msg-user-1", finalAgentState.agents[agent.id]?.processingFrontierMessageId, "Commitment frontier should NOT change.")

        // REFACTOR: Close the Assertion Gap. Verify the final state of the SessionFeature.
        val finalLedger = finalSessionState.sessions["sid-A"]!!.ledger
        assertEquals(3, finalLedger.size, "Ledger should contain both user messages and the original processing card.")
        assertEquals(1, finalLedger.count { it.metadata?.get("render_as_partial")?.jsonPrimitive?.boolean == true }, "There should still be only one avatar card.")
        assertNotNull(finalLedger.find { it.id == "card-processing" }, "The original card should not have been moved or deleted.")
    }
}