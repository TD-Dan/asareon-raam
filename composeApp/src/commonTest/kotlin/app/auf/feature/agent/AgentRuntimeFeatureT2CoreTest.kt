package app.auf.feature.agent

import app.auf.core.*
import app.auf.core.generated.ActionNames
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreState
import app.auf.feature.session.Session
import app.auf.feature.session.SessionFeature
import app.auf.feature.session.SessionState
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

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

        override fun onAction(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {
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
                    PrivateDataEnvelope(ActionNames.Envelopes.SESSION_RESPONSE_LEDGER, responsePayload)
                )
            }
        }
    }

    private object FakeGatewayFeature : Feature {
        override val name = "gateway"
        override val composableProvider: Feature.ComposableProvider? = null
        private val json = Json { ignoreUnknownKeys = true }

        override fun onAction(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {
            if (action.name == ActionNames.GATEWAY_PREPARE_PREVIEW) {
                val payload = action.payload!!
                val agentId = payload["correlationId"]!!.jsonPrimitive.content
                val agnosticRequest = GatewayRequest(
                    modelName = payload["modelName"]!!.jsonPrimitive.content,
                    contents = json.decodeFromJsonElement(payload["contents"]!!),
                    correlationId = agentId,
                    systemPrompt = payload["systemPrompt"]?.jsonPrimitive?.content
                )
                val responsePayload = buildJsonObject {
                    put("correlationId", agentId)
                    put("agnosticRequest", json.encodeToJsonElement(agnosticRequest))
                    put("rawRequestJson", "{\"key\":\"value\"}")
                }
                store.deliverPrivateData(
                    name,
                    "agent",
                    PrivateDataEnvelope(ActionNames.Envelopes.GATEWAY_RESPONSE_PREVIEW, responsePayload)
                )
            }
        }
    }

    private object FakeKnowledgeGraphFeature : Feature {
        override val name: String = "knowledgegraph"
        override val composableProvider: Feature.ComposableProvider? = null
        override fun onPrivateData(envelope: PrivateDataEnvelope, store: Store) {
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

    private fun createTestAgentConfig(
        id: String = "agent-1",
        name: String = "Test Agent",
        sessionIds: List<String> = listOf("session-1"),
        kgId: String? = "p1"
    ): AgentInstance {
        return AgentInstance(id = id, name = name, knowledgeGraphId = kgId, modelProvider = "mp1", modelName = "mn1", subscribedSessionIds = sessionIds)
    }

    @Test
    fun `sentinel sanitizes timestamp echo out of ai response`() = runTest {
        val agent = createTestAgentConfig()
        val status = AgentStatusInfo(status = AgentStatus.PROCESSING)
        val session = Session("session-1", "Test Session", emptyList(), 1L)
        val harness = TestEnvironment.create()
            .withFeature(AgentRuntimeFeature(FakePlatformDependencies("test"), CoroutineScope(Dispatchers.Unconfined)))
            .withFeature(SessionFeature(FakePlatformDependencies("test"), CoroutineScope(Dispatchers.Unconfined)))
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(agent.id to agent),
                agentStatuses = mapOf(agent.id to status)
            ))
            .withInitialState("session", SessionState(sessions = mapOf("session-1" to session)))
            .build()
        val gatewayResponsePayload = buildJsonObject {
            put("correlationId", "agent-1")
            put("rawContent", "Test Agent (agent-1) @ 2025-10-27T12:34:56Z: This is the actual response.")
        }
        val envelope = PrivateDataEnvelope(ActionNames.Envelopes.GATEWAY_RESPONSE, gatewayResponsePayload)

        harness.runAndLogOnFailure {
            // ACT
            (harness.store.features.find { it.name == "agent" } as AgentRuntimeFeature).onPrivateData(envelope, harness.store)

            // ASSERT
            val sentinelAction =
                harness.processedActions.find { it.name == ActionNames.SESSION_POST && it.payload?.get("senderId")?.jsonPrimitive?.content == "system" }
            assertNotNull(sentinelAction, "A sentinel warning should have been posted.")
            assertTrue(sentinelAction.payload?.get("message")?.jsonPrimitive?.content?.contains("Warning for [Test Agent]") == true)

            val agentResponseAction =
                harness.processedActions.find { it.name == ActionNames.SESSION_POST && it.payload?.get("senderId")?.jsonPrimitive?.content == "agent-1" }
            assertNotNull(agentResponseAction, "The agent's own response should have been posted.")
            assertEquals(
                "This is the actual response.",
                agentResponseAction.payload?.get("message")?.jsonPrimitive?.content
            )

            val finalState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
            assertEquals(AgentStatus.IDLE, finalState.agentStatuses["agent-1"]?.status)
        }
    }

    @Test
    fun `INITIATE_TURN with preview=false dispatches SESSION_REQUEST_LEDGER_CONTENT`() = runTest {
        val harness = TestEnvironment.create()
            .withFeature(AgentRuntimeFeature(FakePlatformDependencies("test"), CoroutineScope(Dispatchers.Unconfined)))
            .withInitialState("agent", AgentRuntimeState(agents = mapOf("agent-1" to createTestAgentConfig())))
            .build()

        harness.runAndLogOnFailure {
            val action = Action(ActionNames.AGENT_INITIATE_TURN, buildJsonObject {
                put("agentId", "agent-1")
                put("preview", false)
            })

            harness.store.dispatch("ui", action)

            val ledgerRequest = harness.processedActions.find { it.name == ActionNames.SESSION_REQUEST_LEDGER_CONTENT }
            assertNotNull(ledgerRequest)
            assertEquals("agent-1", ledgerRequest.payload?.get("correlationId")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `on session ledger response for direct turn, dispatches GATEWAY_GENERATE_CONTENT`() = runTest {
        val agent = createTestAgentConfig()
        val status = AgentStatusInfo(status = AgentStatus.IDLE, turnMode = TurnMode.DIRECT)

        val harness = TestEnvironment.create()
            .withFeature(AgentRuntimeFeature(FakePlatformDependencies("test"), CoroutineScope(Dispatchers.Unconfined)))
            .withFeature(FakeSessionFeature)
            .withFeature(FakeKnowledgeGraphFeature)
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(agent.id to agent),
                agentStatuses = mapOf(agent.id to status)
            ))
            .build()

        harness.runAndLogOnFailure {
            harness.store.dispatch("ui", Action(ActionNames.AGENT_INITIATE_TURN, buildJsonObject { put("agentId", "agent-1"); put("preview", false) }))

            val gatewayRequest = harness.processedActions.find { it.name == ActionNames.GATEWAY_GENERATE_CONTENT }
            assertNotNull(gatewayRequest)
        }
    }

    @Test
    fun `assembles enriched context with correct identities`() = runTest {
        val user = Identity("user-id-1", "User Alpha")
        val agent = createTestAgentConfig(id = "agent-1", name = "Test Agent")

        val harness = TestEnvironment.create()
            .withFeature(AgentRuntimeFeature(FakePlatformDependencies("2.0.0-test"), CoroutineScope(Dispatchers.Unconfined)))
            .withFeature(FakeSessionFeature)
            .withFeature(FakeKnowledgeGraphFeature)
            .withInitialState("core", CoreState(userIdentities = listOf(user), activeUserId = "user-id-1", lifecycle = AppLifecycle.RUNNING))
            .withInitialState("agent", AgentRuntimeState(agents = mapOf("agent-1" to agent)))
            .build()

        harness.runAndLogOnFailure {
            // Populate cache
            val identitiesBroadcast = Action(ActionNames.CORE_PUBLISH_IDENTITIES_UPDATED, buildJsonObject {
                put("identities", Json.encodeToJsonElement(listOf(user)))
                put("activeId", "user-id-1")
            })
            harness.store.dispatch("core", identitiesBroadcast)

            val triggerAction = Action(ActionNames.AGENT_INITIATE_TURN, buildJsonObject {
                put("agentId", "agent-1")
                put("preview", false)
            })
            harness.store.dispatch("ui", triggerAction)

            val gatewayRequest = harness.processedActions.find { it.name == ActionNames.GATEWAY_GENERATE_CONTENT }
            assertNotNull(gatewayRequest)

            val contents = gatewayRequest.payload?.get("contents")?.let { json.decodeFromJsonElement<List<GatewayMessage>>(it) }
            assertNotNull(contents)
            assertEquals("User Alpha", contents[0].senderName)
        }
    }

    @Test
    fun `full preview workflow results in staged data and correct final execution`() = runTest {
        val agent = createTestAgentConfig()
        val harness = TestEnvironment.create()
            .withFeature(AgentRuntimeFeature(FakePlatformDependencies("test"), CoroutineScope(Dispatchers.Unconfined)))
            .withFeature(FakeSessionFeature)
            .withFeature(FakeGatewayFeature)
            .withFeature(FakeKnowledgeGraphFeature)
            .withInitialState("agent", AgentRuntimeState(agents = mapOf("agent-1" to agent)))
            .build()

        harness.runAndLogOnFailure {
            // 1. Initiate preview
            harness.store.dispatch("ui", Action(ActionNames.AGENT_INITIATE_TURN, buildJsonObject {
                put("agentId", "agent-1")
                put("preview", true)
            }))

            // Verify state after preview is prepared
            val stateAfterPreview = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
            val agentStatus = stateAfterPreview.agentStatuses["agent-1"]
            assertNotNull(agentStatus?.stagedPreviewData)
            assertEquals("{\"key\":\"value\"}", agentStatus.stagedPreviewData?.rawRequestJson)
            assertEquals("agent-1", stateAfterPreview.viewingContextForAgentId)

            // 2. Execute the staged turn
            harness.store.dispatch("ui", Action(ActionNames.AGENT_EXECUTE_PREVIEWED_TURN, buildJsonObject { put("agentId", "agent-1") }))

            // Verify final execution
            val generateAction = harness.processedActions.find { it.name == ActionNames.GATEWAY_GENERATE_CONTENT }
            assertNotNull(generateAction)

            val stateAfterExecute = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
            assertNull(stateAfterExecute.agentStatuses["agent-1"]?.stagedPreviewData)
        }
    }
}