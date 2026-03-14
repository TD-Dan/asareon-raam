package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.IdentityUUID
import app.auf.core.generated.ActionRegistry
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreState
import app.auf.feature.filesystem.FileSystemFeature
import app.auf.feature.session.SessionFeature
import app.auf.feature.session.SessionState
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import app.auf.test.TestHarness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Tier 3 Peer Test: Agent Lifecycle Operations
 *
 * Covers:
 * 1. AGENT_CANCEL_TURN — Processing → Idle, gateway cancel dispatched
 * 2. AGENT_DELETE — avatar cleanup, filesystem delete, identity unregistration, state removal
 * 3. SESSION_SESSION_DELETED — subscription cleanup, avatar card cleanup, persistence flag
 *
 * NOTE: CANCEL_TURN and DELETE are public, command-dispatchable actions that resolve
 * the agent via the identity registry (resolveAgentId). Tests must register the agent
 * identity before dispatching these actions.
 *
 * NOTE: SESSION_SESSION_DELETED uses stringIsUUID() validation, so session IDs must
 * be proper UUID v4 format (8-4-4-4-12 lowercase hex).
 */
class AgentRuntimeFeatureT3LifecycleTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val platform = FakePlatformDependencies("test")

    // Use proper UUID v4 format — required by stringIsUUID validation in SESSION_SESSION_DELETED
    private val sessionUUID1 = "a0000000-0000-0000-0000-000000000001"
    private val sessionUUID2 = "a0000000-0000-0000-0000-000000000002"
    private val testSession1 = testSession(sessionUUID1, "Session One")
    private val testSession2 = testSession(sessionUUID2, "Session Two")

    private val agentId = "b0000000-0000-0000-0000-000000000001"
    private val agent = testAgent(
        id = agentId,
        name = "Lifecycle Agent",
        modelProvider = "mock",
        modelName = "mock-model",
        subscribedSessionIds = listOf(sessionUUID1, sessionUUID2),
        resources = mapOf("system_instruction" to "res-sys-instruction-v1")
    )
    private val agentUUID = IdentityUUID(agentId)

    private fun buildHarness(
        agents: Map<IdentityUUID, AgentInstance> = mapOf(agentUUID to agent),
        agentStatuses: Map<IdentityUUID, AgentStatusInfo> = emptyMap(),
        agentAvatarCardIds: Map<IdentityUUID, Map<IdentityUUID, String>> = emptyMap(),
        sessions: Map<String, app.auf.feature.session.Session> = mapOf(
            sessionUUID1 to testSession1,
            sessionUUID2 to testSession2
        )
    ): TestHarness = TestEnvironment.create()
        .withFeature(AgentRuntimeFeature(platform, scope))
        .withFeature(SessionFeature(platform, scope))
        .withFeature(FileSystemFeature(platform))
        .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
        .withInitialState("agent", AgentRuntimeState(
            agents = agents,
            agentStatuses = agentStatuses,
            agentAvatarCardIds = agentAvatarCardIds,
            resources = emptyList()
        ))
        .withInitialState("session", SessionState(sessions = sessions))
        .build(platform = platform)

    /**
     * Registers the agent identity in the identity registry so that
     * resolveAgentId (used by public actions like DELETE, CANCEL_TURN) can find it.
     */
    private fun registerAgentIdentity(harness: TestHarness, agentInstance: AgentInstance = agent) {
        harness.store.dispatch("agent", Action(
            ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            buildJsonObject {
                put("uuid", agentInstance.identity.uuid)
                put("name", agentInstance.identity.name)
            }
        ))
    }

    /**
     * Registers session identities in the identity registry so that
     * resolveSessionHandle (used by avatar cleanup in DELETE) can resolve
     * session UUIDs to handles.
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

    // ========================================================================
    // CANCEL TURN
    // ========================================================================

    @Test
    fun `cancel turn transitions PROCESSING agent to IDLE and dispatches gateway cancel`() = runTest {
        val processingStatus = AgentStatusInfo(status = AgentStatus.PROCESSING, processingSinceTimestamp = 1000L)
        val harness = buildHarness(
            agentStatuses = mapOf(agentUUID to processingStatus)
        )

        harness.runAndLogOnFailure {
            registerAgentIdentity(harness)

            // ACT
            harness.store.dispatch("core", Action(
                ActionRegistry.Names.AGENT_CANCEL_TURN,
                buildJsonObject { put("agentId", agentId) }
            ))

            // ASSERT: Gateway cancel dispatched
            val gatewayCancelAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.GATEWAY_CANCEL_REQUEST
            }
            assertNotNull(gatewayCancelAction, "GATEWAY_CANCEL_REQUEST should be dispatched")
            assertEquals(
                agentId,
                gatewayCancelAction.payload?.get("correlationId")?.jsonPrimitive?.content,
                "Cancel request correlationId should be the agent UUID"
            )

            // ASSERT: Agent transitions to IDLE
            val finalState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
            assertEquals(
                AgentStatus.IDLE,
                finalState.agentStatuses[agentUUID]?.status,
                "Agent should be IDLE after cancel"
            )
        }
    }

    @Test
    fun `cancel turn on IDLE agent is a no-op`() = runTest {
        val idleStatus = AgentStatusInfo(status = AgentStatus.IDLE)
        val harness = buildHarness(
            agentStatuses = mapOf(agentUUID to idleStatus)
        )

        harness.runAndLogOnFailure {
            registerAgentIdentity(harness)

            // ACT
            harness.store.dispatch("core", Action(
                ActionRegistry.Names.AGENT_CANCEL_TURN,
                buildJsonObject { put("agentId", agentId) }
            ))

            // ASSERT: Agent remains IDLE (cancel still fires but doesn't break anything)
            val finalState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
            assertEquals(AgentStatus.IDLE, finalState.agentStatuses[agentUUID]?.status)
        }
    }

    // ========================================================================
    // AGENT DELETE CASCADE
    // ========================================================================

    @Test
    fun `delete agent removes avatar cards from sessions`() = runTest {
        val avatarCards: Map<IdentityUUID, Map<IdentityUUID, String>> = mapOf(
            agentUUID to mapOf(
                IdentityUUID(sessionUUID1) to "avatar-msg-1",
                IdentityUUID(sessionUUID2) to "avatar-msg-2"
            )
        )
        val harness = buildHarness(agentAvatarCardIds = avatarCards)

        harness.runAndLogOnFailure {
            registerAgentIdentity(harness)
            registerSessionIdentity(harness, testSession1)
            registerSessionIdentity(harness, testSession2)

            // ACT
            harness.store.dispatch("core", Action(
                ActionRegistry.Names.AGENT_DELETE,
                buildJsonObject { put("agentId", agentId) }
            ))

            // ASSERT: Delete message dispatched for each avatar
            val deleteActions = harness.processedActions.filter {
                it.name == ActionRegistry.Names.SESSION_DELETE_MESSAGE
            }
            val deletedMessageIds = deleteActions.map {
                it.payload?.get("messageId")?.jsonPrimitive?.content
            }.toSet()
            assertTrue(deletedMessageIds.contains("avatar-msg-1"), "Avatar in session 1 should be deleted")
            assertTrue(deletedMessageIds.contains("avatar-msg-2"), "Avatar in session 2 should be deleted")
        }
    }

    @Test
    fun `delete agent dispatches filesystem directory deletion`() = runTest {
        val harness = buildHarness()

        harness.runAndLogOnFailure {
            registerAgentIdentity(harness)

            // ACT
            harness.store.dispatch("core", Action(
                ActionRegistry.Names.AGENT_DELETE,
                buildJsonObject { put("agentId", agentId) }
            ))

            // ASSERT: Filesystem delete directory dispatched
            val fsDeleteAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.FILESYSTEM_DELETE_DIRECTORY
            }
            assertNotNull(fsDeleteAction, "FILESYSTEM_DELETE_DIRECTORY should be dispatched")
            assertEquals(
                agentId,
                fsDeleteAction.payload?.get("path")?.jsonPrimitive?.content,
                "Should delete the agent's UUID directory"
            )
        }
    }

    @Test
    fun `delete agent removes agent from state via CONFIRM_DELETE`() = runTest {
        val harness = buildHarness()

        harness.runAndLogOnFailure {
            registerAgentIdentity(harness)

            // ACT
            harness.store.dispatch("core", Action(
                ActionRegistry.Names.AGENT_DELETE,
                buildJsonObject { put("agentId", agentId) }
            ))

            // ASSERT: CONFIRM_DELETE dispatched and agent removed from state
            val confirmAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.AGENT_CONFIRM_DELETE
            }
            assertNotNull(confirmAction, "AGENT_CONFIRM_DELETE should be dispatched")

            val finalState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
            assertNull(finalState.agents[agentUUID], "Agent should be removed from state after CONFIRM_DELETE")
        }
    }

    @Test
    fun `delete agent unregisters identity`() = runTest {
        val harness = buildHarness()

        harness.runAndLogOnFailure {
            registerAgentIdentity(harness)

            // ACT
            harness.store.dispatch("core", Action(
                ActionRegistry.Names.AGENT_DELETE,
                buildJsonObject { put("agentId", agentId) }
            ))

            // ASSERT: Identity unregistration dispatched
            val unregisterAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.CORE_UNREGISTER_IDENTITY
            }
            assertNotNull(unregisterAction, "CORE_UNREGISTER_IDENTITY should be dispatched")
            assertEquals(
                agent.identity.handle,
                unregisterAction.payload?.get("handle")?.jsonPrimitive?.content,
                "Should unregister the agent's identity handle"
            )
        }
    }

    // ========================================================================
    // SESSION DELETION IMPACT ON AGENTS
    // ========================================================================

    @Test
    fun `session deletion removes session from agent subscriptions`() = runTest {
        val harness = buildHarness()

        harness.runAndLogOnFailure {
            // ACT: Simulate session deletion broadcast (with proper UUID)
            harness.store.dispatch("session", Action(
                ActionRegistry.Names.SESSION_SESSION_DELETED,
                buildJsonObject {
                    put("sessionUUID", sessionUUID1)
                }
            ))

            // ASSERT: Agent no longer subscribed to deleted session
            val finalState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
            val agentInstance = finalState.agents[agentUUID]
            assertNotNull(agentInstance, "Agent should still exist")
            assertFalse(
                agentInstance.subscribedSessionIds.contains(IdentityUUID(sessionUUID1)),
                "Agent should no longer be subscribed to the deleted session"
            )
            assertTrue(
                agentInstance.subscribedSessionIds.contains(IdentityUUID(sessionUUID2)),
                "Agent should still be subscribed to the remaining session"
            )
        }
    }

    @Test
    fun `session deletion cleans up avatar cards for deleted session`() = runTest {
        val avatarCards: Map<IdentityUUID, Map<IdentityUUID, String>> = mapOf(
            agentUUID to mapOf(
                IdentityUUID(sessionUUID1) to "avatar-in-deleted-session",
                IdentityUUID(sessionUUID2) to "avatar-in-remaining-session"
            )
        )
        val harness = buildHarness(agentAvatarCardIds = avatarCards)

        harness.runAndLogOnFailure {
            // ACT
            harness.store.dispatch("session", Action(
                ActionRegistry.Names.SESSION_SESSION_DELETED,
                buildJsonObject {
                    put("sessionUUID", sessionUUID1)
                }
            ))

            // ASSERT: Avatar card map no longer has the deleted session
            val finalState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
            val agentCards = finalState.agentAvatarCardIds[agentUUID]
            assertNotNull(agentCards, "Agent should still have avatar cards tracked")
            assertNull(
                agentCards[IdentityUUID(sessionUUID1)],
                "Avatar card for deleted session should be removed"
            )
            assertEquals(
                "avatar-in-remaining-session",
                agentCards[IdentityUUID(sessionUUID2)],
                "Avatar card for remaining session should be preserved"
            )
        }
    }

    @Test
    fun `session deletion sets agentsToPersist flag`() = runTest {
        val harness = buildHarness()

        harness.runAndLogOnFailure {
            // ACT
            harness.store.dispatch("session", Action(
                ActionRegistry.Names.SESSION_SESSION_DELETED,
                buildJsonObject {
                    put("sessionUUID", sessionUUID1)
                }
            ))

            // ASSERT: Persistence flag set for affected agent
            val finalState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
            assertNotNull(finalState.agentsToPersist, "agentsToPersist should be set after session deletion")
            assertTrue(
                finalState.agentsToPersist!!.contains(agentUUID),
                "Affected agent should be flagged for persistence"
            )
        }
    }

    @Test
    fun `session deletion clears outputSessionId if it was the deleted session`() = runTest {
        val agentWithOutput = testAgent(
            id = agentId,
            name = "Lifecycle Agent",
            modelProvider = "mock",
            modelName = "mock-model",
            // subscribedSessionIds is intentionally empty — we only care about
            // outputSessionId being cleared. If subscriptions existed, Vanilla's
            // validateConfig would auto-assign outputSessionId to the first remaining
            // subscription, defeating the purpose of this test.
            subscribedSessionIds = emptyList(),
            privateSessionId = sessionUUID1  // outputSessionId = session being deleted
        )

        val harness = buildHarness(
            agents = mapOf(agentUUID to agentWithOutput)
        )

        harness.runAndLogOnFailure {
            // ACT
            harness.store.dispatch("session", Action(
                ActionRegistry.Names.SESSION_SESSION_DELETED,
                buildJsonObject {
                    put("sessionUUID", sessionUUID1)
                }
            ))

            // ASSERT: outputSessionId cleared
            val finalState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
            val updatedAgent = finalState.agents[agentUUID]
            assertNotNull(updatedAgent, "Agent should still exist")
            assertNull(
                updatedAgent.outputSessionId,
                "outputSessionId should be null after its session is deleted"
            )
        }
    }

    @Test
    fun `session deletion of unrelated session does not affect agent`() = runTest {
        val harness = buildHarness()
        // Proper UUID format but not in agent's subscriptions
        val unrelatedSessionUUID = "c0000000-0000-0000-0000-000000000099"

        harness.runAndLogOnFailure {
            // ACT: Delete a session the agent is NOT subscribed to
            harness.store.dispatch("session", Action(
                ActionRegistry.Names.SESSION_SESSION_DELETED,
                buildJsonObject {
                    put("sessionUUID", unrelatedSessionUUID)
                }
            ))

            // ASSERT: Agent subscriptions unchanged
            val finalState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
            val agentInstance = finalState.agents[agentUUID]
            assertNotNull(agentInstance)
            assertEquals(2, agentInstance.subscribedSessionIds.size, "Subscriptions should not change")
        }
    }
}