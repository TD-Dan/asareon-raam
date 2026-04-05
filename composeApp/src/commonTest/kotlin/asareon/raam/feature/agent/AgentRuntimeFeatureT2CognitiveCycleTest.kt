package asareon.raam.feature.agent

import asareon.raam.core.Action
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.fakes.FakePlatformDependencies
import asareon.raam.feature.agent.strategies.SovereignDefaults
import asareon.raam.feature.filesystem.FileSystemFeature
import asareon.raam.feature.session.SessionFeature
import asareon.raam.feature.session.SessionState
import asareon.raam.test.TestEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * ## Tier 2 Integration Test: Cognitive Cycle
 *
 * **Mandate:** Verify the end-to-end Sovereign Strategy integration from Boot to Awake.
 *
 * **Test Scope:**
 * 1. Booting Phase (Sentinel Injection in System Prompt)
 * 2. Gateway Processing (Sentinel Validation)
 * 3. State Transition (NVRAM Persistence via AGENT_NVRAM_LOADED)
 * 4. Awake Phase (Normal Operation)
 *
 * **Critical Flow:**
 * - INITIATE_TURN → Request Ledger
 * - Ledger Response → STAGE_TURN_CONTEXT → evaluateTurnContext
 * - evaluateTurnContext dispatches workspace listing + HKG context (parallel) + timeout
 * - Workspace listing response → SET_WORKSPACE_CONTEXT → evaluateFullContext (gate)
 * - (If Sovereign) HKG response → SET_HKG_CONTEXT → evaluateFullContext (gate)
 * - Gate passes → executeTurn → GATEWAY_GENERATE_CONTENT
 * - Gateway Response → postProcessResponse → NVRAM_LOADED + SESSION_POST
 *
 * **Identity Registry Notes:**
 * All IDs use valid UUID hex format (CoreFeature validates). SessionFeature is
 * included so the "session" parent identity exists and session child identities
 * can be registered. Agent identities are registered via CORE_REGISTER_IDENTITY
 * before any INITIATE_TURN dispatch.
 */
class AgentRuntimeFeatureT2CognitiveCycleTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val platform = FakePlatformDependencies("test")
    private val json = Json { ignoreUnknownKeys = true }

    // Valid UUID hex format — CoreFeature validates these.
    private val agentId = "a0000001-0000-0000-0000-000000000001"
    private val sessionId = "b0000001-0000-0000-0000-000000000001"

    private val session = testSession(sessionId, "Chat")

    // Sovereign Agent in BOOTING phase
    private val agent = testAgent(
        id = agentId,
        name = "Sovereign Test Agent",
        modelProvider = "mock",
        modelName = "mock-gpt",
        cognitiveStrategyId = "sovereign_v1",
        subscribedSessionIds = listOf(sessionId),
        resources = mapOf(
            "constitution" to "res-sovereign-constitution-v1",
            "bootloader" to "res-boot-sentinel-v1"
        )
    ).copy(cognitiveState = buildJsonObject { put("phase", "BOOTING") })

    /**
     * Registers the agent identity in the registry via CORE_REGISTER_IDENTITY.
     * Must be called after harness.build() and before INITIATE_TURN.
     */
    private fun registerAgentIdentity(harness: asareon.raam.test.TestHarness) {
        harness.store.dispatch("agent", Action(
            ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            buildJsonObject {
                put("uuid", agentId)
                put("name", agent.identity.name)
            }
        ))
    }

    private fun registerSessionIdentity(harness: asareon.raam.test.TestHarness) {
        harness.store.dispatch("session", Action(
            ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            buildJsonObject {
                put("uuid", sessionId)
                put("name", session.identity.name)
            }
        ))
    }

    @Test
    fun `full boot cycle with sentinel validation`() = runTest {
        val feature = AgentRuntimeFeature(platform, scope)

        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(SessionFeature(platform, scope))
            .withFeature(FileSystemFeature(platform))
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(uid(agentId) to agent),
                resources = testBuiltInResources()
            ))
            // Session must exist in SessionState so handleGatewayResponse can
            // route the response POST. SessionFeature auto-responds to ledger requests.
            .withInitialState("session", SessionState(
                sessions = mapOf(sessionId to session)
            ))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // Register identities
            registerAgentIdentity(harness)
            registerSessionIdentity(harness)
            harness.store.processedActions.clear()

            // === PHASE 1: INITIATE TURN ===
            // SessionFeature auto-responds to REQUEST_LEDGER_CONTENT.
            // Pipeline auto-triggers: evaluateTurnContext → workspace listing → gate → executeTurn.
            harness.store.dispatch("core", Action(ActionRegistry.Names.AGENT_INITIATE_TURN, buildJsonObject {
                put("agentId", agentId)
                put("preview", false)
            }))

            // ASSERT: Gateway request dispatched with Sentinel in System Prompt
            val gatewayRequest = harness.processedActions.find { action ->
                action.name == ActionRegistry.Names.GATEWAY_GENERATE_CONTENT
            }
            assertNotNull(gatewayRequest, "Should dispatch gateway request after context gathered")

            val systemPrompt = gatewayRequest.payload?.get("systemPrompt")?.jsonPrimitive?.content ?: ""
            assertTrue(
                systemPrompt.contains(SovereignDefaults.BOOT_SENTINEL_XML),
                "System prompt must contain BOOT_SENTINEL in BOOTING phase"
            )

            // === PHASE 2: GATEWAY SUCCESS RESPONSE ===
            harness.store.dispatch("gateway", Action(
                name = ActionRegistry.Names.GATEWAY_RETURN_RESPONSE,
                payload = buildJsonObject {
                    put("correlationId", agentId)
                    put("rawContent", "Boot sequence complete. I am now awake and ready to serve.")
                    put("errorMessage", JsonNull)
                },
                targetRecipient = "agent"
            ))

            // ASSERT: State transition to AWAKE
            val nvramUpdate = harness.processedActions.find { action ->
                action.name == ActionRegistry.Names.AGENT_NVRAM_LOADED
            }
            assertNotNull(nvramUpdate, "Should update NVRAM state on sentinel success")

            val newState = nvramUpdate.payload?.get("state")?.jsonObject
            assertEquals("AWAKE", newState?.get("phase")?.jsonPrimitive?.content, "Agent should transition to AWAKE")

            // ASSERT: Response posted to session
            // Filter for response POSTs (with "message" field), not avatar POSTs
            val sessionPost = harness.processedActions.find { action ->
                action.name == ActionRegistry.Names.SESSION_POST &&
                        action.payload?.containsKey("message") == true
            }
            assertNotNull(sessionPost, "Should post response to session")
            assertEquals(agent.identity.handle, sessionPost.payload?.get("senderId")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `sentinel failure halts without posting`() = runTest {
        val feature = AgentRuntimeFeature(platform, scope)

        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(SessionFeature(platform, scope))
            .withFeature(FileSystemFeature(platform))
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(uid(agentId) to agent),
                resources = testBuiltInResources()
            ))
            .withInitialState("session", SessionState(
                sessions = mapOf(sessionId to session)
            ))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // Register agent identity so session lookup path works
            registerAgentIdentity(harness)
            registerSessionIdentity(harness)
            harness.store.processedActions.clear()

            // Simulate a gateway failure response with sentinel failure token
            harness.store.dispatch("gateway", Action(
                name = ActionRegistry.Names.GATEWAY_RETURN_RESPONSE,
                payload = buildJsonObject {
                    put("correlationId", agentId)
                    put("rawContent", "[${SovereignDefaults.SENTINEL_FAILURE_TOKEN}: NO_AGENT_PRESENT]")
                    put("errorMessage", JsonNull)
                },
                targetRecipient = "agent"
            ))

            // ASSERT: No state update (remains BOOTING)
            val nvramUpdate = harness.processedActions.find { action ->
                action.name == ActionRegistry.Names.AGENT_NVRAM_LOADED
            }
            assertNull(nvramUpdate, "Should NOT update NVRAM on sentinel failure")

            // ASSERT: No content post (avatar cards are ok, but no agent response should be posted)
            val sessionPost = harness.processedActions.find { action ->
                action.name == ActionRegistry.Names.SESSION_POST &&
                        action.payload?.containsKey("message") == true
            }
            assertNull(sessionPost, "Should NOT post to session on sentinel failure")

            // ASSERT: Agent status set to IDLE with warning
            val statusUpdates = harness.processedActions.filter { action ->
                action.name == ActionRegistry.Names.AGENT_SET_STATUS
            }
            val idleStatus = statusUpdates.lastOrNull()
            assertNotNull(idleStatus, "Should update status on sentinel failure")
            assertEquals("IDLE", idleStatus.payload?.get("status")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `awake agent bypasses sentinel and posts normally`() = runTest {
        val awakeAgent = agent.copy(
            cognitiveState = buildJsonObject { put("phase", "AWAKE") }
        )

        val feature = AgentRuntimeFeature(platform, scope)

        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(SessionFeature(platform, scope))
            .withFeature(FileSystemFeature(platform))
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(uid(agentId) to awakeAgent),
                resources = testBuiltInResources()
            ))
            // Empty SessionState: prevents auto-response race with manual ledger delivery
            .withInitialState("session", SessionState())
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // Register identities
            registerAgentIdentity(harness)
            registerSessionIdentity(harness)
            harness.store.processedActions.clear()

            // === INITIATE TURN ===
            harness.store.dispatch("core", Action(ActionRegistry.Names.AGENT_INITIATE_TURN, buildJsonObject {
                put("agentId", agentId)
            }))

            // === DELIVER LEDGER ===
            // Manually deliver (SessionFeature won't auto-respond with empty state).
            // Phase B: compound correlationId "agentUUID::sessionUUID" for multi-session accumulation.
            harness.store.dispatch("session", Action(
                name = ActionRegistry.Names.SESSION_RETURN_LEDGER,
                payload = buildJsonObject {
                    put("correlationId", "$agentId::$sessionId")
                    put("messages", buildJsonArray {
                        add(buildJsonObject {
                            put("senderId", "user")
                            put("rawContent", "What is 2+2?")
                            put("timestamp", 2000L)
                        })
                    })
                },
                targetRecipient = "agent"
            ))

            // Workspace context arrives automatically via FileSystemFeature.
            // Sovereign agent also needs HKG context:
            harness.store.dispatch("knowledgegraph", Action(
                name = ActionRegistry.Names.KNOWLEDGEGRAPH_RETURN_CONTEXT,
                payload = buildJsonObject {
                    put("correlationId", agentId)
                    put("context", buildJsonObject { put("persona", "test") })
                },
                targetRecipient = "agent"
            ))

            // === VERIFY NO SENTINEL ===
            val gatewayRequest = harness.processedActions.find { action ->
                action.name == ActionRegistry.Names.GATEWAY_GENERATE_CONTENT
            }
            assertNotNull(gatewayRequest, "Should dispatch gateway request")

            val systemPrompt = gatewayRequest.payload?.get("systemPrompt")?.jsonPrimitive?.content ?: ""
            assertFalse(
                systemPrompt.contains(SovereignDefaults.BOOT_SENTINEL_XML),
                "System prompt must NOT contain sentinel in AWAKE phase"
            )

            // === DELIVER NORMAL RESPONSE ===
            harness.store.dispatch("gateway", Action(
                name = ActionRegistry.Names.GATEWAY_RETURN_RESPONSE,
                payload = buildJsonObject {
                    put("correlationId", agentId)
                    put("rawContent", "2+2 equals 4.")
                    put("errorMessage", JsonNull)
                },
                targetRecipient = "agent"
            ))

            // === VERIFY POST ===
            // Filter for response POSTs (with "message" field), not avatar POSTs
            val sessionPost = harness.processedActions.find { action ->
                action.name == ActionRegistry.Names.SESSION_POST &&
                        action.payload?.containsKey("message") == true
            }
            assertNotNull(sessionPost, "AWAKE agent should post response")
            assertTrue(
                sessionPost.payload?.get("message")?.jsonPrimitive?.content?.contains("2+2 equals 4") ?: false,
                "Posted message should contain the response"
            )
        }
    }

    @Test
    fun `missing resources triggers error state`() = runTest {
        val agentWithMissingResource = agent.copy(
            resources = mapOf(
                "constitution" to uid("nonexistent-resource-id")
            )
        )

        val feature = AgentRuntimeFeature(platform, scope)

        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(SessionFeature(platform, scope))
            .withFeature(FileSystemFeature(platform))
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(uid(agentId) to agentWithMissingResource),
                resources = testBuiltInResources()
            ))
            .withInitialState("session", SessionState())
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // Register identities
            registerAgentIdentity(harness)
            registerSessionIdentity(harness)
            harness.store.processedActions.clear()

            // Initiate turn and deliver ledger
            harness.store.dispatch("core", Action(ActionRegistry.Names.AGENT_INITIATE_TURN, buildJsonObject {
                put("agentId", agentId)
            }))

            // Manually deliver ledger — Phase B compound correlationId
            harness.store.dispatch("session", Action(
                name = ActionRegistry.Names.SESSION_RETURN_LEDGER,
                payload = buildJsonObject {
                    put("correlationId", "$agentId::$sessionId")
                    put("messages", buildJsonArray {
                        add(buildJsonObject {
                            put("senderId", "user")
                            put("rawContent", "Test")
                            put("timestamp", 3000L)
                        })
                    })
                },
                targetRecipient = "agent"
            ))

            // Workspace listing arrives via FileSystemFeature automatically.
            // Sovereign agent also needs HKG context for gate to pass:
            harness.store.dispatch("knowledgegraph", Action(
                name = ActionRegistry.Names.KNOWLEDGEGRAPH_RETURN_CONTEXT,
                payload = buildJsonObject {
                    put("correlationId", agentId)
                    put("context", buildJsonObject { put("persona", "test") })
                },
                targetRecipient = "agent"
            ))

            // Gate passes → executeTurn fires → resource validation fails → ERROR

            // ASSERT: No gateway request (resource validation failed)
            val gatewayRequest = harness.processedActions.find { action ->
                action.name == ActionRegistry.Names.GATEWAY_GENERATE_CONTENT
            }
            assertNull(gatewayRequest, "Should NOT dispatch gateway request when resources missing")

            // ASSERT: Agent status set to ERROR
            val statusUpdates = harness.processedActions.filter { action ->
                action.name == ActionRegistry.Names.AGENT_SET_STATUS
            }
            val errorStatus = statusUpdates.find { action ->
                action.payload?.get("status")?.jsonPrimitive?.content == "ERROR"
            }
            assertNotNull(errorStatus, "Should set status to ERROR on missing resources")

            val errorMsg = errorStatus.payload?.get("error")?.jsonPrimitive?.contentOrNull
            assertNotNull(errorMsg, "Should include error message")
            assertTrue(errorMsg.contains("Missing required resources"), "Error message should mention missing resources")
        }
    }
}