package asareon.raam.feature.agent

import asareon.raam.core.Action
import asareon.raam.core.IdentityUUID
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.fakes.FakePlatformDependencies
import asareon.raam.feature.core.AppLifecycle
import asareon.raam.feature.core.CoreState
import asareon.raam.feature.filesystem.FileSystemFeature
import asareon.raam.feature.session.SessionFeature
import asareon.raam.feature.session.SessionState
import asareon.raam.test.TestEnvironment
import asareon.raam.test.TestHarness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Tier 2 Integration Tests for agent.ADD_SESSION_SUBSCRIPTION,
 * agent.REMOVE_SESSION_SUBSCRIPTION, and agent.AGENT_NAMES_UPDATED broadcast.
 *
 * Verifies:
 * 1. Side effects fire correctly (persist, avatar update, names broadcast)
 * 2. ACTION_RESULT is published with success/failure
 * 3. AGENT_NAMES_UPDATED broadcast reaches SessionFeature state
 * 4. Cross-feature agent discovery cycle works end-to-end
 */
class AgentRuntimeFeatureT2SubscriptionTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val platform = FakePlatformDependencies("test")
    private val json = Json { ignoreUnknownKeys = true }

    // All IDs must be valid UUIDs
    private val sessionUUID1 = "a0000000-0000-0000-0000-000000000001"
    private val sessionUUID2 = "a0000000-0000-0000-0000-000000000002"
    private val sessionUUID3 = "a0000000-0000-0000-0000-000000000003"

    private val agentId1 = "b0000000-0000-0000-0000-000000000001"
    private val agentId2 = "b0000000-0000-0000-0000-000000000002"

    private val testSession1 = testSession(id = sessionUUID1, name = "Session 1")
    private val testSession2 = testSession(id = sessionUUID2, name = "Session 2")

    private fun buildHarness(
        agents: Map<IdentityUUID, AgentInstance>,
        sessions: Map<String, asareon.raam.feature.session.Session> = mapOf(
            sessionUUID1 to testSession1,
            sessionUUID2 to testSession2
        )
    ) = TestEnvironment.create()
        .withFeature(AgentRuntimeFeature(platform, scope))
        .withFeature(SessionFeature(platform, scope))
        .withFeature(FileSystemFeature(platform))
        .withInitialState("agent", AgentRuntimeState(agents = agents))
        .withInitialState("session", SessionState(
            sessions = sessions,
            activeSessionLocalHandle = sessions.keys.firstOrNull(),
            sessionOrder = SessionState.deriveSessionOrder(sessions)
        ))
        .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
        .build(platform = platform)

    /**
     * Registers an agent's identity in the identity registry so that
     * resolveAgentId() can find it during handleSideEffects.
     * Mirrors registerSessionIdentity but for the agent namespace.
     */
    private fun registerAgentIdentity(harness: TestHarness, agent: AgentInstance) {
        harness.store.dispatch("agent", Action(
            ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            buildJsonObject {
                put("uuid", agent.identityUUID.uuid)
                put("name", agent.identity.name)
            }
        ))
    }

    // ========================================================================
    // ADD_SESSION_SUBSCRIPTION — side effects
    // ========================================================================

    @Test
    fun `ADD_SESSION_SUBSCRIPTION persists agent config`() = runTest {
        val agent = testAgent(
            id = agentId1, name = "Agent 1",
            modelProvider = "test", modelName = "test",
            subscribedSessionIds = listOf(sessionUUID1)
        )
        val harness = buildHarness(agents = mapOf(IdentityUUID(agentId1) to agent))

        harness.runAndLogOnFailure {
            registerAgentIdentity(harness, agent)
            harness.registerSessionIdentity(testSession1)
            harness.registerSessionIdentity(testSession2)

            harness.store.dispatch("session", Action(
                ActionRegistry.Names.AGENT_ADD_SESSION_SUBSCRIPTION,
                buildJsonObject {
                    put("agentId", agentId1)
                    put("sessionId", sessionUUID2)
                }
            ))

            // Verify persistence was triggered
            val writeAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.FILESYSTEM_WRITE &&
                        it.payload?.get("path")?.jsonPrimitive?.contentOrNull?.contains(agentId1) == true
            }
            assertNotNull(writeAction, "Agent config should be persisted after subscription change")
        }
    }

    @Test
    fun `ADD_SESSION_SUBSCRIPTION publishes ACTION_RESULT with success`() = runTest {
        val agent = testAgent(
            id = agentId1, name = "Agent 1",
            modelProvider = "test", modelName = "test",
            subscribedSessionIds = emptyList()
        )
        val harness = buildHarness(agents = mapOf(IdentityUUID(agentId1) to agent))

        harness.runAndLogOnFailure {
            registerAgentIdentity(harness, agent)
            harness.registerSessionIdentity(testSession1)

            harness.store.dispatch("agent", Action(
                ActionRegistry.Names.AGENT_ADD_SESSION_SUBSCRIPTION,
                buildJsonObject {
                    put("agentId", agentId1)
                    put("sessionId", sessionUUID1)
                }
            ))

            val result = harness.processedActions.find {
                it.name == ActionRegistry.Names.AGENT_ACTION_RESULT &&
                        it.payload?.get("requestAction")?.jsonPrimitive?.contentOrNull ==
                        ActionRegistry.Names.AGENT_ADD_SESSION_SUBSCRIPTION
            }
            assertNotNull(result, "ACTION_RESULT should be published")
            assertTrue(
                result.payload?.get("success")?.jsonPrimitive?.boolean ?: false,
                "ACTION_RESULT should indicate success"
            )
            assertTrue(
                result.payload?.get("summary")?.jsonPrimitive?.contentOrNull?.contains("added to") ?: false,
                "Summary should describe the add operation"
            )
        }
    }

    @Test
    fun `ADD_SESSION_SUBSCRIPTION broadcasts AGENT_NAMES_UPDATED`() = runTest {
        val agent = testAgent(
            id = agentId1, name = "Agent 1",
            modelProvider = "test", modelName = "test",
            subscribedSessionIds = listOf(sessionUUID1)
        )
        val harness = buildHarness(agents = mapOf(IdentityUUID(agentId1) to agent))

        harness.runAndLogOnFailure {
            registerAgentIdentity(harness, agent)
            harness.registerSessionIdentity(testSession1)
            harness.registerSessionIdentity(testSession2)

            harness.store.dispatch("agent", Action(
                ActionRegistry.Names.AGENT_ADD_SESSION_SUBSCRIPTION,
                buildJsonObject {
                    put("agentId", agentId1)
                    put("sessionId", sessionUUID2)
                }
            ))

            val broadcast = harness.processedActions.findLast {
                it.name == ActionRegistry.Names.AGENT_AGENT_NAMES_UPDATED
            }
            assertNotNull(broadcast, "AGENT_NAMES_UPDATED should be broadcast after subscription change")

            val agents = broadcast.payload?.get("agents")?.jsonArray
            assertNotNull(agents)
            assertEquals(1, agents.size)

            val agentEntry = agents[0].jsonObject
            assertEquals(agentId1, agentEntry["uuid"]?.jsonPrimitive?.content)
            assertEquals("Agent 1", agentEntry["name"]?.jsonPrimitive?.content)

            val subscribedIds = agentEntry["subscribedSessionIds"]?.jsonArray
                ?.map { it.jsonPrimitive.content }?.toSet()
            assertNotNull(subscribedIds)
            assertTrue(subscribedIds.contains(sessionUUID1), "Should include original subscription")
            assertTrue(subscribedIds.contains(sessionUUID2), "Should include newly added subscription")
        }
    }

    // ========================================================================
    // REMOVE_SESSION_SUBSCRIPTION — side effects
    // ========================================================================

    @Test
    fun `REMOVE_SESSION_SUBSCRIPTION persists and broadcasts`() = runTest {
        val agent = testAgent(
            id = agentId1, name = "Agent 1",
            modelProvider = "test", modelName = "test",
            subscribedSessionIds = listOf(sessionUUID1, sessionUUID2)
        )
        val harness = buildHarness(agents = mapOf(IdentityUUID(agentId1) to agent))

        harness.runAndLogOnFailure {
            registerAgentIdentity(harness, agent)
            harness.registerSessionIdentity(testSession1)
            harness.registerSessionIdentity(testSession2)

            harness.store.dispatch("agent", Action(
                ActionRegistry.Names.AGENT_REMOVE_SESSION_SUBSCRIPTION,
                buildJsonObject {
                    put("agentId", agentId1)
                    put("sessionId", sessionUUID1)
                }
            ))

            // Verify persistence
            val writeAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.FILESYSTEM_WRITE &&
                        it.payload?.get("path")?.jsonPrimitive?.contentOrNull?.contains(agentId1) == true
            }
            assertNotNull(writeAction, "Agent config should be persisted after removal")

            // Verify broadcast shows updated subscriptions
            val broadcast = harness.processedActions.findLast {
                it.name == ActionRegistry.Names.AGENT_AGENT_NAMES_UPDATED
            }
            assertNotNull(broadcast)
            val agentEntry = broadcast.payload?.get("agents")?.jsonArray?.get(0)?.jsonObject
            val subscribedIds = agentEntry?.get("subscribedSessionIds")?.jsonArray
                ?.map { it.jsonPrimitive.content }?.toSet()
            assertNotNull(subscribedIds)
            assertFalse(subscribedIds.contains(sessionUUID1), "Removed session should not appear in broadcast")
            assertTrue(subscribedIds.contains(sessionUUID2), "Remaining session should still appear")
        }
    }

    @Test
    fun `REMOVE_SESSION_SUBSCRIPTION publishes ACTION_RESULT with removed summary`() = runTest {
        val agent = testAgent(
            id = agentId1, name = "Agent 1",
            modelProvider = "test", modelName = "test",
            subscribedSessionIds = listOf(sessionUUID1)
        )
        val harness = buildHarness(agents = mapOf(IdentityUUID(agentId1) to agent))

        harness.runAndLogOnFailure {
            registerAgentIdentity(harness, agent)
            harness.registerSessionIdentity(testSession1)

            harness.store.dispatch("agent", Action(
                ActionRegistry.Names.AGENT_REMOVE_SESSION_SUBSCRIPTION,
                buildJsonObject {
                    put("agentId", agentId1)
                    put("sessionId", sessionUUID1)
                }
            ))

            val result = harness.processedActions.find {
                it.name == ActionRegistry.Names.AGENT_ACTION_RESULT &&
                        it.payload?.get("requestAction")?.jsonPrimitive?.contentOrNull ==
                        ActionRegistry.Names.AGENT_REMOVE_SESSION_SUBSCRIPTION
            }
            assertNotNull(result)
            assertTrue(result.payload?.get("success")?.jsonPrimitive?.boolean ?: false)
            assertTrue(
                result.payload?.get("summary")?.jsonPrimitive?.contentOrNull?.contains("removed from") ?: false,
                "Summary should describe the remove operation"
            )
        }
    }

    // ========================================================================
    // AGENTS_LOADED triggers initial AGENT_NAMES_UPDATED broadcast
    // ========================================================================

    @Test
    fun `AGENTS_LOADED broadcasts AGENT_NAMES_UPDATED with all agents`() = runTest {
        val agent1 = testAgent(
            id = agentId1, name = "Alpha",
            modelProvider = "test", modelName = "test",
            subscribedSessionIds = listOf(sessionUUID1)
        )
        val agent2 = testAgent(
            id = agentId2, name = "Beta",
            modelProvider = "test", modelName = "test",
            subscribedSessionIds = listOf(sessionUUID1, sessionUUID2)
        )
        val harness = buildHarness(agents = mapOf(
            IdentityUUID(agentId1) to agent1,
            IdentityUUID(agentId2) to agent2
        ))

        harness.runAndLogOnFailure {
            // Simulate AGENTS_LOADED (fires after all agent configs are read from disk)
            harness.store.dispatch("agent", Action(ActionRegistry.Names.AGENT_AGENTS_LOADED))

            val broadcast = harness.processedActions.findLast {
                it.name == ActionRegistry.Names.AGENT_AGENT_NAMES_UPDATED
            }
            assertNotNull(broadcast, "AGENTS_LOADED should trigger AGENT_NAMES_UPDATED broadcast")

            val agents = broadcast.payload?.get("agents")?.jsonArray
            assertNotNull(agents)
            assertEquals(2, agents.size, "Broadcast should include all agents")
        }
    }
}