package app.auf.feature.agent

import app.auf.core.*
import app.auf.core.generated.ActionNames
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.filesystem.FileSystemFeature
import app.auf.test.TestEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Tier 2/3 Test for AgentCognitivePipeline (The Thinker).
 * Replaces the monolithic T2CoreTest.
 * Verifies the Cognitive Cycle: Ledger -> Context -> Prompt -> Gateway
 */
class AgentRuntimeFeatureT2T3ThinkerTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val platform = FakePlatformDependencies("test")
    private val feature = AgentRuntimeFeature(platform, scope)
    private val agent = AgentInstance("agent-1", "Test", "", "p", "m", subscribedSessionIds = listOf("session-1"))

    @Test
    fun `INITIATE_TURN dispatches REQUEST_LEDGER_CONTENT`() = runTest {
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(FileSystemFeature(platform))
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent.id to agent)))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            val action = Action(ActionNames.AGENT_INITIATE_TURN, buildJsonObject { put("agentId", agent.id) })
            harness.store.dispatch("ui", action)

            val request = harness.processedActions.find { it.name == ActionNames.SESSION_REQUEST_LEDGER_CONTENT }
            assertNotNull(request)
            assertEquals("agent-1", request.payload?.get("correlationId")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `handleLedgerResponse stages context and triggers full context gathering`() = runTest {
        // This test verifies the full pipeline from ledger response to gateway request.
        // The flow is: LedgerResponse → STAGE_TURN_CONTEXT → evaluateTurnContext
        // → FILESYSTEM_SYSTEM_LIST → FileSystemFeature → SET_WORKSPACE_CONTEXT
        // → evaluateFullContext (gate) → executeTurn → GATEWAY_GENERATE_CONTENT

        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(FileSystemFeature(platform))
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent.id to agent)))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            val response = PrivateDataEnvelope(ActionNames.Envelopes.SESSION_RESPONSE_LEDGER, buildJsonObject {
                put("correlationId", agent.id)
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("senderId", "user")
                        put("rawContent", "Hello")
                        put("timestamp", 1000L)
                    })
                })
            })

            // ACT
            feature.onPrivateData(response, harness.store)

            // ASSERT 1: STAGE_TURN_CONTEXT dispatched
            val stageAction = harness.processedActions.find { it.name == ActionNames.AGENT_INTERNAL_STAGE_TURN_CONTEXT }
            assertNotNull(stageAction)

            // ASSERT 2: GATEWAY_GENERATE_CONTENT dispatched (proving full context pipeline completed)
            // This proves the loop: PrivateData -> Pipeline -> STAGE_TURN_CONTEXT -> evaluateTurnContext
            // -> FILESYSTEM_SYSTEM_LIST -> FileSystemFeature -> SET_WORKSPACE_CONTEXT -> evaluateFullContext -> executeTurn
            val gatewayAction = harness.processedActions.find { it.name == ActionNames.GATEWAY_GENERATE_CONTENT }
            assertNotNull(gatewayAction)
        }
    }

    @Test
    fun `sentinel warning does not trigger new turn`() = runTest {
        // Verifies the Sentinel Fix in a full loop context
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(FileSystemFeature(platform))
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent.id to agent)))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            val sentinelMsg = Action(ActionNames.SESSION_PUBLISH_MESSAGE_POSTED, buildJsonObject {
                put("sessionId", "session-1")
                put("entry", buildJsonObject {
                    put("id", "msg-1")
                    put("senderId", "system") // <--- Sentinel
                    put("timestamp", 1000L)
                })
            })

            // ACT
            harness.store.dispatch("session", sentinelMsg)

            // ASSERT: Agent status remains IDLE (null/default)
            val state = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
            val status = state.agentStatuses[agent.id]?.status ?: AgentStatus.IDLE
            assertEquals(AgentStatus.IDLE, status)
        }
    }
    /* ADD TO: commonTest\kotlin\app\auf\feature\agent\AgentRuntimeFeatureT2T3ThinkerTest.kt */

    @Test
    fun `startCognitiveCycle should fail gracefully and set ERROR status if agent has no sessions`() = runTest {
        // ARRANGE
        val orphanAgent = AgentInstance("orphan-1", "Orphan", "", "p", "m", subscribedSessionIds = emptyList(), privateSessionId = null)
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(FileSystemFeature(platform))
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(orphanAgent.id to orphanAgent)))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // ACT
            harness.store.dispatch("ui", Action(ActionNames.AGENT_INITIATE_TURN, buildJsonObject {
                put("agentId", orphanAgent.id)
            }))

            // ASSERT
            val state = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
            val status = state.agentStatuses[orphanAgent.id]

            // Should be ERROR status
            assertEquals(AgentStatus.ERROR, status?.status)
            // Should have descriptive error message
            assertTrue(status?.errorMessage?.contains("no session") == true)
            // Should NOT have dispatched a ledger request
            assertNull(harness.processedActions.find { it.name == ActionNames.SESSION_REQUEST_LEDGER_CONTENT })
        }
    }

    @Test
    fun `handleLedgerResponse should handle malformed JSON gracefully`() = runTest {
        // ARRANGE
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(FileSystemFeature(platform))
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent.id to agent)))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // ACT: Send malformed payload in envelope
            val malformedResponse = PrivateDataEnvelope(ActionNames.Envelopes.SESSION_RESPONSE_LEDGER, buildJsonObject {
                put("correlationId", agent.id)
                // Missing "messages" array, or other schema violation
                put("invalid_key", "invalid_value")
            })

            feature.onPrivateData(malformedResponse, harness.store)

            // ASSERT
            val state = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
            val status = state.agentStatuses[agent.id]

            // Should transition to ERROR
            assertEquals(AgentStatus.ERROR, status?.status)
            assertTrue(status?.errorMessage?.contains("Failed to parse ledger") == true)
        }
    }

    @Test
    fun `evaluateFullContext should abort if staged ledger context is missing (State Integrity)`() = runTest {
        // ARRANGE
        // Status has NO stagedTurnContext, simulating a desync or cancellation
        val status = AgentStatusInfo(status = AgentStatus.PROCESSING, stagedTurnContext = null)
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(FileSystemFeature(platform))
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(agent.id to agent),
                agentStatuses = mapOf(agent.id to status)
            ))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // ACT: Trigger a context arrival (e.g. workspace or HKG) without staged ledger
            val hkgResponse = PrivateDataEnvelope(ActionNames.Envelopes.KNOWLEDGEGRAPH_RESPONSE_CONTEXT, buildJsonObject {
                put("correlationId", agent.id)
                put("context", buildJsonObject { put("some", "data") })
            })
            feature.onPrivateData(hkgResponse, harness.store)

            // ASSERT
            val state = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
            val finalStatus = state.agentStatuses[agent.id]

            // Should abort and set ERROR
            assertEquals(AgentStatus.ERROR, finalStatus?.status)
            assertTrue(finalStatus?.errorMessage?.contains("Context assembly failed") == true)

            // Should NOT proceed to Gateway
            assertNull(harness.processedActions.find { it.name == ActionNames.GATEWAY_GENERATE_CONTENT })
        }
    }
}