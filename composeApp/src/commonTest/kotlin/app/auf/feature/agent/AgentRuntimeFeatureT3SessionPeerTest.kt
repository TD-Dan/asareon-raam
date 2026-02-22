package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.IdentityUUID
import app.auf.core.generated.ActionRegistry
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
import kotlinx.serialization.json.buildJsonObject
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

    private val sessionA = testSession("sid-A", "Session A")
    private val agent1 = testAgent("aid-1", "Agent 1", subscribedSessionIds = listOf("sid-A"))
    private val agent1UUID = IdentityUUID(agent1.identity.uuid!!)

    @Test
    fun `when a new message arrives an IDLE agent transitions to WAITING and moves its card`() = runTest {
        val initialAvatarCards: Map<IdentityUUID, Map<IdentityUUID, String>> = mapOf(
            agent1UUID to mapOf(IdentityUUID("sid-A") to "card-1")
        )

        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withFeature(sessionFeature)
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent1UUID to agent1), agentAvatarCardIds = initialAvatarCards))
            .withInitialState("session", SessionState(sessions = mapOf(sessionA.identity.uuid!! to sessionA)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // ACT: User posts a new message
            val userPostAction = Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
                put("session", "sid-A"); put("senderId", "user"); put("message", "New user message")
            })
            harness.store.dispatch("ui", userPostAction)

            // ASSERT (Agent State)
            val finalAgentState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState

            assertEquals(AgentStatus.WAITING, finalAgentState.agentStatuses[agent1UUID]?.status)

            // Verify awareness frontier
            val finalSessionState = harness.store.state.value.featureStates["session"] as SessionState
            val userMessageId = finalSessionState.sessions["sid-A"]?.ledger?.find { it.senderId == "user" }?.id
            assertEquals(userMessageId, finalAgentState.agentStatuses[agent1UUID]?.lastSeenMessageId)
        }
    }

    @Test
    fun `when triggered a WAITING agent transitions to PROCESSING and locks its card at the commitment frontier`() = runTest {
        val initialUserMessage = LedgerEntry("msg-user-1", 1L, "user", "Prompt", emptyList())
        val agentConfig = agent1
        // START STATE: Waiting
        val status = AgentStatusInfo(status = AgentStatus.WAITING, lastSeenMessageId = "msg-user-1")
        val initialAvatarCards: Map<IdentityUUID, Map<IdentityUUID, String>> = mapOf(
            agent1UUID to mapOf(IdentityUUID("sid-A") to "card-waiting")
        )

        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withFeature(sessionFeature)
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(agent1UUID to agentConfig),
                agentStatuses = mapOf(agent1UUID to status),
                agentAvatarCardIds = initialAvatarCards
            ))
            .withInitialState("session", SessionState(sessions = mapOf(sessionA.identity.uuid!! to sessionA.copy(ledger = listOf(initialUserMessage)))))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // ACT: Trigger the agent's turn
            val triggerAction = Action(ActionRegistry.Names.AGENT_INITIATE_TURN, buildJsonObject { put("agentId", agentConfig.identity.uuid) })
            harness.store.dispatch("ui", triggerAction)

            // ASSERT (Agent State)
            val finalAgentState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
            assertEquals(AgentStatus.PROCESSING, finalAgentState.agentStatuses[agent1UUID]?.status)
            // Check the commitment frontier
            assertEquals("msg-user-1", finalAgentState.agentStatuses[agent1UUID]?.processingFrontierMessageId)
        }
    }
}