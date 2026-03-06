package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.IdentityUUID
import app.auf.core.generated.ActionRegistry
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreState
import app.auf.feature.filesystem.FileSystemFeature
import app.auf.feature.session.SessionFeature
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Tier 3 Peer Test for Agent <-> Gateway interaction.
 *
 * Verifies:
 * 1. Successful generation flow (Processing -> Idle).
 * 2. Error handling flow (Processing -> Error).
 * 3. Output sanitization (Sentinel) logic.
 */
class AgentRuntimeFeatureT3GatewayPeerTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private lateinit var harness: app.auf.test.TestHarness
    private lateinit var feature: AgentRuntimeFeature
    private lateinit var platform: FakePlatformDependencies

    // All IDs must be valid UUIDs
    private val agentUUID = "b0000000-0000-0000-0000-000000000001"
    private val sessionUUID = "a0000000-0000-0000-0000-000000000001"
    private val session = testSession(sessionUUID, "Test Session")

    private val agent = testAgent(agentUUID, "Test", modelProvider = "test-provider", modelName = "test-model",
        subscribedSessionIds = listOf(sessionUUID),
        resources = mapOf("system_instruction" to "res-sys-instruction-v1")
    )
    private val agentIdentityUUID = IdentityUUID(agentUUID)

    @BeforeTest
    fun setup() {
        platform = FakePlatformDependencies("test")
        feature = AgentRuntimeFeature(platform, scope)
        harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(FileSystemFeature(platform))
            .withFeature(SessionFeature(platform, scope))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(agentIdentityUUID to agent),
                resources = testBuiltInResources()
            ))
            .build(platform = platform)
    }

    @Test
    fun `full cognitive cycle completes and sets agent to IDLE on success`() = runTest {
        harness.runAndLogOnFailure {
            // Register identities for resolveAgentId and session UUID validation
            harness.registerAgentIdentity(agent)
            harness.registerSessionIdentity(session)

            val triggerAction = Action(ActionRegistry.Names.AGENT_INITIATE_TURN, buildJsonObject { put("agentId", agent.identity.uuid) })
            harness.store.dispatch("core", triggerAction)

            val stateAfterTrigger = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
            // ASSERT status
            assertEquals(AgentStatus.PROCESSING, stateAfterTrigger.agentStatuses[agentIdentityUUID]?.status)

            // Mock response sequence...
            harness.store.dispatch("session", Action(
                name = ActionRegistry.Names.SESSION_RETURN_LEDGER,
                payload = buildJsonObject {
                    put("correlationId", agent.identity.uuid); put("messages", buildJsonArray { })
                },
                targetRecipient = "agent"
            ))

            harness.store.dispatch("gateway", Action(
                name = ActionRegistry.Names.GATEWAY_RETURN_RESPONSE,
                payload = buildJsonObject {
                    put("correlationId", agent.identity.uuid); put("rawContent", "Success")
                },
                targetRecipient = "agent"
            ))

            val finalState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
            // ASSERT final status
            assertEquals(AgentStatus.IDLE, finalState.agentStatuses[agentIdentityUUID]?.status)
        }
    }

    @Test
    fun `full cognitive cycle transitions agent to ERROR on gateway failure`() = runTest {
        harness.runAndLogOnFailure {
            harness.registerAgentIdentity(agent)
            harness.registerSessionIdentity(session)

            harness.store.dispatch("core", Action(ActionRegistry.Names.AGENT_INITIATE_TURN, buildJsonObject { put("agentId", agent.identity.uuid) }))

            // Deliver ledger so the pipeline can progress to gateway
            harness.store.dispatch("session", Action(
                name = ActionRegistry.Names.SESSION_RETURN_LEDGER,
                payload = buildJsonObject {
                    put("correlationId", agent.identity.uuid); put("messages", buildJsonArray { })
                },
                targetRecipient = "agent"
            ))

            harness.store.dispatch("gateway", Action(
                name = ActionRegistry.Names.GATEWAY_RETURN_RESPONSE,
                payload = buildJsonObject {
                    put("correlationId", agent.identity.uuid); put("errorMessage", "Fail")
                },
                targetRecipient = "agent"
            ))

            val finalState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
            assertEquals(AgentStatus.ERROR, finalState.agentStatuses[agentIdentityUUID]?.status)
            assertEquals("[AGENT ERROR] Generation failed: Fail", finalState.agentStatuses[agentIdentityUUID]?.errorMessage)
        }
    }

    @Test
    fun `sentinel sanitizes timestamp echo out of ai response`() = runTest {
        // Setup specific state for this test (Agent is PROCESSING)
        val status = AgentStatusInfo(status = AgentStatus.PROCESSING)
        // Re-build harness to inject specific status
        harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(FileSystemFeature(platform))
            .withFeature(SessionFeature(platform, scope))
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(agentIdentityUUID to agent),
                agentStatuses = mapOf(agentIdentityUUID to status),
                resources = testBuiltInResources()
            ))
            .build(platform = platform)

        val gatewayResponsePayload = buildJsonObject {
            put("correlationId", agent.identity.uuid)
            // Simulate LLM hallucinating the header
            put("rawContent", "Test ($agentUUID) @ 2025-10-27T12:34:56Z: This is the actual response.")
        }
        harness.runAndLogOnFailure {
            // Register session identity so handleGatewayResponse can resolve session UUID → handle
            harness.registerSessionIdentity(session)

            // ACT: Deliver the response
            harness.store.dispatch("gateway", Action(
                name = ActionRegistry.Names.GATEWAY_RETURN_RESPONSE,
                payload = gatewayResponsePayload,
                targetRecipient = "agent"
            ))

            // ASSERT 1: Sentinel Warning Posted
            val sentinelAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.SESSION_POST &&
                        it.payload?.get("senderId")?.jsonPrimitive?.content == "system"
            }
            assertNotNull(sentinelAction, "A sentinel warning should have been posted by 'system'.")
            assertTrue(
                sentinelAction.payload?.get("message")?.jsonPrimitive?.content?.contains("Warning for [Test]") == true,
                "Warning message should reference the agent name."
            )

            // ASSERT 2: Cleaned Response Posted
            val agentResponseAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.SESSION_POST &&
                        it.payload?.get("senderId")?.jsonPrimitive?.content == agent.identity.handle
            }
            assertNotNull(agentResponseAction, "The agent's own response should have been posted.")
            assertEquals(
                "This is the actual response.",
                agentResponseAction.payload?.get("message")?.jsonPrimitive?.contentOrNull,
                "The timestamp header should be stripped."
            )

            // ASSERT 3: Agent reset to IDLE
            val finalState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
            assertEquals(AgentStatus.IDLE, finalState.agentStatuses[agentIdentityUUID]?.status)
        }
    }
}