package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.Feature
import app.auf.core.Identity
import app.auf.core.Store
import app.auf.core.generated.ActionNames
import app.auf.feature.core.CoreState
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tier 2 Core Test for AgentRuntimeFeature.
 *
 * Mandate (P-TEST-001, T2): To test the feature's reducer and onAction handlers working
 * together within a realistic TestEnvironment that includes the real Store.
 */
class AgentRuntimeFeatureT2CoreTest {
    private val json = Json { ignoreUnknownKeys = true }

    private object FakeSessionFeature : Feature {
        override val name = "session"
        override val composableProvider: Feature.ComposableProvider? = null
        private val json = Json { ignoreUnknownKeys = true }

        override fun onAction(action: Action, store: Store, previousState: AppState) {
            if (action.name == ActionNames.SESSION_REQUEST_LEDGER_CONTENT) {
                val correlationId = action.payload?.get("correlationId")?.jsonPrimitive?.content ?: return
                // Simulate a ledger with one user message and one agent message
                val ledgerMessages = listOf(
                    buildJsonObject {
                        put("id", "msg-1"); put("timestamp", 1000L); put("senderId", "user-id-1")
                        put("rawContent", "User message"); put("content", buildJsonArray { })
                    },
                    buildJsonObject {
                        put("id", "msg-2"); put("timestamp", 2000L); put("senderId", "agent-1")
                        put("rawContent", "Agent response"); put("content", buildJsonArray { })
                    }
                )
                val responsePayload = buildJsonObject {
                    put("correlationId", correlationId)
                    put("messages", Json.encodeToJsonElement(ledgerMessages))
                }
                store.deliverPrivateData(
                    name,
                    "agent",
                    app.auf.core.PrivateDataEnvelope(ActionNames.Envelopes.SESSION_RESPONSE_LEDGER, responsePayload)
                )
            }
        }
    }

    private object FakeGatewayFeature : Feature {
        override val name = "gateway"
        override val composableProvider: Feature.ComposableProvider? = null
        private val json = Json { ignoreUnknownKeys = true }

        override fun onAction(action: Action, store: Store, previousState: AppState) {
            if (action.name == ActionNames.GATEWAY_PREPARE_PREVIEW) {
                val payload = action.payload!!
                val agentId = payload["correlationId"]!!.jsonPrimitive.content
                val agnosticRequest = GatewayRequest(
                    modelName = payload["modelName"]!!.jsonPrimitive.content,
                    contents = json.decodeFromJsonElement(payload["contents"]!!),
                    correlationId = agentId
                )
                val responsePayload = buildJsonObject {
                    put("correlationId", agentId)
                    put("agnosticRequest", json.encodeToJsonElement(agnosticRequest))
                    put("rawRequestJson", "{\"key\":\"value\"}")
                }
                store.deliverPrivateData(
                    name,
                    "agent",
                    app.auf.core.PrivateDataEnvelope(ActionNames.Envelopes.GATEWAY_RESPONSE_PREVIEW, responsePayload)
                )
            }
        }
    }

    private fun createTestAgent(
        id: String = "agent-1",
        name: String = "Test Agent",
        sessionId: String? = "session-1",
        status: AgentStatus = AgentStatus.IDLE,
        turnMode: TurnMode = TurnMode.DIRECT
    ): AgentInstance {
        return AgentInstance(id = id, name = name, personaId = "p1", modelProvider = "mp1", modelName = "mn1", primarySessionId = sessionId, status = status, turnMode = turnMode)
    }

    @Test
    fun `INITIATE_TURN with preview=false dispatches SESSION_REQUEST_LEDGER_CONTENT`() {
        val harness = TestEnvironment.create()
            .withFeature(AgentRuntimeFeature(FakePlatformDependencies("test"), CoroutineScope(Dispatchers.Unconfined)))
            .withInitialState("agent", AgentRuntimeState(agents = mapOf("agent-1" to createTestAgent())))
            .build()
        val action = Action(ActionNames.AGENT_INITIATE_TURN, buildJsonObject {
            put("agentId", "agent-1")
            put("preview", false)
        })

        harness.store.dispatch("ui", action)

        val ledgerRequest = harness.processedActions.find { it.name == ActionNames.SESSION_REQUEST_LEDGER_CONTENT }
        assertNotNull(ledgerRequest)
        assertEquals("agent-1", ledgerRequest.payload?.get("correlationId")?.jsonPrimitive?.content)
    }

    @Test
    fun `INITIATE_TURN with preview=true dispatches SESSION_REQUEST_LEDGER_CONTENT`() {
        val harness = TestEnvironment.create()
            .withFeature(AgentRuntimeFeature(FakePlatformDependencies("test"), CoroutineScope(Dispatchers.Unconfined)))
            .withInitialState("agent", AgentRuntimeState(agents = mapOf("agent-1" to createTestAgent())))
            .build()
        val action = Action(ActionNames.AGENT_INITIATE_TURN, buildJsonObject {
            put("agentId", "agent-1")
            put("preview", true)
        })

        harness.store.dispatch("ui", action)

        val ledgerRequest = harness.processedActions.find { it.name == ActionNames.SESSION_REQUEST_LEDGER_CONTENT }
        assertNotNull(ledgerRequest)
    }

    @Test
    fun `on session ledger response for direct turn, dispatches GATEWAY_GENERATE_CONTENT`() {
        val agent = createTestAgent(status = AgentStatus.IDLE, turnMode = TurnMode.DIRECT)
        val harness = TestEnvironment.create()
            .withFeature(AgentRuntimeFeature(FakePlatformDependencies("test"), CoroutineScope(Dispatchers.Unconfined)))
            .withFeature(FakeSessionFeature)
            .withInitialState("agent", AgentRuntimeState(agents = mapOf("agent-1" to agent)))
            .build()

        harness.store.dispatch("ui", Action(ActionNames.AGENT_INITIATE_TURN, buildJsonObject { put("agentId", "agent-1"); put("preview", false) }))

        val gatewayRequest = harness.processedActions.find { it.name == ActionNames.GATEWAY_GENERATE_CONTENT }
        assertNotNull(gatewayRequest)
    }

    @Test
    fun `on session ledger response for preview turn, dispatches GATEWAY_PREPARE_PREVIEW`() {
        val agent = createTestAgent(status = AgentStatus.IDLE, turnMode = TurnMode.PREVIEW)
        val harness = TestEnvironment.create()
            .withFeature(AgentRuntimeFeature(FakePlatformDependencies("test"), CoroutineScope(Dispatchers.Unconfined)))
            .withFeature(FakeSessionFeature)
            .withInitialState("agent", AgentRuntimeState(agents = mapOf("agent-1" to agent)))
            .build()

        harness.store.dispatch("ui", Action(ActionNames.AGENT_INITIATE_TURN, buildJsonObject { put("agentId", "agent-1"); put("preview", true) }))

        val gatewayRequest = harness.processedActions.find { it.name == ActionNames.GATEWAY_PREPARE_PREVIEW }
        assertNotNull(gatewayRequest)
    }

    @Test
    fun `assembles enriched context with correct identities from various sources`() {
        // ARRANGE
        val user = Identity("user-id-1", "User Alpha")
        val agent = createTestAgent(id = "agent-1", name = "Test Agent")
        val harness = TestEnvironment.create()
            .withFeature(AgentRuntimeFeature(FakePlatformDependencies("test"), CoroutineScope(Dispatchers.Unconfined)))
            .withFeature(FakeSessionFeature)
            .withInitialState("core", CoreState(userIdentities = listOf(user), activeUserId = "user-id-1"))
            .withInitialState("agent", AgentRuntimeState(agents = mapOf("agent-1" to agent)))
            .build()

        // ARRANGE 2: Manually broadcast the identities to populate the agent's cache.
        val identitiesBroadcast = Action(ActionNames.CORE_PUBLISH_IDENTITIES_UPDATED, buildJsonObject {
            put("identities", Json.encodeToJsonElement(listOf(user)))
            put("activeId", "user-id-1")
        })
        harness.store.dispatch("core", identitiesBroadcast)

        // ACT: Trigger the turn using the correct, public entry point.
        val triggerAction = Action(ActionNames.AGENT_INITIATE_TURN, buildJsonObject {
            put("agentId", "agent-1")
            put("preview", false)
        })
        harness.store.dispatch("ui", triggerAction)


        // ASSERT
        val gatewayRequest = harness.processedActions.find { it.name == ActionNames.GATEWAY_GENERATE_CONTENT }

        // FIX: Add instrumentation output to diagnose the failure.
        if (gatewayRequest == null) {
            println("--- DEBUG LOGS FOR FAILING TEST ---")
            harness.platform.capturedLogs.forEach { println(it) }
            println("--- END DEBUG LOGS ---")
        }

        assertNotNull(gatewayRequest, "Gateway request should have been dispatched.")
        val contents = gatewayRequest.payload?.get("contents")?.let { json.decodeFromJsonElement<List<GatewayMessage>>(it) }
        assertNotNull(contents)
        assertEquals(2, contents.size)

        val userMessage = contents[0]
        assertEquals("user", userMessage.role)
        assertEquals("user-id-1", userMessage.senderId)
        assertEquals("User Alpha", userMessage.senderName)
        assertEquals("User message", userMessage.content)
        assertEquals(1000L, userMessage.timestamp)

        val agentMessage = contents[1]
        assertEquals("model", agentMessage.role)
        assertEquals("agent-1", agentMessage.senderId)
        assertEquals("Test Agent", agentMessage.senderName)
        assertEquals("Agent response", agentMessage.content)
        assertEquals(2000L, agentMessage.timestamp)
    }

    @Test
    fun `CLONE action dispatches a CREATE action with modified name`() {
        val agent = createTestAgent()
        val harness = TestEnvironment.create()
            .withFeature(AgentRuntimeFeature(FakePlatformDependencies("test"), CoroutineScope(Dispatchers.Unconfined)))
            .withInitialState("agent", AgentRuntimeState(agents = mapOf("agent-1" to agent)))
            .build()

        harness.store.dispatch("ui", Action(ActionNames.AGENT_CLONE, buildJsonObject { put("agentId", "agent-1") }))

        val createAction = harness.processedActions.find { it.name == ActionNames.AGENT_CREATE }
        assertNotNull(createAction)
        assertEquals("Test Agent (Copy)", createAction.payload?.get("name")?.jsonPrimitive?.content)
    }

    @Test
    fun `full preview workflow results in staged data and correct final execution`() {
        val agent = createTestAgent()
        val harness = TestEnvironment.create()
            .withFeature(AgentRuntimeFeature(FakePlatformDependencies("test"), CoroutineScope(Dispatchers.Unconfined)))
            .withFeature(FakeSessionFeature)
            .withFeature(FakeGatewayFeature)
            .withInitialState("agent", AgentRuntimeState(agents = mapOf("agent-1" to agent)))
            .build()

        // 1. Initiate preview
        harness.store.dispatch("ui", Action(ActionNames.AGENT_INITIATE_TURN, buildJsonObject {
            put("agentId", "agent-1")
            put("preview", true)
        }))

        // Verify state after preview is prepared
        val stateAfterPreview = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
        val agentAfterPreview = stateAfterPreview.agents["agent-1"]
        assertNotNull(agentAfterPreview?.stagedPreviewData)
        assertEquals("{\"key\":\"value\"}", agentAfterPreview.stagedPreviewData.rawRequestJson)
        assertEquals("agent-1", stateAfterPreview.viewingContextForAgentId)
        assertTrue(harness.processedActions.any { it.name == ActionNames.CORE_SET_ACTIVE_VIEW })

        // 2. Execute the staged turn
        harness.store.dispatch("ui", Action(ActionNames.AGENT_EXECUTE_PREVIEWED_TURN, buildJsonObject { put("agentId", "agent-1") }))

        // Verify final execution
        val generateAction = harness.processedActions.find { it.name == ActionNames.GATEWAY_GENERATE_CONTENT }
        assertNotNull(generateAction)
        assertEquals("agent-1", generateAction.payload?.get("correlationId")?.jsonPrimitive?.content)
        val stateAfterExecute = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
        assertNull(stateAfterExecute.agents["agent-1"]?.stagedPreviewData, "Staged data should be cleared after execution.")
        assertNull(stateAfterExecute.viewingContextForAgentId, "Viewing context should be cleared.")
    }

    @Test
    fun `DISCARD_PREVIEW action clears staged data and viewing context`() {
        val previewData = StagedPreviewData(GatewayRequest("m1", emptyList(), "c1"), "{}")
        val agent = createTestAgent().copy(stagedPreviewData = previewData)
        val harness = TestEnvironment.create()
            .withFeature(AgentRuntimeFeature(FakePlatformDependencies("test"), CoroutineScope(Dispatchers.Unconfined)))
            .withInitialState("agent", AgentRuntimeState(agents = mapOf("agent-1" to agent), viewingContextForAgentId = "agent-1"))
            .build()

        harness.store.dispatch("ui", Action(ActionNames.AGENT_DISCARD_PREVIEW, buildJsonObject { put("agentId", "agent-1") }))

        val finalState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
        assertNull(finalState.agents["agent-1"]?.stagedPreviewData)
        assertNull(finalState.viewingContextForAgentId)
    }

}