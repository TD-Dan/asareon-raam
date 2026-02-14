package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.generated.ActionRegistry
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.agent.strategies.SovereignDefaults
import app.auf.feature.filesystem.FileSystemFeature
import app.auf.test.TestEnvironment
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
 * - Gateway Response → postProcessResponse → NVRAM_LOADED + SESSION_POST
 */
class AgentRuntimeFeatureT2CognitiveCycleTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val platform = FakePlatformDependencies("test")
    private val json = Json { ignoreUnknownKeys = true }

    // Sovereign Agent in BOOTING phase
    private val agentId = "agent-sovereign"
    private val sessionId = "session-1"
    private val agent = testAgent(
        id = agentId,
        name = "Sovereign Test Agent",
        modelProvider = "mock",
        modelName = "mock-gpt",
        cognitiveStrategyId = "sovereign_v1",
        subscribedSessionIds = listOf(sessionId),
        resources = mapOf(
            "constitution" to "const-default",
            "bootloader" to "boot-default"
        )
    ).copy(cognitiveState = buildJsonObject { put("phase", "BOOTING") })

    @Test
    fun `full boot cycle with sentinel validation`() = runTest {
        val feature = AgentRuntimeFeature(platform, scope)

        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(FileSystemFeature(platform))
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(agentId to agent),
                resources = AgentDefaults.builtInResources
            ))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // === PHASE 1: INITIATE TURN ===
            harness.store.dispatch("ui", Action(ActionRegistry.Names.AGENT_INITIATE_TURN, buildJsonObject {
                put("agentId", agentId)
                put("preview", false)
            }))

            // ASSERT: Ledger request dispatched
            val ledgerRequest = harness.processedActions.find { action ->
                action.name == ActionRegistry.Names.SESSION_REQUEST_LEDGER_CONTENT
            }
            assertNotNull(ledgerRequest, "Should request ledger content")
            assertEquals(agentId, ledgerRequest.payload?.get("correlationId")?.jsonPrimitive?.content)

            // === PHASE 2: LEDGER RESPONSE ===
            harness.store.dispatch("session", Action(
                ActionRegistry.Names.SESSION_RESPONSE_LEDGER,
                buildJsonObject {
                    put("correlationId", agentId)
                    put("messages", buildJsonArray {
                        add(buildJsonObject {
                            put("senderId", "user")
                            put("rawContent", "Hello Agent")
                            put("timestamp", 1000L)
                        })
                    })
                }
            ))

            // ASSERT: Turn context staged
            val stageAction = harness.processedActions.find { action ->
                action.name == ActionRegistry.Names.AGENT_STAGE_TURN_CONTEXT
            }
            assertNotNull(stageAction, "Should stage turn context")

            // === PHASE 3: EVALUATE CONTEXT (triggers parallel context gathering) ===
            AgentCognitivePipeline.evaluateTurnContext(agentId, harness.store)

            // ASSERT: Workspace listing requested
            val workspaceListRequest = harness.processedActions.find { action ->
                action.name == ActionRegistry.Names.FILESYSTEM_SYSTEM_LIST &&
                        action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull == agentId
            }
            assertNotNull(workspaceListRequest, "Should request workspace listing")

            // The FileSystemFeature handles the listing and delivers the response automatically.
            // Workspace context should now be staged.

            // For sovereign agents, HKG context is also required.
            // Simulate HKG context response arrival:
            harness.store.dispatch("knowledgegraph", Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_RESPONSE_CONTEXT,
                buildJsonObject {
                    put("correlationId", agentId)
                    put("context", buildJsonObject { put("persona", "test") })
                }
            ))

            // ASSERT: Gate passed — Gateway request dispatched with Sentinel in System Prompt
            val gatewayRequest = harness.processedActions.find { action ->
                action.name == ActionRegistry.Names.GATEWAY_GENERATE_CONTENT
            }
            assertNotNull(gatewayRequest, "Should dispatch gateway request after all contexts gathered")

            val systemPrompt = gatewayRequest.payload?.get("systemPrompt")?.jsonPrimitive?.content ?: ""
            assertTrue(
                systemPrompt.contains(SovereignDefaults.BOOT_SENTINEL_XML),
                "System prompt must contain BOOT_SENTINEL in BOOTING phase"
            )

            // === PHASE 4: GATEWAY SUCCESS RESPONSE ===
            harness.store.dispatch("gateway", Action(
                ActionRegistry.Names.GATEWAY_RESPONSE_RESPONSE,
                buildJsonObject {
                    put("correlationId", agentId)
                    put("rawContent", "Boot sequence complete. I am now awake and ready to serve.")
                    put("errorMessage", JsonNull)
                }
            ))

            // ASSERT: State transition to AWAKE
            val nvramUpdate = harness.processedActions.find { action ->
                action.name == ActionRegistry.Names.AGENT_NVRAM_LOADED
            }
            assertNotNull(nvramUpdate, "Should update NVRAM state on sentinel success")

            val newState = nvramUpdate.payload?.get("state")?.jsonObject
            assertEquals("AWAKE", newState?.get("phase")?.jsonPrimitive?.content, "Agent should transition to AWAKE")

            // ASSERT: Response posted to session
            val sessionPost = harness.processedActions.find { action ->
                action.name == ActionRegistry.Names.SESSION_POST
            }
            assertNotNull(sessionPost, "Should post response to session")
            assertEquals(sessionId, sessionPost.payload?.get("session")?.jsonPrimitive?.content)
            assertEquals(agent.identity.handle, sessionPost.payload?.get("senderId")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `sentinel failure halts without posting`() = runTest {
        val feature = AgentRuntimeFeature(platform, scope)

        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(FileSystemFeature(platform))
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(agentId to agent),
                resources = AgentDefaults.builtInResources
            ))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // Simulate a gateway failure response with sentinel failure token
            harness.store.dispatch("gateway", Action(
                ActionRegistry.Names.GATEWAY_RESPONSE_RESPONSE,
                buildJsonObject {
                    put("correlationId", agentId)
                    put("rawContent", "[${SovereignDefaults.SENTINEL_FAILURE_TOKEN}: NO_AGENT_PRESENT]")
                    put("errorMessage", JsonNull)
                }
            ))

            // ASSERT: No state update (remains BOOTING)
            val nvramUpdate = harness.processedActions.find { action ->
                action.name == ActionRegistry.Names.AGENT_NVRAM_LOADED
            }
            assertNull(nvramUpdate, "Should NOT update NVRAM on sentinel failure")

            // ASSERT: No session post
            val sessionPost = harness.processedActions.find { action ->
                action.name == ActionRegistry.Names.SESSION_POST
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
            .withFeature(FileSystemFeature(platform))
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(agentId to awakeAgent),
                resources = AgentDefaults.builtInResources
            ))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // === INITIATE TURN ===
            harness.store.dispatch("ui", Action(ActionRegistry.Names.AGENT_INITIATE_TURN, buildJsonObject {
                put("agentId", agentId)
            }))

            // === DELIVER LEDGER ===
            harness.store.dispatch("session", Action(
                ActionRegistry.Names.SESSION_RESPONSE_LEDGER,
                buildJsonObject {
                    put("correlationId", agentId)
                    put("messages", buildJsonArray {
                        add(buildJsonObject {
                            put("senderId", "user")
                            put("rawContent", "What is 2+2?")
                            put("timestamp", 2000L)
                        })
                    })
                }
            ))

            // Trigger evaluation (parallel context gathering)
            AgentCognitivePipeline.evaluateTurnContext(agentId, harness.store)

            // Workspace context arrives automatically via FileSystemFeature.
            // Sovereign agent also needs HKG context:
            harness.store.dispatch("knowledgegraph", Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_RESPONSE_CONTEXT,
                buildJsonObject {
                    put("correlationId", agentId)
                    put("context", buildJsonObject { put("persona", "test") })
                }
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
                ActionRegistry.Names.GATEWAY_RESPONSE_RESPONSE,
                buildJsonObject {
                    put("correlationId", agentId)
                    put("rawContent", "2+2 equals 4.")
                    put("errorMessage", JsonNull)
                }
            ))

            // === VERIFY POST ===
            val sessionPost = harness.processedActions.find { action ->
                action.name == ActionRegistry.Names.SESSION_POST
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
                "constitution" to "nonexistent-resource-id"
            )
        )

        val feature = AgentRuntimeFeature(platform, scope)

        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(FileSystemFeature(platform))
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(agentId to agentWithMissingResource),
                resources = AgentDefaults.builtInResources
            ))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // Initiate turn and deliver ledger
            harness.store.dispatch("ui", Action(ActionRegistry.Names.AGENT_INITIATE_TURN, buildJsonObject {
                put("agentId", agentId)
            }))

            harness.store.dispatch("session", Action(
                ActionRegistry.Names.SESSION_RESPONSE_LEDGER,
                buildJsonObject {
                    put("correlationId", agentId)
                    put("messages", buildJsonArray {
                        add(buildJsonObject {
                            put("senderId", "user")
                            put("rawContent", "Test")
                            put("timestamp", 3000L)
                        })
                    })
                }
            ))

            // Trigger evaluation (parallel context gathering starts)
            AgentCognitivePipeline.evaluateTurnContext(agentId, harness.store)

            // Workspace listing arrives via FileSystemFeature automatically.
            // Sovereign agent also needs HKG context for gate to pass:
            harness.store.dispatch("knowledgegraph", Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_RESPONSE_CONTEXT,
                buildJsonObject {
                    put("correlationId", agentId)
                    put("context", buildJsonObject { put("persona", "test") })
                }
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