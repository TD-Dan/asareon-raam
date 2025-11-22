package app.auf.feature.agent

import app.auf.core.*
import app.auf.core.generated.ActionNames
import app.auf.fakes.FakePlatformDependencies
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
    fun `handleLedgerResponse stages context and calls evaluateTurnContext`() = runTest {
        // This test verifies the race condition fix.
        // The pipeline handles the response by staging context.
        // The feature's onAction handler picks up the STAGE_TURN_CONTEXT action and calls evaluateTurnContext.
        // This ensures sequential consistency.

        val harness = TestEnvironment.create()
            .withFeature(feature)
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

            // ASSERT 2: GATEWAY_GENERATE_CONTENT dispatched (proving evaluateTurnContext was called)
            // This proves the loop: PrivateData -> Pipeline -> Action -> Feature.onAction -> Pipeline.evaluate -> Action
            val gatewayAction = harness.processedActions.find { it.name == ActionNames.GATEWAY_GENERATE_CONTENT }
            assertNotNull(gatewayAction)
        }
    }

    @Test
    fun `sentinel warning does not trigger new turn`() = runTest {
        // Verifies the Sentinel Fix in a full loop context
        val harness = TestEnvironment.create()
            .withFeature(feature)
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
}