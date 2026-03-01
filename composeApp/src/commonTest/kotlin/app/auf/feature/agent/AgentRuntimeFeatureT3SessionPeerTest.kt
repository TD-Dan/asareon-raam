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
import app.auf.test.TestHarness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Tier 3 Peer Test for AgentRuntimeFeature <-> SessionFeature interaction.
 *
 * NOTE: Session UUIDs must be proper UUID v4 format (8-4-4-4-12 hex) because
 * SESSION_MESSAGE_POSTED uses stringIsUUID() validation in the reducer.
 *
 * NOTE: INITIATE_TURN is a public action resolved via the identity registry
 * (resolveAgentId), so the agent identity must be registered before dispatch.
 */
class AgentRuntimeFeatureT3SessionPeerTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val platform = FakePlatformDependencies("test")
    private val agentFeature = AgentRuntimeFeature(platform, scope)
    private val sessionFeature = SessionFeature(platform, scope)

    // Proper UUID v4 format — required by stringIsUUID validation
    private val sessionAId = "a0000000-0000-0000-0000-00000000000a"
    private val agentId = "b0000000-0000-0000-0000-00000000000b"

    private val sessionA = testSession(sessionAId, "Session A")
    private val agent1 = testAgent(agentId, "Agent 1", subscribedSessionIds = listOf(sessionAId))
    private val agent1UUID = IdentityUUID(agentId)

    /**
     * Registers the agent identity in the identity registry so that
     * resolveAgentId (used by INITIATE_TURN) can find it.
     */
    private fun registerAgentIdentity(harness: TestHarness) {
        harness.store.dispatch("agent", Action(
            ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            buildJsonObject {
                put("uuid", agent1.identity.uuid)
                put("name", agent1.identity.name)
            }
        ))
    }

    /**
     * Registers a session identity in the identity registry so that
     * AgentCognitivePipeline can resolve session UUIDs to handles.
     */
    private fun registerSessionIdentity(harness: TestHarness, session: app.auf.feature.session.Session) {
        harness.store.dispatch("session", Action(
            ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            buildJsonObject {
                put("uuid", session.identity.uuid)
                put("name", session.identity.name)
            }
        ))
    }

    @Test
    fun `when a new message arrives an IDLE agent transitions to WAITING and moves its card`() = runTest {
        val initialAvatarCards: Map<IdentityUUID, Map<IdentityUUID, String>> = mapOf(
            agent1UUID to mapOf(IdentityUUID(sessionAId) to "card-1")
        )

        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withFeature(sessionFeature)
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent1UUID to agent1), agentAvatarCardIds = initialAvatarCards))
            .withInitialState("session", SessionState(sessions = mapOf(sessionAId to sessionA)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // ACT: User posts a new message
            val userPostAction = Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
                put("session", sessionAId); put("senderId", "user"); put("message", "New user message")
            })
            harness.store.dispatch("ui", userPostAction)

            // ASSERT (Agent State)
            val finalAgentState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState

            assertEquals(AgentStatus.WAITING, finalAgentState.agentStatuses[agent1UUID]?.status)

            // Verify awareness frontier
            val finalSessionState = harness.store.state.value.featureStates["session"] as SessionState
            val userMessageId = finalSessionState.sessions[sessionAId]?.ledger?.find { it.senderId == "user" }?.id
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
            agent1UUID to mapOf(IdentityUUID(sessionAId) to "card-waiting")
        )

        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withFeature(sessionFeature)
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(agent1UUID to agentConfig),
                agentStatuses = mapOf(agent1UUID to status),
                agentAvatarCardIds = initialAvatarCards
            ))
            .withInitialState("session", SessionState(sessions = mapOf(sessionAId to sessionA.copy(ledger = listOf(initialUserMessage)))))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // Register agent identity so resolveAgentId works for INITIATE_TURN
            registerAgentIdentity(harness)
            // Register session identity so AgentCognitivePipeline can resolve session UUIDs
            registerSessionIdentity(harness, sessionA)

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