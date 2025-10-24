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
    private val sessionB = Session("sid-B", "Session B", emptyList(), 2L)
    private val agent1 = AgentInstance("aid-1", "Agent 1", "", "", "", primarySessionId = "sid-A")
    private val agent2 = AgentInstance("aid-2", "Agent 2", "", "", "", primarySessionId = "sid-A")

    @Test
    fun `when an agent is loaded it should create an IDLE card in its subscribed session`() = runTest {
        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withFeature(sessionFeature)
            .withInitialState("session", SessionState(sessions = mapOf(sessionA.id to sessionA)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        // ACT: Simulate the agent being loaded from disk
        val loadAction = Action(ActionNames.AGENT_INTERNAL_AGENT_LOADED, Json.encodeToJsonElement(AgentInstance.serializer(), agent1) as JsonObject)
        harness.store.dispatch("agent", loadAction)

        // ASSERT
        val postAction = harness.processedActions.find { it.name == ActionNames.SESSION_POST }
        assertNotNull(postAction, "An action to post the avatar card should have been dispatched.")
        assertEquals("sid-A", postAction.payload?.get("session")?.jsonPrimitive?.content)
        assertEquals("aid-1", postAction.payload?.get("senderId")?.jsonPrimitive?.content)
        val metadata = postAction.payload?.get("metadata")?.jsonObject
        assertNotNull(metadata)
        assertEquals(true, metadata["is_transient"]?.jsonPrimitive?.boolean)
        assertEquals("IDLE", metadata["agentStatus"]?.jsonPrimitive?.content)
    }

    @Test
    fun `when an agent changes its session subscription it should remove the old card and create a new one`() = runTest {
        val initialCard = LedgerEntry("msg-123", 1L, "aid-1", null, emptyList(), buildJsonObject { put("render_as_partial", true) })
        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withFeature(sessionFeature)
            .withInitialState("session", SessionState(sessions = mapOf(sessionA.id to sessionA.copy(ledger = listOf(initialCard)), sessionB.id to sessionB)))
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent1.id to agent1), agentAvatarCardIds = mapOf("aid-1" to mapOf(AgentStatus.IDLE to "msg-123"))))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        // ACT: Change subscription from Session A to Session B
        val updateAction = Action(ActionNames.AGENT_UPDATE_CONFIG, buildJsonObject {
            put("agentId", "aid-1")
            put("primarySessionId", "sid-B")
        })
        harness.store.dispatch("ui", updateAction)

        // ASSERT
        val deleteAction = harness.processedActions.find { it.name == ActionNames.SESSION_DELETE_MESSAGE }
        assertNotNull(deleteAction, "An action to delete the old avatar card must be dispatched.")
        assertEquals("sid-A", deleteAction.payload?.get("session")?.jsonPrimitive?.content)
        assertEquals("msg-123", deleteAction.payload?.get("messageId")?.jsonPrimitive?.content)

        val postAction = harness.processedActions.find { it.name == ActionNames.SESSION_POST }
        assertNotNull(postAction, "An action to post the new avatar card must be dispatched.")
        assertEquals("sid-B", postAction.payload?.get("session")?.jsonPrimitive?.content)
    }

    @Test
    fun `when an agent unsubscribes from a session it should remove the old card and not create a new one`() = runTest {
        val initialCard = LedgerEntry("msg-123", 1L, "aid-1", null, emptyList(), buildJsonObject { put("render_as_partial", true) })
        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withFeature(sessionFeature)
            .withInitialState("session", SessionState(sessions = mapOf(sessionA.id to sessionA.copy(ledger = listOf(initialCard)))))
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent1.id to agent1), agentAvatarCardIds = mapOf("aid-1" to mapOf(AgentStatus.IDLE to "msg-123"))))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        // ACT: Unsubscribe by setting primarySessionId to null
        val updateAction = Action(ActionNames.AGENT_UPDATE_CONFIG, buildJsonObject {
            put("agentId", "aid-1")
            put("primarySessionId", null as String?)
        })
        harness.store.dispatch("ui", updateAction)

        // ASSERT
        val deleteAction = harness.processedActions.find { it.name == ActionNames.SESSION_DELETE_MESSAGE }
        assertNotNull(deleteAction, "An action to delete the old avatar card must be dispatched.")
        assertEquals("sid-A", deleteAction.payload?.get("session")?.jsonPrimitive?.content)

        val postAction = harness.processedActions.find { it.name == ActionNames.SESSION_POST }
        assertNull(postAction, "No new avatar card should be posted when unsubscribing.")
    }

    @Test
    fun `when an agent is loaded that is subscribed to a non-existent session the system should remain stable`() = runTest {
        val agentWithBadSub = agent1.copy(primarySessionId = "session-that-does-not-exist")
        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withFeature(sessionFeature)
            .withInitialState("session", SessionState(sessions = mapOf(sessionA.id to sessionA)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        // ACT: Load an agent subscribed to a ghost session
        val loadAction = Action(ActionNames.AGENT_INTERNAL_AGENT_LOADED, Json.encodeToJsonElement(AgentInstance.serializer(), agentWithBadSub) as JsonObject)
        harness.store.dispatch("agent", loadAction)

        // ASSERT
        val postAction = harness.processedActions.find { it.name == ActionNames.SESSION_POST }
        assertNull(postAction, "No avatar card should be posted to a non-existent session.")
        // The test passing without exceptions proves system stability.
    }

    @Test
    fun `when multiple agents are in one session their avatar cards should be managed independently`() = runTest {
        val card1 = LedgerEntry("msg-1", 1L, "aid-1", null, emptyList(), buildJsonObject { put("render_as_partial", true) })
        val card2 = LedgerEntry("msg-2", 2L, "aid-2", null, emptyList(), buildJsonObject { put("render_as_partial", true) })
        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withFeature(sessionFeature)
            .withInitialState("session", SessionState(sessions = mapOf(sessionA.id to sessionA.copy(ledger = listOf(card1, card2)))))
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(agent1.id to agent1, agent2.id to agent2),
                agentAvatarCardIds = mapOf(
                    "aid-1" to mapOf(AgentStatus.IDLE to "msg-1"),
                    "aid-2" to mapOf(AgentStatus.IDLE to "msg-2")
                )
            ))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        // ACT: Delete Agent 1
        val deleteAction = Action(ActionNames.AGENT_DELETE, buildJsonObject { put("agentId", "aid-1") })
        harness.store.dispatch("ui", deleteAction)

        // ASSERT
        val finalSessionState = harness.store.state.value.featureStates["session"] as SessionState
        assertEquals(1, finalSessionState.sessions["sid-A"]?.ledger?.size, "Ledger should only contain the remaining agent's card.")
        assertEquals("msg-2", finalSessionState.sessions["sid-A"]?.ledger?.first()?.id)

        val deleteMsgAction = harness.processedActions.find { it.name == ActionNames.SESSION_DELETE_MESSAGE }
        assertNotNull(deleteMsgAction, "A delete message action should have been dispatched.")
        assertEquals("msg-1", deleteMsgAction.payload?.get("messageId")?.jsonPrimitive?.content, "Should delete the card for agent 1, not agent 2.")
    }
}