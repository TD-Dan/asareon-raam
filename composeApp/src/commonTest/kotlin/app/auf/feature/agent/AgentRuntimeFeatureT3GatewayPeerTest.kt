package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.PrivateDataEnvelope
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
import kotlinx.serialization.json.put
import kotlin.test.*
import app.auf.core.Feature

class AgentRuntimeFeatureT3GatewayPeerTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private lateinit var harness: app.auf.test.TestHarness
    private lateinit var feature: AgentRuntimeFeature
    private lateinit var platform: FakePlatformDependencies
    private val agent = AgentInstance("agent-1", "Test", "", "test-provider", "test-model", "session-1")

    // FIX: A fake KnowledgeGraphFeature to unblock the cognitive cycle in tests.
    private object FakeKnowledgeGraphFeature : Feature {
        override val name: String = "knowledgegraph"
        override val composableProvider: Feature.ComposableProvider? = null
        override fun onPrivateData(envelope: PrivateDataEnvelope, store: app.auf.core.Store) {
            if (envelope.type == ActionNames.Envelopes.AGENT_REQUEST_CONTEXT) {
                val correlationId = envelope.payload["correlationId"]?.jsonPrimitive?.contentOrNull ?: return
                val responsePayload = buildJsonObject {
                    put("correlationId", correlationId)
                    put("context", buildJsonObject {}) // Respond with empty context
                }
                store.deliverPrivateData(
                    this.name,
                    "agent",
                    PrivateDataEnvelope(ActionNames.Envelopes.KNOWLEDGEGRAPH_RESPONSE_CONTEXT, responsePayload)
                )
            }
        }
    }


    @BeforeTest
    fun setup() {
        platform = FakePlatformDependencies("test")
        feature = AgentRuntimeFeature(platform, scope)
        harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(FakeKnowledgeGraphFeature)
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent.id to agent)))
            .build(platform = platform)
    }

    @Test
    fun `full cognitive cycle completes and sets agent to IDLE on success`() = runTest {
        val triggerAction = Action(ActionNames.AGENT_INITIATE_TURN, buildJsonObject { put("agentId", agent.id) })
        harness.store.dispatch("ui", triggerAction)

        val stateAfterTrigger = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
        assertEquals(AgentStatus.PROCESSING, stateAfterTrigger.agents[agent.id]?.status)

        val ledgerResponsePayload = buildJsonObject {
            put("correlationId", agent.id)
            put("messages", buildJsonArray { })
        }
        val ledgerEnvelope = PrivateDataEnvelope(ActionNames.Envelopes.SESSION_RESPONSE_LEDGER, ledgerResponsePayload)
        feature.onPrivateData(ledgerEnvelope, harness.store)

        val gatewayRequest = harness.processedActions.find { it.name == ActionNames.GATEWAY_GENERATE_CONTENT }
        assertNotNull(gatewayRequest, "Agent should have dispatched a request to the gateway.")

        val gatewaySuccessPayload = buildJsonObject {
            put("correlationId", agent.id)
            put("rawContent", "This is the successful response.")
        }
        val gatewayEnvelope = PrivateDataEnvelope(ActionNames.Envelopes.GATEWAY_RESPONSE, gatewaySuccessPayload)
        feature.onPrivateData(gatewayEnvelope, harness.store)

        val finalState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
        assertEquals(AgentStatus.IDLE, finalState.agents[agent.id]?.status, "Agent should return to IDLE after a successful cycle.")
    }

    @Test
    fun `full cognitive cycle transitions agent to ERROR on gateway failure`() = runTest {
        harness.store.dispatch("ui", Action(ActionNames.AGENT_INITIATE_TURN, buildJsonObject { put("agentId", agent.id) }))
        // NOTE: We skip the ledger response step as we are testing the gateway response directly.
        val gatewayErrorPayload = buildJsonObject {
            put("correlationId", agent.id)
            put("errorMessage", "API key invalid.")
        }
        val envelope = PrivateDataEnvelope(ActionNames.Envelopes.GATEWAY_RESPONSE, gatewayErrorPayload)
        feature.onPrivateData(envelope, harness.store)

        val finalState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
        assertEquals(AgentStatus.ERROR, finalState.agents[agent.id]?.status, "Agent should be in ERROR state after a reported gateway failure.")
        assertEquals("[AGENT ERROR] Generation failed: API key invalid.", finalState.agents[agent.id]?.errorMessage)
    }

    @Test
    fun `full cognitive cycle transitions agent to ERROR on corrupted gateway response`() = runTest {
        harness.store.dispatch("ui", Action(ActionNames.AGENT_INITIATE_TURN, buildJsonObject { put("agentId", agent.id) }))
        // NOTE: We skip the ledger response step as we are testing the gateway response directly.
        val gatewayMismatchedPayload = buildJsonObject {
            put("correlationId", agent.id)
            put("unexpected_key", "some_value")
        }

        val envelope = PrivateDataEnvelope(ActionNames.Envelopes.GATEWAY_RESPONSE, gatewayMismatchedPayload)
        feature.onPrivateData(envelope, harness.store)

        val finalState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
        assertEquals(AgentStatus.ERROR, finalState.agents[agent.id]?.status, "Agent should be in ERROR state after a corrupted gateway response.")

        assertEquals("FATAL: Corrupted response from gateway.", finalState.agents[agent.id]?.errorMessage)

        val log = platform.capturedLogs.find { it.level == LogLevel.ERROR }
        assertNotNull(log, "A fatal error should have been logged.")
        assertTrue(log.message.contains("FATAL: Received corrupted gateway response payload"))
    }
}