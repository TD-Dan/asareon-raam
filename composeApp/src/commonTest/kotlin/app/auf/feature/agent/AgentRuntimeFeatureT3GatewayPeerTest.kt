package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.PrivateDataEnvelope
import app.auf.core.generated.ActionNames
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreState
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*
import app.auf.core.Feature
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class AgentRuntimeFeatureT3GatewayPeerTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private lateinit var harness: app.auf.test.TestHarness
    private lateinit var feature: AgentRuntimeFeature
    private lateinit var platform: FakePlatformDependencies
    private val agent = AgentInstance("agent-1", "Test", "", "test-provider", "test-model", subscribedSessionIds = listOf("session-1"))

    // Fake KnowledgeGraphFeature omitted for brevity (same as T2)

    @BeforeTest
    fun setup() {
        platform = FakePlatformDependencies("test")
        feature = AgentRuntimeFeature(platform, scope)
        harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent.id to agent)))
            .build(platform = platform)
    }

    @Test
    fun `full cognitive cycle completes and sets agent to IDLE on success`() = runTest {
        val triggerAction = Action(ActionNames.AGENT_INITIATE_TURN, buildJsonObject { put("agentId", agent.id) })
        harness.store.dispatch("ui", triggerAction)

        val stateAfterTrigger = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
        // ASSERT status
        assertEquals(AgentStatus.PROCESSING, stateAfterTrigger.agentStatuses[agent.id]?.status)

        // Mock response sequence...
        val ledgerEnvelope = PrivateDataEnvelope(ActionNames.Envelopes.SESSION_RESPONSE_LEDGER, buildJsonObject {
            put("correlationId", agent.id); put("messages", buildJsonArray { })
        })
        feature.onPrivateData(ledgerEnvelope, harness.store)

        val gatewayEnvelope = PrivateDataEnvelope(ActionNames.Envelopes.GATEWAY_RESPONSE, buildJsonObject {
            put("correlationId", agent.id); put("rawContent", "Success")
        })
        feature.onPrivateData(gatewayEnvelope, harness.store)

        val finalState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
        // ASSERT final status
        assertEquals(AgentStatus.IDLE, finalState.agentStatuses[agent.id]?.status)
    }

    @Test
    fun `full cognitive cycle transitions agent to ERROR on gateway failure`() = runTest {
        harness.store.dispatch("ui", Action(ActionNames.AGENT_INITIATE_TURN, buildJsonObject { put("agentId", agent.id) }))
        val envelope = PrivateDataEnvelope(ActionNames.Envelopes.GATEWAY_RESPONSE, buildJsonObject {
            put("correlationId", agent.id); put("errorMessage", "Fail")
        })
        feature.onPrivateData(envelope, harness.store)

        val finalState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
        assertEquals(AgentStatus.ERROR, finalState.agentStatuses[agent.id]?.status)
        assertEquals("[AGENT ERROR] Generation failed: Fail", finalState.agentStatuses[agent.id]?.errorMessage)
    }
}