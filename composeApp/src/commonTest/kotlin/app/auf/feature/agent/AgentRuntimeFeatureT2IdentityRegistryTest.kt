package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.Identity
import app.auf.core.generated.ActionRegistry
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreState
import app.auf.feature.filesystem.FileSystemFeature
import app.auf.feature.session.SessionFeature
import app.auf.feature.session.SessionState
import app.auf.test.TestEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * ## Tier 2/3 Integration Test: Identity Registry Interactions
 *
 * **Mandate:** Close the systemic identity registry blind spot identified in
 * the T2 coverage analysis. Every interaction between AgentRuntimeFeature and
 * the AppState.identityRegistry is exercised here.
 *
 * **Coverage Targets:**
 * 1. `resolveAgentId()` — flexible resolution by UUID, handle, localHandle, name
 * 2. `startCognitiveCycle` — session UUID validation guard (abort + success)
 * 3. `handleGatewayResponse` — session UUID validation guard
 * 4. `CORE_REGISTER_IDENTITY` dispatched on AGENT_AGENT_LOADED and AGENT_CREATE
 * 5. `CORE_UNREGISTER_IDENTITY` dispatched on AGENT_DELETE
 * 6. `CORE_UPDATE_IDENTITY` dispatched on AGENT_UPDATE_CONFIG name change
 * 7. `CORE_RETURN_REGISTER_IDENTITY` / `CORE_RETURN_UPDATE_IDENTITY` → saveAgentConfig
 *
 * **Design Notes:**
 * - `resolveAgentId` tests (§2) register only the agent identity. They prove
 *   resolution succeeded by asserting `AGENT_ACTION_RESULT success=true` and
 *   `SET_STATUS` with an error about "Session UUID" (not "agent not found").
 *   This isolates resolution from the session validation guard concern.
 * - Session-dependent tests (§4–5) include `SessionFeature` so the "session"
 *   parent identity exists in the registry and session child identities can be
 *   registered via `SessionState` pre-population.
 */
class AgentRuntimeFeatureT2IdentityRegistryTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val platform = FakePlatformDependencies("test")

    // Valid UUIDs (hex-only, 8-4-4-4-12 format) — CoreFeature validates format.
    private val agentUUID = "a0000000-0000-0000-0000-000000000001"
    private val sessionUUID = "b0000000-0000-0000-0000-000000000001"

    private val agent = testAgent(
        id = agentUUID,
        name = "Alpha",
        modelProvider = "mock",
        modelName = "mock-model",
        subscribedSessionIds = listOf(sessionUUID),
        resources = mapOf("system_instruction" to "res-sys-instruction-v1")
    )

    private val session = testSession(sessionUUID, "Chat")

    // ========================================================================
    // Helper: Register agent identity in the registry
    // ========================================================================

    /**
     * Dispatches CORE_REGISTER_IDENTITY from "agent" to populate the identity
     * registry. This mirrors what AgentRuntimeFeature does in AGENT_AGENT_LOADED.
     * Works because AgentRuntimeFeature registers "agent" as a root identity.
     */
    private fun registerAgentIdentity(
        harness: app.auf.test.TestHarness,
        uuid: String,
        name: String
    ) {
        harness.store.dispatch("agent", Action(
            ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            buildJsonObject {
                put("uuid", uuid)
                put("name", name)
            }
        ))
    }

    /**
     * Verifies the identity registry contains an entry with the given UUID
     * and parentHandle. Returns the registered Identity for further assertions.
     */
    private fun assertRegistered(
        harness: app.auf.test.TestHarness,
        uuid: String,
        parentHandle: String
    ): Identity {
        val registry = harness.store.state.value.identityRegistry
        val identity = registry.values.find { it.uuid == uuid && it.parentHandle == parentHandle }
        assertNotNull(identity, "Expected identity with uuid='$uuid' and parentHandle='$parentHandle' in registry. " +
                "Registry contains: ${registry.values.map { "${it.handle} (uuid=${it.uuid})" }}")
        return identity
    }

    // ========================================================================
    // 1. CORE_REGISTER_IDENTITY dispatched on AGENT_AGENT_LOADED
    // ========================================================================

    @Test
    fun `AGENT_AGENT_LOADED dispatches CORE_REGISTER_IDENTITY with agent uuid and name`() = runTest {
        val feature = AgentRuntimeFeature(platform, scope)

        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("agent", AgentRuntimeState(resources = emptyList()))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // ACT: Simulate agent loading from disk by dispatching AGENT_AGENT_LOADED
            val agentJson = Json.encodeToJsonElement(agent) as JsonObject
            harness.store.dispatch("agent", Action(
                ActionRegistry.Names.AGENT_AGENT_LOADED,
                agentJson
            ))

            // ASSERT: CORE_REGISTER_IDENTITY was dispatched for this agent
            val registerAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.CORE_REGISTER_IDENTITY &&
                        it.payload?.get("uuid")?.jsonPrimitive?.content == agentUUID
            }
            assertNotNull(registerAction, "Expected CORE_REGISTER_IDENTITY to be dispatched for agent uuid=$agentUUID")
            assertEquals("Alpha", registerAction.payload?.get("name")?.jsonPrimitive?.content)
        }
    }

    // ========================================================================
    // 2. resolveAgentId — flexible resolution paths
    //
    // These tests register only the agent identity (no session) and prove
    // resolution succeeded by asserting:
    //   (a) AGENT_ACTION_RESULT with success=true (dispatched after resolveAgentId)
    //   (b) SET_STATUS error mentions "Session UUID" (proves pipeline got past
    //       resolution to the session validation guard — if resolution had failed,
    //       the error would say "not found" and ACTION_RESULT would have success=false)
    // ========================================================================

    @Test
    fun `resolveAgentId resolves agent by UUID`() = runTest {
        val feature = AgentRuntimeFeature(platform, scope)

        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(FileSystemFeature(platform))
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(uid(agentUUID) to agent),
                resources = emptyList()
            ))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            registerAgentIdentity(harness, agentUUID, "Alpha")
            harness.store.processedActions.clear()

            // ACT: Dispatch INITIATE_TURN using the agent's UUID
            harness.store.dispatch("core", Action(
                ActionRegistry.Names.AGENT_INITIATE_TURN,
                buildJsonObject { put("agentId", agentUUID) }
            ))

            // ASSERT: resolveAgentId succeeded → ACTION_RESULT with success=true
            val successResult = harness.processedActions.find {
                it.name == ActionRegistry.Names.AGENT_ACTION_RESULT &&
                        it.payload?.get("success")?.jsonPrimitive?.boolean == true
            }
            assertNotNull(successResult,
                "Expected AGENT_ACTION_RESULT with success=true after resolveAgentId by UUID")
            assertTrue(successResult.payload?.get("summary")?.jsonPrimitive?.content?.contains("Alpha") == true,
                "ACTION_RESULT summary should mention agent name 'Alpha'")

            // Pipeline got past resolution → session guard fired (expected in this T2 setup)
            val errorStatus = harness.processedActions.find {
                it.name == ActionRegistry.Names.AGENT_SET_STATUS &&
                        it.payload?.get("status")?.jsonPrimitive?.content == "ERROR"
            }
            assertNotNull(errorStatus, "Expected SET_STATUS ERROR (from session guard, not from resolution)")
            val errorMsg = errorStatus.payload?.get("error")?.jsonPrimitive?.content ?: ""
            assertTrue(errorMsg.contains("sessions are in the registry"),
                "Error should be about session registry (not agent resolution), got: $errorMsg")
        }
    }

    @Test
    fun `resolveAgentId resolves agent by display name (case-insensitive)`() = runTest {
        val feature = AgentRuntimeFeature(platform, scope)

        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(FileSystemFeature(platform))
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(uid(agentUUID) to agent),
                resources = emptyList()
            ))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            registerAgentIdentity(harness, agentUUID, "Alpha")
            harness.store.processedActions.clear()

            // ACT: Dispatch using case-mismatched display name
            harness.store.dispatch("core", Action(
                ActionRegistry.Names.AGENT_INITIATE_TURN,
                buildJsonObject { put("agentId", "alpha") }
            ))

            // ASSERT: Resolution succeeded (success=true, error is about session not agent)
            val successResult = harness.processedActions.find {
                it.name == ActionRegistry.Names.AGENT_ACTION_RESULT &&
                        it.payload?.get("success")?.jsonPrimitive?.boolean == true
            }
            assertNotNull(successResult,
                "Expected AGENT_ACTION_RESULT with success=true after resolveAgentId by name 'alpha'")

            val errorStatus = harness.processedActions.find {
                it.name == ActionRegistry.Names.AGENT_SET_STATUS &&
                        it.payload?.get("status")?.jsonPrimitive?.content == "ERROR"
            }
            val errorMsg = errorStatus?.payload?.get("error")?.jsonPrimitive?.content ?: ""
            assertTrue(errorMsg.contains("sessions are in the registry"),
                "Error should be about session registry (not agent resolution), got: $errorMsg")
        }
    }

    @Test
    fun `resolveAgentId resolves agent by full handle`() = runTest {
        val feature = AgentRuntimeFeature(platform, scope)

        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(FileSystemFeature(platform))
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(uid(agentUUID) to agent),
                resources = emptyList()
            ))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            registerAgentIdentity(harness, agentUUID, "Alpha")
            val registeredAgent = assertRegistered(harness, agentUUID, "agent")
            harness.store.processedActions.clear()

            // ACT: Dispatch using the agent's full registered handle
            harness.store.dispatch("core", Action(
                ActionRegistry.Names.AGENT_INITIATE_TURN,
                buildJsonObject { put("agentId", registeredAgent.handle) }
            ))

            // ASSERT: Resolution succeeded
            val successResult = harness.processedActions.find {
                it.name == ActionRegistry.Names.AGENT_ACTION_RESULT &&
                        it.payload?.get("success")?.jsonPrimitive?.boolean == true
            }
            assertNotNull(successResult,
                "Expected AGENT_ACTION_RESULT with success=true after resolveAgentId by handle '${registeredAgent.handle}'")

            val errorStatus = harness.processedActions.find {
                it.name == ActionRegistry.Names.AGENT_SET_STATUS &&
                        it.payload?.get("status")?.jsonPrimitive?.content == "ERROR"
            }
            val errorMsg = errorStatus?.payload?.get("error")?.jsonPrimitive?.content ?: ""
            assertTrue(errorMsg.contains("sessions are in the registry"),
                "Error should be about session registry (not agent resolution), got: $errorMsg")
        }
    }

    @Test
    fun `resolveAgentId resolves agent by localHandle`() = runTest {
        val feature = AgentRuntimeFeature(platform, scope)

        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(FileSystemFeature(platform))
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(uid(agentUUID) to agent),
                resources = emptyList()
            ))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            registerAgentIdentity(harness, agentUUID, "Alpha")
            val registeredAgent = assertRegistered(harness, agentUUID, "agent")
            harness.store.processedActions.clear()

            // ACT: Dispatch using the agent's localHandle
            harness.store.dispatch("core", Action(
                ActionRegistry.Names.AGENT_INITIATE_TURN,
                buildJsonObject { put("agentId", registeredAgent.localHandle) }
            ))

            // ASSERT: Resolution succeeded
            val successResult = harness.processedActions.find {
                it.name == ActionRegistry.Names.AGENT_ACTION_RESULT &&
                        it.payload?.get("success")?.jsonPrimitive?.boolean == true
            }
            assertNotNull(successResult,
                "Expected AGENT_ACTION_RESULT with success=true after resolveAgentId by localHandle '${registeredAgent.localHandle}'")

            val errorStatus = harness.processedActions.find {
                it.name == ActionRegistry.Names.AGENT_SET_STATUS &&
                        it.payload?.get("status")?.jsonPrimitive?.content == "ERROR"
            }
            val errorMsg = errorStatus?.payload?.get("error")?.jsonPrimitive?.content ?: ""
            assertTrue(errorMsg.contains("sessions are in the registry"),
                "Error should be about session registry (not agent resolution), got: $errorMsg")
        }
    }

    // ========================================================================
    // 3. resolveAgentId — failure case with suggestions
    // ========================================================================

    @Test
    fun `resolveAgentId publishes ACTION_RESULT error with suggestions for unknown agent`() = runTest {
        val feature = AgentRuntimeFeature(platform, scope)

        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(FileSystemFeature(platform))
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(uid(agentUUID) to agent),
                resources = emptyList()
            ))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // Register "Alpha" so it appears as a suggestion
            registerAgentIdentity(harness, agentUUID, "Alpha")
            harness.store.processedActions.clear()

            // ACT: Dispatch INITIATE_TURN with a non-existent agent name
            harness.store.dispatch("core", Action(
                ActionRegistry.Names.AGENT_INITIATE_TURN,
                buildJsonObject {
                    put("agentId", "nonexistent-agent-xyz")
                    put("correlationId", "test-corr-1")
                }
            ))

            // ASSERT: ACTION_RESULT with error dispatched (resolveAgentId failed)
            val errorResult = harness.processedActions.find {
                it.name == ActionRegistry.Names.AGENT_ACTION_RESULT &&
                        it.payload?.get("success")?.jsonPrimitive?.boolean == false
            }
            assertNotNull(errorResult, "Expected AGENT_ACTION_RESULT with success=false when agent not found")
            val errorMsg = errorResult.payload?.get("error")?.jsonPrimitive?.content ?: ""
            assertTrue(errorMsg.contains("not found"), "Error should mention 'not found', got: $errorMsg")

            // Should NOT have started the pipeline
            assertNull(
                harness.processedActions.find { it.name == ActionRegistry.Names.SESSION_REQUEST_LEDGER_CONTENT },
                "Pipeline should NOT start when resolveAgentId fails"
            )
        }
    }

    // ========================================================================
    // 4. startCognitiveCycle — session UUID validation guard
    //
    // These tests include SessionFeature so the "session" parent identity
    // exists in the registry and session child identities can be registered.
    // ========================================================================

    @Test
    fun `startCognitiveCycle aborts with ERROR when session UUID is not in registry`() = runTest {
        val feature = AgentRuntimeFeature(platform, scope)

        // Include SessionFeature (so "session" parent exists) but do NOT
        // pre-populate SessionState with our session — its UUID won't be
        // in the registry, so startCognitiveCycle should abort.
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(SessionFeature(platform, scope))
            .withFeature(FileSystemFeature(platform))
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(uid(agentUUID) to agent),
                resources = emptyList()
            ))
            .withInitialState("session", SessionState())
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // Register AGENT identity so resolveAgentId succeeds
            registerAgentIdentity(harness, agentUUID, "Alpha")
            harness.store.processedActions.clear()

            // ACT
            harness.store.dispatch("core", Action(
                ActionRegistry.Names.AGENT_INITIATE_TURN,
                buildJsonObject { put("agentId", agentUUID) }
            ))

            // ASSERT: Agent should be in ERROR status with "not in registry" message
            val state = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
            val status = state.agentStatuses[uid(agentUUID)]
            assertEquals(AgentStatus.ERROR, status?.status,
                "Agent should be in ERROR when session UUID is not in identity registry")
            assertTrue(status?.errorMessage?.contains("sessions are in the registry") == true,
                "Error message should mention sessions not in registry, got: ${status?.errorMessage}")

            // Should NOT have dispatched a ledger request
            assertNull(
                harness.processedActions.find { it.name == ActionRegistry.Names.SESSION_REQUEST_LEDGER_CONTENT },
                "Pipeline should NOT dispatch ledger request when session UUID is missing from registry"
            )
        }
    }

    @Test
    fun `startCognitiveCycle succeeds when both agent and session are registered`() = runTest {
        val feature = AgentRuntimeFeature(platform, scope)
        val sessionFeature = SessionFeature(platform, scope)

        // Include SessionFeature with the session pre-populated so its
        // identity gets registered during Store initialization.
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(sessionFeature)
            .withFeature(FileSystemFeature(platform))
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(uid(agentUUID) to agent),
                resources = testBuiltInResources()
            ))
            .withInitialState("session", SessionState(
                sessions = mapOf(sessionUUID to session)
            ))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // Register both agent and session identities explicitly.
            // Pre-populating SessionState does NOT auto-register child identities.
            registerAgentIdentity(harness, agentUUID, "Alpha")
            harness.store.dispatch("session", Action(
                ActionRegistry.Names.CORE_REGISTER_IDENTITY,
                buildJsonObject {
                    put("uuid", sessionUUID)
                    put("name", session.identity.name)
                }
            ))
            harness.store.processedActions.clear()

            // ACT
            harness.store.dispatch("core", Action(
                ActionRegistry.Names.AGENT_INITIATE_TURN,
                buildJsonObject { put("agentId", agentUUID) }
            ))

            // ASSERT: Pipeline should start — REQUEST_LEDGER_CONTENT dispatched
            val ledgerRequest = harness.processedActions.find {
                it.name == ActionRegistry.Names.SESSION_REQUEST_LEDGER_CONTENT
            }
            assertNotNull(ledgerRequest,
                "Pipeline should dispatch SESSION_REQUEST_LEDGER_CONTENT when both identities are registered. " +
                        "Registry: ${harness.store.state.value.identityRegistry.values.map { "${it.handle} (uuid=${it.uuid})" }}")

            // Agent should NOT be in ERROR
            val state = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
            val status = state.agentStatuses[uid(agentUUID)]
            assertNotEquals(AgentStatus.ERROR, status?.status,
                "Agent should not be in ERROR when identities are properly registered")
        }
    }

    // ========================================================================
    // 5. handleGatewayResponse — session UUID validation guard
    // ========================================================================

    @Test
    fun `handleGatewayResponse aborts with ERROR when session UUID is not in registry`() = runTest {
        val feature = AgentRuntimeFeature(platform, scope)

        // Pre-set agent into PROCESSING state as if a turn is in-flight
        val processingStatus = AgentStatusInfo(
            status = AgentStatus.PROCESSING,
            stagedTurnContext = listOf(
                GatewayMessage("user", "Hello", "user-1", "User", 1000L)
            ),
            contextGatheringStartedAt = platform.currentTimeMillis(),
            transientWorkspaceListing = JsonArray(emptyList())
        )

        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(FileSystemFeature(platform))
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(uid(agentUUID) to agent),
                agentStatuses = mapOf(uid(agentUUID) to processingStatus),
                resources = emptyList()
            ))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // Register AGENT but NOT session
            registerAgentIdentity(harness, agentUUID, "Alpha")
            harness.store.processedActions.clear()

            // ACT: Simulate gateway response arriving
            harness.store.dispatch("gateway", Action(
                name = ActionRegistry.Names.GATEWAY_RETURN_RESPONSE,
                payload = buildJsonObject {
                    put("correlationId", agentUUID)
                    put("rawContent", "Hello from the model!")
                    put("modelName", "mock-model")
                },
                targetRecipient = "agent"
            ))

            // ASSERT: Agent should be in ERROR because session UUID not in registry
            val state = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
            val status = state.agentStatuses[uid(agentUUID)]
            assertEquals(AgentStatus.ERROR, status?.status,
                "Agent should be in ERROR when target session UUID is not in registry during gateway response")
            assertTrue(status?.errorMessage?.contains("not in registry") == true ||
                    status?.errorMessage?.contains("session") == true,
                "Error message should reference session registry issue, got: ${status?.errorMessage}")

            // Should NOT have dispatched SESSION_POST
            assertNull(
                harness.processedActions.find { it.name == ActionRegistry.Names.SESSION_POST },
                "Should NOT post to session when session UUID is missing from registry"
            )
        }
    }

    // ========================================================================
    // 6. CORE_RETURN_REGISTER_IDENTITY → triggers saveAgentConfig
    // ========================================================================

    @Test
    fun `CORE_RETURN_REGISTER_IDENTITY triggers saveAgentConfig for the registered agent`() = runTest {
        val feature = AgentRuntimeFeature(platform, scope)

        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(FileSystemFeature(platform))
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(uid(agentUUID) to agent),
                resources = emptyList()
            ))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.processedActions.clear()

            // ACT: Simulate CoreFeature responding to a registration
            harness.store.dispatch("core", Action(
                name = ActionRegistry.Names.CORE_RETURN_REGISTER_IDENTITY,
                payload = buildJsonObject {
                    put("uuid", agentUUID)
                    put("handle", "agent.alpha")
                    put("localHandle", "alpha")
                    put("name", "Alpha")
                    put("parentHandle", "agent")
                },
                targetRecipient = "agent"
            ))

            // ASSERT: saveAgentConfig dispatched a FILESYSTEM_WRITE for agent.json
            val writeAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.FILESYSTEM_WRITE &&
                        it.payload?.get("path")?.jsonPrimitive?.content?.contains("agent.json") == true
            }
            assertNotNull(writeAction,
                "Expected FILESYSTEM_WRITE for agent.json after CORE_RETURN_REGISTER_IDENTITY")

            // Verify the written content has cognitiveState stripped (set to null/JsonNull)
            val writtenContent = writeAction.payload?.get("content")?.jsonPrimitive?.content
            assertNotNull(writtenContent, "FILESYSTEM_WRITE should have content")
            val parsedAgent = Json { ignoreUnknownKeys = true }.decodeFromString<AgentInstance>(writtenContent)
            assertTrue(parsedAgent.cognitiveState is JsonNull || parsedAgent.cognitiveState == null,
                "saveAgentConfig should strip cognitiveState; got: ${parsedAgent.cognitiveState}")
        }
    }

    // ========================================================================
    // 7. CORE_RETURN_UPDATE_IDENTITY → triggers saveAgentConfig
    // ========================================================================

    @Test
    fun `CORE_RETURN_UPDATE_IDENTITY triggers saveAgentConfig for the updated agent`() = runTest {
        val feature = AgentRuntimeFeature(platform, scope)

        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(FileSystemFeature(platform))
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(uid(agentUUID) to agent),
                resources = emptyList()
            ))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.processedActions.clear()

            // ACT: Simulate CoreFeature responding to an identity update (e.g. name change)
            harness.store.dispatch("core", Action(
                name = ActionRegistry.Names.CORE_RETURN_UPDATE_IDENTITY,
                payload = buildJsonObject {
                    put("uuid", agentUUID)
                    put("handle", "agent.alpha-renamed")
                    put("name", "Alpha Renamed")
                },
                targetRecipient = "agent"
            ))

            // ASSERT: saveAgentConfig dispatched
            val writeAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.FILESYSTEM_WRITE &&
                        it.payload?.get("path")?.jsonPrimitive?.content?.contains("agent.json") == true
            }
            assertNotNull(writeAction,
                "Expected FILESYSTEM_WRITE for agent.json after CORE_RETURN_UPDATE_IDENTITY")
        }
    }

    // ========================================================================
    // 8. AGENT_DELETE dispatches CORE_UNREGISTER_IDENTITY
    // ========================================================================

    @Test
    fun `AGENT_DELETE dispatches CORE_UNREGISTER_IDENTITY for the deleted agent`() = runTest {
        val feature = AgentRuntimeFeature(platform, scope)

        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(FileSystemFeature(platform))
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(uid(agentUUID) to agent),
                resources = emptyList()
            ))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // Register the agent so resolveAgentId succeeds for the DELETE handler
            registerAgentIdentity(harness, agentUUID, "Alpha")
            harness.store.processedActions.clear()

            // ACT
            harness.store.dispatch("core", Action(
                ActionRegistry.Names.AGENT_DELETE,
                buildJsonObject { put("agentId", agentUUID) }
            ))

            // ASSERT: CORE_UNREGISTER_IDENTITY dispatched with the agent's handle
            val unregisterAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.CORE_UNREGISTER_IDENTITY
            }
            assertNotNull(unregisterAction,
                "Expected CORE_UNREGISTER_IDENTITY to be dispatched when agent is deleted")
            // The handle should match the agent's identity handle
            val unregHandle = unregisterAction.payload?.get("handle")?.jsonPrimitive?.content
            assertEquals(agent.identityHandle.handle, unregHandle,
                "CORE_UNREGISTER_IDENTITY should carry the agent's handle")
        }
    }

    // ========================================================================
    // 9. AGENT_CREATE dispatches CORE_REGISTER_IDENTITY
    // ========================================================================

    @Test
    fun `AGENT_CREATE dispatches CORE_REGISTER_IDENTITY for the new agent`() = runTest {
        val feature = AgentRuntimeFeature(platform, scope)

        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(FileSystemFeature(platform))
            .withInitialState("agent", AgentRuntimeState(resources = emptyList()))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.processedActions.clear()

            // ACT: Create a new agent via AGENT_CREATE
            harness.store.dispatch("core", Action(
                ActionRegistry.Names.AGENT_CREATE,
                buildJsonObject {
                    put("name", "Beta")
                    put("modelProvider", "mock")
                    put("modelName", "mock-model")
                    put("cognitiveStrategyId", "agent.strategy.vanilla")
                }
            ))

            // ASSERT: CORE_REGISTER_IDENTITY dispatched for the new agent
            val registerAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.CORE_REGISTER_IDENTITY &&
                        it.payload?.get("name")?.jsonPrimitive?.content == "Beta"
            }
            assertNotNull(registerAction,
                "Expected CORE_REGISTER_IDENTITY dispatched for newly created agent 'Beta'")

            // The registration payload should have the new agent's UUID
            val registeredUUID = registerAction.payload?.get("uuid")?.jsonPrimitive?.content
            assertNotNull(registeredUUID, "Registration should include the agent's UUID")
        }
    }

    // ========================================================================
    // 10. AGENT_UPDATE_CONFIG dispatches CORE_UPDATE_IDENTITY on name change
    // ========================================================================

    @Test
    fun `AGENT_UPDATE_CONFIG dispatches CORE_UPDATE_IDENTITY when agent name changes`() = runTest {
        val feature = AgentRuntimeFeature(platform, scope)

        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(FileSystemFeature(platform))
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(uid(agentUUID) to agent),
                resources = emptyList()
            ))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            registerAgentIdentity(harness, agentUUID, "Alpha")
            harness.store.processedActions.clear()

            // ACT: Update agent name
            harness.store.dispatch("core", Action(
                ActionRegistry.Names.AGENT_UPDATE_CONFIG,
                buildJsonObject {
                    put("agentId", agentUUID)
                    put("name", "Alpha Renamed")
                }
            ))

            // ASSERT: CORE_UPDATE_IDENTITY dispatched with new name
            val updateAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.CORE_UPDATE_IDENTITY
            }
            assertNotNull(updateAction,
                "Expected CORE_UPDATE_IDENTITY dispatched when agent name changes")
            assertEquals("Alpha Renamed", updateAction.payload?.get("newName")?.jsonPrimitive?.content)
        }
    }
}