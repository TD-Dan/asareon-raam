package app.auf.feature.agent

import app.auf.core.*
import app.auf.core.generated.ActionNames
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.agent.strategies.SovereignDefaults
import app.auf.test.TestEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Tier 2 Integration Test for the Cognitive Cycle.
 * Verifies the Sovereign Strategy integration:
 * 1. Booting Phase (Sentinel Injection)
 * 2. Gateway Processing (Sentinel Validation)
 * 3. State Transition (Persistence)
 * 4. Awake Phase (Normal Operation)
 */
class AgentRuntimeFeatureT2CognitiveCycleTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val platform = FakePlatformDependencies("test")
    private val feature = AgentRuntimeFeature(platform, scope)
    private val json = Json { ignoreUnknownKeys = true }

    // Setup a Sovereign Agent in Booting Phase
    private val agentId = "agent-sovereign"
    private val agent = AgentInstance(
        id = agentId,
        name = "Sovereign Agent",
        modelProvider = "mock",
        modelName = "mock-gpt",
        cognitiveStrategyId = "sovereign_v1",
        cognitiveState = buildJsonObject { put("phase", "BOOTING") },
        subscribedSessionIds = listOf("session-1")
    )

    // Minimal valid ledger response
    private val ledgerResponse = PrivateDataEnvelope(ActionNames.Envelopes.SESSION_RESPONSE_LEDGER, buildJsonObject {
        put("correlationId", agentId)
        put("messages", buildJsonArray {
            add(buildJsonObject { put("senderId", "user"); put("rawContent", "Hello"); put("timestamp", 1000L) })
        })
    })

    @Test
    fun `full cognitive boot cycle`() = runTest {
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agentId to agent)))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // 1. INITIATE TURN (Trigger Boot)
            harness.store.dispatch("ui", Action(ActionNames.AGENT_INITIATE_TURN, buildJsonObject { put("agentId", agentId) }))

            // Deliver Ledger Data (Mocking Session Feature Response)
            // This triggers the Pipeline -> Evaluate -> Gateway Request
            feature.onPrivateData(ledgerResponse, harness.store)

            // ASSERT 1: Sentinel XML injected into System Prompt
            val gatewayAction = harness.processedActions.find { it.name == ActionNames.GATEWAY_GENERATE_CONTENT }
            assertNotNull(gatewayAction, "Gateway Request should be dispatched")
            val prompt = gatewayAction.payload?.get("systemPrompt")?.jsonPrimitive?.content ?: ""
            assertTrue(prompt.contains(SovereignDefaults.BOOT_SENTINEL_XML), "Prompt must contain Sentinel in BOOTING phase")

            // 2. SIMULATE GATEWAY RESPONSE (Success)
            val successResponse = PrivateDataEnvelope(ActionNames.Envelopes.GATEWAY_RESPONSE_RESPONSE, buildJsonObject {
                put("correlationId", agentId)
                put("rawContent", "Boot sequence complete. I am ready.")
            })
            feature.onPrivateData(successResponse, harness.store)

            // ASSERT 2: State Update Action Dispatched
            val updateAction = harness.processedActions.find { it.name == ActionNames.AGENT_INTERNAL_UPDATE_COGNITIVE_STATE }
            assertNotNull(updateAction, "Should update cognitive state on sentinel success")
            val newState = updateAction.payload?.get("state")?.jsonObject
            assertEquals("AWAKE", newState?.get("phase")?.jsonPrimitive?.content)

            // ASSERT 3: Message Posted to Session
            val postAction = harness.processedActions.find { it.name == ActionNames.SESSION_POST }
            assertNotNull(postAction, "Should post the response to session")
        }
    }

    @Test
    fun `sentinel failure halts and does not post`() = runTest {
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agentId to agent)))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // 1. Simulate Gateway Response (Failure)
            // Note: We skip the initiate part and jump straight to the response handler for this test
            val failResponse = PrivateDataEnvelope(ActionNames.Envelopes.GATEWAY_RESPONSE_RESPONSE, buildJsonObject {
                put("correlationId", agentId)
                put("rawContent", "[${SovereignDefaults.SENTINEL_FAILURE_TOKEN}: NO_AGENT_PRESENT]")
            })
            feature.onPrivateData(failResponse, harness.store)

            // ASSERT: No Session Post
            val postAction = harness.processedActions.find { it.name == ActionNames.SESSION_POST }
            assertNull(postAction, "Should NOT post to session on sentinel failure")

            // ASSERT: No State Update (remains BOOTING)
            val updateAction = harness.processedActions.find { it.name == ActionNames.AGENT_INTERNAL_UPDATE_COGNITIVE_STATE }
            assertNull(updateAction, "Should NOT update state on failure")
        }
    }
}