package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.generated.ActionNames
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreState
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import app.auf.util.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Tier 2/3 Workflow Tests for AgentRuntimeFeature.
 * These tests verify the full, asynchronous, multi-feature cognitive cycle by simulating
 * the private data responses from SessionFeature and GatewayFeature.
 */
class AgentRuntimeFeatureWorkflowTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private lateinit var harness: app.auf.test.TestHarness
    private lateinit var feature: AgentRuntimeFeature
    private lateinit var platform: FakePlatformDependencies
    private val agent = AgentInstance("agent-1", "Test", "", "test-provider", "test-model", "session-1")


    @BeforeTest
    fun setup() {
        platform = FakePlatformDependencies("test")
        feature = AgentRuntimeFeature(platform, scope)
        // Build a harness with only the Agent feature. We will simulate other features' responses.
        harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent.id to agent)))
            .build(platform = platform)
    }

    @Test
    fun `full cognitive cycle completes and sets agent to IDLE on success`() = runTest {
        // --- PHASE 1: Trigger the cycle ---
        val triggerAction = Action(ActionNames.AGENT_TRIGGER_MANUAL_TURN, buildJsonObject { put("agentId", agent.id) })
        harness.store.dispatch("ui", triggerAction)

        // Assert initial state transition
        val stateAfterTrigger = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
        assertEquals(AgentStatus.PROCESSING, stateAfterTrigger.agents[agent.id]?.status)

        // --- PHASE 2: Simulate SessionFeature responding with ledger content ---
        val ledgerResponse = buildJsonObject {
            put("correlationId", agent.id)
            put("messages", buildJsonArray { /* empty for this test */ })
        }
        feature.onPrivateData(ledgerResponse, harness.store)

        // Assert that the feature requested generation from the gateway
        val gatewayRequest = harness.processedActions.find { it.name == ActionNames.GATEWAY_GENERATE_CONTENT }
        assertNotNull(gatewayRequest, "Agent should have dispatched a request to the gateway.")

        // --- PHASE 3: Simulate GatewayFeature responding with a successful result ---
        val gatewaySuccessResponse = buildJsonObject {
            put("correlationId", agent.id)
            put("rawContent", "This is the successful response.")
        }
        feature.onPrivateData(gatewaySuccessResponse, harness.store)

        // --- FINAL ASSERTION: The agent should be IDLE ---
        val finalState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
        assertEquals(AgentStatus.IDLE, finalState.agents[agent.id]?.status, "Agent should return to IDLE after a successful cycle.")
    }

    @Test
    fun `full cognitive cycle transitions agent to ERROR on gateway failure`() = runTest {
        // --- PHASE 1 & 2: Trigger and get ledger ---
        harness.store.dispatch("ui", Action(ActionNames.AGENT_TRIGGER_MANUAL_TURN, buildJsonObject { put("agentId", agent.id) }))
        val ledgerResponse = buildJsonObject {
            put("correlationId", agent.id)
            put("messages", buildJsonArray {})
        }
        feature.onPrivateData(ledgerResponse, harness.store)

        // --- PHASE 3: Simulate GatewayFeature responding with an EXPLICIT error ---
        val gatewayErrorResponse = buildJsonObject {
            put("correlationId", agent.id)
            put("errorMessage", "API key invalid.")
        }
        feature.onPrivateData(gatewayErrorResponse, harness.store)

        // --- FINAL ASSERTION: The agent should be in ERROR state ---
        val finalState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
        assertEquals(AgentStatus.ERROR, finalState.agents[agent.id]?.status, "Agent should be in ERROR state after a reported gateway failure.")
        assertEquals("[AGENT ERROR] Generation failed: API key invalid.", finalState.agents[agent.id]?.errorMessage)
    }

    @Test
    fun `full cognitive cycle transitions agent to ERROR on corrupted gateway response`() = runTest {
        // --- PHASE 1 & 2: Trigger and get ledger ---
        harness.store.dispatch("ui", Action(ActionNames.AGENT_TRIGGER_MANUAL_TURN, buildJsonObject { put("agentId", agent.id) }))
        val ledgerResponse = buildJsonObject {
            put("correlationId", agent.id)
            put("messages", buildJsonArray {})
        }
        feature.onPrivateData(ledgerResponse, harness.store)

        // --- PHASE 3: Simulate GatewayFeature responding with CORRUPTED JSON ---
        val gatewayMismatchedResponse = buildJsonObject {
            put("correlationId", agent.id)
            put("unexpected_key", "some_value")
        }


        feature.onPrivateData(gatewayMismatchedResponse, harness.store)

        // --- FINAL ASSERTION: The agent should be in ERROR state and a FATAL error logged ---
        val finalState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
        assertEquals(AgentStatus.ERROR, finalState.agents[agent.id]?.status, "Agent should be in ERROR state after a corrupted gateway response.")
        assertEquals("FATAL: Could not parse gateway response.", finalState.agents[agent.id]?.errorMessage)

        val log = platform.capturedLogs.find { it.level == LogLevel.ERROR }
        assertNotNull(log, "A fatal error should have been logged.")
        assertTrue(log.message.contains("FATAL: Failed to parse gateway response for agent 'agent-1'"))
    }
}