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
 */
class AgentRuntimeFeatureT3SessionPeerTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val platform = FakePlatformDependencies("test")
    private val agentFeature = AgentRuntimeFeature(platform, scope)
    private val sessionFeature = SessionFeature(platform, scope)

    private val sessionA = Session("sid-A", "Session A", emptyList(), 1L)
    private val agent1 = AgentInstance("aid-1", "Agent 1", "", "", "", subscribedSessionIds = listOf("sid-A"))

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
        // *** MODIFIED: Use agentStatuses
        assertEquals(AgentStatus.WAITING, finalAgentState.agentStatuses[agent1.id]?.status)

        // Verify awareness frontier
        val finalSessionState = harness.store.state.value.featureStates["session"] as SessionState
        val userMessageId = finalSessionState.sessions["sid-A"]?.ledger?.find { it.senderId == "user" }?.id
        assertEquals(userMessageId, finalAgentState.agentStatuses[agent1.id]?.lastSeenMessageId)
    }

    @Test
    fun `when triggered a WAITING agent transitions to PROCESSING and locks its card at the commitment frontier`() = runTest {
        val initialUserMessage = LedgerEntry("msg-user-1", 1L, "user", "Prompt", emptyList())
        val agentConfig = agent1
        // START STATE: Waiting
        val status = AgentStatusInfo(status = AgentStatus.WAITING, lastSeenMessageId = "msg-user-1")
        val initialAvatarCards = mapOf(agentConfig.id to AgentRuntimeState.AvatarCardInfo("card-waiting", "sid-A"))

        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withFeature(sessionFeature)
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(agentConfig.id to agentConfig),
                agentStatuses = mapOf(agentConfig.id to status),
                agentAvatarCardIds = initialAvatarCards
            ))
            .withInitialState("session", SessionState(sessions = mapOf(sessionA.id to sessionA.copy(ledger = listOf(initialUserMessage)))))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        // ACT: Trigger the agent's turn
        val triggerAction = Action(ActionNames.AGENT_INITIATE_TURN, buildJsonObject { put("agentId", agentConfig.id) })
        harness.store.dispatch("ui", triggerAction)

        // ASSERT (Agent State)
        val finalAgentState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
        assertEquals(AgentStatus.PROCESSING, finalAgentState.agentStatuses[agentConfig.id]?.status)
        // Check commitment frontier
        assertEquals("msg-user-1", finalAgentState.agentStatuses[agentConfig.id]?.processingFrontierMessageId)
    }
}