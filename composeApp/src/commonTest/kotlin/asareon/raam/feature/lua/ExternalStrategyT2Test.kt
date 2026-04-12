package asareon.raam.feature.lua

import asareon.raam.core.Action
import asareon.raam.core.IdentityHandle
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.fakes.FakePlatformDependencies
import asareon.raam.feature.agent.*
import asareon.raam.feature.core.AppLifecycle
import asareon.raam.feature.core.CoreState
import asareon.raam.test.TestEnvironment
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Tier 2 Contract Tests for the External Strategy Protocol.
 *
 * Mandate: Test the generic external strategy registration and turn execution
 * protocol between agent feature and external providers (Lua, future Python, etc.).
 * Tests both the agent-side (ExternalStrategyProxy, CognitivePipeline) and the
 * action bus protocol.
 */
class ExternalStrategyT2Test {

    private fun createHarness(
        platform: FakePlatformDependencies = FakePlatformDependencies("test")
    ): asareon.raam.test.TestHarness {
        return TestEnvironment.create()
            .withFeature(AgentRuntimeFeature(platform, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)
    }

    // ========================================================================
    // External Strategy Registration
    // ========================================================================

    @Test
    fun `REGISTER_EXTERNAL_STRATEGY creates proxy in CognitiveStrategyRegistry`() {
        val platform = FakePlatformDependencies("test")
        val harness = createHarness(platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("lua", Action(
                name = ActionRegistry.Names.AGENT_REGISTER_EXTERNAL_STRATEGY,
                payload = buildJsonObject {
                    put("strategyId", "agent.strategy.lua")
                    put("displayName", "Lua Script")
                    put("featureHandle", "lua")
                    put("resourceSlots", buildJsonArray {
                        add(buildJsonObject {
                            put("slotId", "system_instruction")
                            put("type", "SYSTEM_INSTRUCTION")
                            put("displayName", "System Instructions")
                            put("isRequired", false)
                        })
                    })
                    put("configFields", buildJsonArray {
                        add(buildJsonObject {
                            put("key", "outputSessionId")
                            put("type", "OUTPUT_SESSION")
                            put("displayName", "Output Session")
                        })
                    })
                    put("initialState", buildJsonObject {
                        put("phase", "READY")
                    })
                }
            ))

            // Verify strategy is registered
            val handle = IdentityHandle("agent.strategy.lua")
            assertTrue(CognitiveStrategyRegistry.isRegistered(handle),
                "Lua strategy should be registered after REGISTER_EXTERNAL_STRATEGY")

            val strategy = CognitiveStrategyRegistry.get(handle)
            assertTrue(strategy is ExternalStrategyProxy, "Should be an ExternalStrategyProxy")
            assertEquals("Lua Script", strategy.displayName)
            assertEquals("lua", (strategy as ExternalStrategyProxy).featureHandle)

            // Verify resource slots
            val slots = strategy.getResourceSlots()
            assertEquals(1, slots.size)
            assertEquals("system_instruction", slots[0].slotId)

            // Verify config fields
            val fields = strategy.getConfigFields()
            assertEquals(1, fields.size)
            assertEquals("outputSessionId", fields[0].key)

            // Verify confirmation response was dispatched
            val response = harness.processedActions.find {
                it.name == ActionRegistry.Names.AGENT_RETURN_STRATEGY_REGISTERED
            }
            assertNotNull(response, "Should send registration confirmation")
            assertEquals(true, response.payload?.get("success")?.jsonPrimitive?.booleanOrNull)
        }
    }

    @Test
    fun `REGISTER_EXTERNAL_STRATEGY with missing strategyId logs error`() {
        val platform = FakePlatformDependencies("test")
        val harness = createHarness(platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("lua", Action(
                name = ActionRegistry.Names.AGENT_REGISTER_EXTERNAL_STRATEGY,
                payload = buildJsonObject {
                    put("displayName", "Missing Strategy Id")
                    put("featureHandle", "lua")
                }
            ))

            assertTrue(
                platform.capturedLogs.any {
                    it.level == asareon.raam.util.LogLevel.ERROR && it.message.contains("strategyId")
                },
                "Should log error for missing strategyId"
            )
        }
    }

    @Test
    fun `REGISTER_EXTERNAL_STRATEGY with missing featureHandle logs error`() {
        val platform = FakePlatformDependencies("test")
        val harness = createHarness(platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("lua", Action(
                name = ActionRegistry.Names.AGENT_REGISTER_EXTERNAL_STRATEGY,
                payload = buildJsonObject {
                    put("strategyId", "agent.strategy.test")
                    put("displayName", "Test")
                }
            ))

            assertTrue(
                platform.capturedLogs.any {
                    it.level == asareon.raam.util.LogLevel.ERROR && it.message.contains("featureHandle")
                },
                "Should log error for missing featureHandle"
            )
        }
    }

    // ========================================================================
    // ExternalStrategyProxy behavior
    // ========================================================================

    @Test
    fun `ExternalStrategyProxy buildPrompt returns a valid PromptBuilder`() {
        val proxy = ExternalStrategyProxy(
            identityHandle = IdentityHandle("agent.strategy.test"),
            displayName = "Test",
            featureHandle = "test",
            declaredSlots = emptyList(),
            declaredConfigFields = emptyList(),
            declaredInitialState = JsonNull
        )

        val context = AgentTurnContext(agentName = "test-agent", resolvedResources = emptyMap())
        val builder = proxy.buildPrompt(context, JsonNull)
        assertNotNull(builder, "buildPrompt should return a non-null PromptBuilder")
    }

    @Test
    fun `ExternalStrategyProxy postProcessResponse passes through`() {
        val proxy = ExternalStrategyProxy(
            identityHandle = IdentityHandle("agent.strategy.test"),
            displayName = "Test",
            featureHandle = "test",
            declaredSlots = emptyList(),
            declaredConfigFields = emptyList(),
            declaredInitialState = JsonNull
        )

        val state = buildJsonObject { put("phase", "ACTIVE") }
        val result = proxy.postProcessResponse("response text", state)

        assertEquals(SentinelAction.PROCEED, result.action)
        assertSame(state, result.newState, "State should pass through unchanged")
    }

    @Test
    fun `ExternalStrategyProxy does not request additional context`() {
        val proxy = ExternalStrategyProxy(
            identityHandle = IdentityHandle("agent.strategy.test"),
            displayName = "Test",
            featureHandle = "test",
            declaredSlots = emptyList(),
            declaredConfigFields = emptyList(),
            declaredInitialState = JsonNull
        )

        val agent = createMinimalAgent()
        assertFalse(proxy.needsAdditionalContext(agent))
    }

    @Test
    fun `ExternalStrategyProxy accepts all NVRAM keys`() {
        val proxy = ExternalStrategyProxy(
            identityHandle = IdentityHandle("agent.strategy.test"),
            displayName = "Test",
            featureHandle = "test",
            declaredSlots = emptyList(),
            declaredConfigFields = emptyList(),
            declaredInitialState = JsonNull
        )

        assertNull(proxy.getValidNvramKeys(), "Should return null (accept all keys)")
    }

    @Test
    fun `ExternalStrategyProxy validateConfig enforces output session in subscribed list`() {
        val proxy = ExternalStrategyProxy(
            identityHandle = IdentityHandle("agent.strategy.test"),
            displayName = "Test",
            featureHandle = "test",
            declaredSlots = emptyList(),
            declaredConfigFields = emptyList(),
            declaredInitialState = JsonNull
        )

        val agent = createMinimalAgent().copy(
            outputSessionId = asareon.raam.core.IdentityUUID("session-orphan"),
            subscribedSessionIds = listOf(asareon.raam.core.IdentityUUID("session-1"))
        )

        val validated = proxy.validateConfig(agent)
        assertEquals(asareon.raam.core.IdentityUUID("session-1"), validated.outputSessionId,
            "Output should be corrected to first subscribed session")
    }

    // ========================================================================
    // External Turn Result Reducer
    // ========================================================================

    @Test
    fun `EXTERNAL_TURN_RESULT stores payload in transientExternalTurnResult`() {
        val platform = FakePlatformDependencies("test")
        val agentUUID = "00000000-0000-0000-0000-000000000001"
        val agentState = AgentRuntimeState(
            agentStatuses = mapOf(
                asareon.raam.core.IdentityUUID(agentUUID) to AgentStatusInfo()
            )
        )

        val result = AgentRuntimeReducer.reduce(
            agentState,
            Action(ActionRegistry.Names.AGENT_EXTERNAL_TURN_RESULT, buildJsonObject {
                put("correlationId", agentUUID)
                put("mode", "advance")
                put("systemPrompt", "modified prompt")
            }),
            platform
        )

        val statusInfo = result.agentStatuses[asareon.raam.core.IdentityUUID(agentUUID)]
        assertNotNull(statusInfo?.transientExternalTurnResult,
            "Should store turn result in status info")
        assertEquals("advance",
            statusInfo.transientExternalTurnResult?.get("mode")?.jsonPrimitive?.contentOrNull)
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun createMinimalAgent(): AgentInstance {
        return AgentInstance(
            identity = asareon.raam.core.Identity(
                uuid = "00000000-0000-0000-0000-000000000001",
                handle = "agent.test-bot",
                localHandle = "test-bot",
                name = "Test Bot"
            ),
            modelProvider = "test",
            modelName = "test-model"
        )
    }

    @AfterTest
    fun cleanup() {
        CognitiveStrategyRegistry.clearForTesting()
    }
}
