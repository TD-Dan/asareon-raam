package app.auf.feature.gateway

import app.auf.core.Action
import app.auf.core.generated.ActionRegistry
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreState
import app.auf.feature.settings.SettingsFeature
import app.auf.test.TestEnvironment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Tier 2 Core Test for GatewayFeature.
 *
 * Mandate (P-TEST-001, T2): To test the feature's reducer and onAction handlers working
 * together within a realistic TestEnvironment, interacting with fake providers.
 */
class GatewayFeatureT2CoreTest {

    // A test-only fake provider for use within this test class.
    private class FakeUniversalGatewayProvider(
        override val id: String,
        private val modelsToReturn: List<String> = listOf("model-1", "model-2"),
        private val delayMillis: Long = 0L // Enable simulation of long-running requests
    ) : UniversalGatewayProvider {
        var registerSettingsCallCount = 0
        var listAvailableModelsCallCount = 0
        var generateContentCallCount = 0
        var lastRequest: GatewayRequest? = null
        val apiKeySettingKey = "gateway.$id.apiKey"

        override fun registerSettings(dispatch: (Action) -> Unit) {
            registerSettingsCallCount++
            dispatch(Action(ActionRegistry.Names.SETTINGS_ADD, buildJsonObject {
                put("key", apiKeySettingKey); put("type", "STRING"); put("label", "$id Key")
                put("description", "API Key for $id"); put("section", "API Keys"); put("defaultValue", "")
            }))
        }

        override suspend fun listAvailableModels(settings: Map<String, String>): List<String> {
            val apiKey = settings[apiKeySettingKey].orEmpty()
            if (apiKey.isBlank()) return emptyList()
            listAvailableModelsCallCount++
            return modelsToReturn
        }

        override suspend fun generateContent(request: GatewayRequest, settings: Map<String, String>): GatewayResponse {
            generateContentCallCount++
            lastRequest = request
            if (delayMillis > 0) delay(delayMillis) // Simulate work
            return GatewayResponse("Response from $id", null, request.correlationId)
        }

        override suspend fun generatePreview(request: GatewayRequest, settings: Map<String, String>): String {
            return "Fake Preview for ${request.modelName}"
        }
    }

    private lateinit var fakeProvider1: FakeUniversalGatewayProvider
    private lateinit var fakeProvider2: FakeUniversalGatewayProvider
    private lateinit var testScope: TestScope

    @BeforeTest
    fun setup() {
        testScope = TestScope()
        fakeProvider1 = FakeUniversalGatewayProvider("provider-1")
        fakeProvider2 = FakeUniversalGatewayProvider("provider-2", modelsToReturn = listOf("gpt-x"), delayMillis = 1000L)
    }

    private fun createHarness(
        testScope: TestScope,
        initialLifecycle: AppLifecycle = AppLifecycle.RUNNING
    ): app.auf.test.TestHarness {
        // FIX: Create a single shared platform instance
        val platform = FakePlatformDependencies("test")

        val settingsFeature = SettingsFeature(platform)
        val gatewayFeature = GatewayFeature(
            platform,
            testScope,
            listOf(fakeProvider1, fakeProvider2)
        )
        return TestEnvironment.create()
            .withFeature(gatewayFeature)
            .withFeature(settingsFeature)
            .withInitialState("core", CoreState(lifecycle = initialLifecycle))
            // FIX: Pass the shared platform to the builder
            .build(scope = testScope, platform = platform)
    }

    @Test
    fun `on INITIALIZING registers settings for all providers`() = testScope.runTest {
        val harness = createHarness(this, initialLifecycle = AppLifecycle.BOOTING)
        harness.store.dispatch("system", Action(ActionRegistry.Names.SYSTEM_INITIALIZING))

        harness.runAndLogOnFailure {
            assertEquals(1, fakeProvider1.registerSettingsCallCount)
            assertEquals(1, fakeProvider2.registerSettingsCallCount)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `on STARTING refreshes models for all providers with API keys`() = testScope.runTest {
        val harness = createHarness(this, initialLifecycle = AppLifecycle.INITIALIZING)
        harness.store.dispatch("settings", Action(ActionRegistry.Names.SETTINGS_LOADED, buildJsonObject {
            put("gateway.provider-1.apiKey", "k1")
            put("gateway.provider-2.apiKey", "k2")
        }))

        harness.store.dispatch("system", Action(ActionRegistry.Names.SYSTEM_STARTING))
        runCurrent()

        harness.runAndLogOnFailure {
            assertEquals(1, fakeProvider1.listAvailableModelsCallCount)
            assertEquals(1, fakeProvider2.listAvailableModelsCallCount)

            val finalState = harness.store.state.value.featureStates["gateway"] as GatewayState
            assertEquals(listOf("model-1", "model-2"), finalState.availableModels["provider-1"])
            assertEquals(listOf("gpt-x"), finalState.availableModels["provider-2"])
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `on settings VALUE_CHANGED refreshes models for the correct provider`() = testScope.runTest {
        val harness = createHarness(this, initialLifecycle = AppLifecycle.INITIALIZING)
        harness.store.dispatch("settings", Action(ActionRegistry.Names.SETTINGS_LOADED, buildJsonObject {
            put("gateway.provider-1.apiKey", "k1")
            put("gateway.provider-2.apiKey", "k2")
        }))
        harness.store.dispatch("system", Action(ActionRegistry.Names.SYSTEM_STARTING))
        runCurrent()

        val valueChangedAction = Action(ActionRegistry.Names.SETTINGS_VALUE_CHANGED, buildJsonObject {
            put("key", "gateway.provider-2.apiKey"); put("value", "new-key")
        })
        harness.store.dispatch("settings", valueChangedAction)
        runCurrent()

        harness.runAndLogOnFailure {
            assertEquals(1, fakeProvider1.listAvailableModelsCallCount, "Provider 1 should not be refreshed.")
            assertEquals(2, fakeProvider2.listAvailableModelsCallCount, "Provider 2 should be refreshed.")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `on GENERATE_CONTENT routes to correct provider and delivers response privately`() = testScope.runTest {
        val harness = createHarness(this)
        harness.store.dispatch("settings", Action(ActionRegistry.Names.SETTINGS_LOADED, buildJsonObject {
            put("gateway.provider-2.apiKey", "k2")
        }))
        val originatorId = "agent"
        val correlationId = "test-turn-123"
        val message = GatewayMessage("user", "Test", "user-1", "User", 1L)
        val action = Action(ActionRegistry.Names.GATEWAY_GENERATE_CONTENT, buildJsonObject {
            put("providerId", "provider-2"); put("modelName", "gpt-x")
            put("correlationId", correlationId)
            put("contents", buildJsonArray { add(Json.encodeToJsonElement(message)) })
        })

        harness.store.dispatch(originatorId, action)

        // Advance time to allow the "network call" (fake provider delay) to finish
        advanceTimeBy(1001)
        runCurrent()

        harness.runAndLogOnFailure {
            assertEquals(0, fakeProvider1.generateContentCallCount)
            assertEquals(1, fakeProvider2.generateContentCallCount)

            // Phase 3 migration: GatewayFeature now dispatches targeted Actions via
            // deferredDispatch, not the deprecated deliverPrivateData. Assert on
            // processedActions instead.
            val responseAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.GATEWAY_RETURN_RESPONSE
            }
            assertNotNull(responseAction, "A targeted RETURN_RESPONSE action should have been dispatched.")
            assertEquals(originatorId, responseAction.targetRecipient, "targetRecipient should be the original dispatcher.")
            assertEquals(correlationId, responseAction.payload?.get("correlationId")?.jsonPrimitive?.content)
            // Verify the response content from the fake provider is present
            assertNotNull(responseAction.payload?.get("rawContent")?.jsonPrimitive?.content)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `PREPARE_PREVIEW uses polymorphic generatePreview`() = testScope.runTest {
        val harness = createHarness(this)
        val originatorId = "agent"
        val correlationId = "test-preview-456"
        val message = GatewayMessage("user", "Preview Test", "user-1", "User", 1L)
        val systemPrompt = "You are a test assistant."

        val action = Action(ActionRegistry.Names.GATEWAY_PREPARE_PREVIEW, buildJsonObject {
            put("providerId", "provider-1"); put("modelName", "model-1")
            put("correlationId", correlationId); put("systemPrompt", systemPrompt)
            put("contents", buildJsonArray { add(Json.encodeToJsonElement(message)) })
        })

        // ACT
        harness.store.dispatch(originatorId, action)
        runCurrent()

        // ASSERT
        harness.runAndLogOnFailure {
            // Phase 3 migration: Assert on processedActions for the targeted action.
            val responseAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.GATEWAY_RETURN_PREVIEW
            }
            assertNotNull(responseAction, "A targeted RETURN_PREVIEW action should have been dispatched.")
            assertEquals(originatorId, responseAction.targetRecipient, "targetRecipient should be the original dispatcher.")

            val rawJson = responseAction.payload?.get("rawRequestJson")?.jsonPrimitive?.content
            // Verify we got the string from FakeProvider.generatePreview, NOT empty map
            assertEquals("Fake Preview for model-1", rawJson)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `CANCEL_REQUEST cancels the job and cleans up via internal action`() = testScope.runTest {
        val harness = createHarness(this)
        harness.store.dispatch("settings", Action(ActionRegistry.Names.SETTINGS_LOADED, buildJsonObject {
            put("gateway.provider-2.apiKey", "k2")
        }))
        val correlationId = "cancel-test-123"

        // 1. Start a long running request (Provider 2 has 1000ms delay)
        val generateAction = Action(ActionRegistry.Names.GATEWAY_GENERATE_CONTENT, buildJsonObject {
            put("providerId", "provider-2"); put("modelName", "gpt-x")
            put("correlationId", correlationId)
            put("contents", buildJsonArray { add(Json.encodeToJsonElement(GatewayMessage("user", "hi", "u1", "U", 1L))) })
        })
        harness.store.dispatch("agent", generateAction)
        runCurrent() // Launches coroutine, effectively "in flight"

        // 2. Dispatch Cancel
        val cancelAction = Action(ActionRegistry.Names.GATEWAY_CANCEL_REQUEST, buildJsonObject {
            put("correlationId", correlationId)
        })
        harness.store.dispatch("system", cancelAction)
        runCurrent()

        // ASSERT
        harness.runAndLogOnFailure {
            // 3. Verify Log indicates cancellation
            assertTrue(
                harness.platform.capturedLogs.any { it.message.contains("Cancelling gateway request") && it.message.contains(correlationId) },
                "Log should show cancellation"
            )

            // 4. Advance time past the delay to ensure the job *would* have finished if not cancelled
            advanceTimeBy(1001)
            runCurrent()

            // 5. Verify the internal cleanup action was dispatched (triggered by invokeOnCompletion)
            val cleanupAction = harness.processedActions.find { it.name == ActionRegistry.Names.GATEWAY_REQUEST_COMPLETED }
            assertNotNull(cleanupAction, "Cleanup action should be dispatched")
            assertEquals(correlationId, cleanupAction.payload?.get("correlationId")?.jsonPrimitive?.content)

            // 6. Verify NO response envelope was sent (job was cancelled)
            val responseAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.GATEWAY_RETURN_RESPONSE
            }
            assertNull(responseAction, "No RETURN_RESPONSE action should be dispatched for a cancelled request")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `GENERATE_CONTENT with unknown provider logs error and does not crash`() = testScope.runTest {
        val harness = createHarness(this)
        val action = Action(ActionRegistry.Names.GATEWAY_GENERATE_CONTENT, buildJsonObject {
            put("providerId", "nonexistent-provider"); put("modelName", "some-model")
            put("correlationId", "corr-unknown")
            put("contents", buildJsonArray { add(Json.encodeToJsonElement(GatewayMessage("user", "hi", "u1", "U", 1L))) })
        })

        // ACT
        harness.store.dispatch("gateway", action)
        runCurrent()

        // ASSERT
        harness.runAndLogOnFailure {
            assertEquals(0, fakeProvider1.generateContentCallCount, "No provider should be called.")
            assertEquals(0, fakeProvider2.generateContentCallCount, "No provider should be called.")
            val errorLog = harness.platform.capturedLogs.find {
                it.level == app.auf.util.LogLevel.ERROR && it.message.contains("nonexistent-provider")
            }
            assertNotNull(errorLog, "An error should be logged for an unknown provider ID.")

            val responseAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.GATEWAY_RETURN_RESPONSE
            }
            assertNull(responseAction, "No response should be dispatched for an unknown provider.")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `REQUEST_AVAILABLE_MODELS broadcasts current model state`() = testScope.runTest {
        val harness = createHarness(this, initialLifecycle = AppLifecycle.INITIALIZING)
        // Pre-load some API keys and trigger model refresh
        harness.store.dispatch("settings", Action(ActionRegistry.Names.SETTINGS_LOADED, buildJsonObject {
            put("gateway.provider-1.apiKey", "k1")
        }))
        harness.store.dispatch("system", Action(ActionRegistry.Names.SYSTEM_STARTING))
        runCurrent()

        // ACT: Request the current model list
        harness.store.dispatch("gateway", Action(ActionRegistry.Names.GATEWAY_REQUEST_AVAILABLE_MODELS))
        runCurrent()

        // ASSERT
        harness.runAndLogOnFailure {
            val updateAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.GATEWAY_AVAILABLE_MODELS_UPDATED
            }
            assertNotNull(updateAction, "AVAILABLE_MODELS_UPDATED should be broadcast in response.")
            assertNotNull(updateAction.payload, "Payload should contain the models map.")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `GENERATE_CONTENT passes system prompt through to provider`() = testScope.runTest {
        val harness = createHarness(this)
        harness.store.dispatch("settings", Action(ActionRegistry.Names.SETTINGS_LOADED, buildJsonObject {
            put("gateway.provider-1.apiKey", "k1")
        }))
        val systemPrompt = "You are a test assistant with special instructions."
        val action = Action(ActionRegistry.Names.GATEWAY_GENERATE_CONTENT, buildJsonObject {
            put("providerId", "provider-1"); put("modelName", "model-1")
            put("correlationId", "corr-sysprompt")
            put("systemPrompt", systemPrompt)
            put("contents", buildJsonArray { add(Json.encodeToJsonElement(GatewayMessage("user", "hi", "u1", "U", 1L))) })
        })

        // ACT
        harness.store.dispatch("agent", action)
        runCurrent()

        // ASSERT
        harness.runAndLogOnFailure {
            assertEquals(1, fakeProvider1.generateContentCallCount)
            val lastRequest = fakeProvider1.lastRequest
            assertNotNull(lastRequest, "Provider should have received the request.")
            assertEquals(systemPrompt, lastRequest.systemPrompt, "System prompt should be passed through to the provider.")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `MODELS_UPDATED with empty list removes provider from state rather than storing empty entry`() = testScope.runTest {
        // ARRANGE: Start with provider-1 already in state with models.
        val harness = createHarness(this, initialLifecycle = AppLifecycle.INITIALIZING)
        harness.store.dispatch("settings", Action(ActionRegistry.Names.SETTINGS_LOADED, buildJsonObject {
            put("gateway.provider-1.apiKey", "k1")
        }))
        harness.store.dispatch("system", Action(ActionRegistry.Names.SYSTEM_STARTING))
        runCurrent()

        val stateAfterLoad = harness.store.state.value.featureStates["gateway"] as GatewayState
        assertEquals(listOf("model-1", "model-2"), stateAfterLoad.availableModels["provider-1"],
            "Pre-condition: provider-1 should have models in state before key is cleared.")

        // ACT: Simulate user clearing the API key — provider returns emptyList() when key is blank.
        // The FakeUniversalGatewayProvider returns emptyList() when its API key is blank (line 54).
        harness.store.dispatch("settings", Action(ActionRegistry.Names.SETTINGS_VALUE_CHANGED, buildJsonObject {
            put("key", "gateway.provider-1.apiKey")
            put("value", "") // blank key → listAvailableModels returns emptyList()
        }))
        runCurrent()

        // ASSERT: Provider should be ejected from availableModels entirely, not stored as [].
        harness.runAndLogOnFailure {
            val finalState = harness.store.state.value.featureStates["gateway"] as GatewayState
            assertFalse(
                finalState.availableModels.containsKey("provider-1"),
                "provider-1 should be removed from availableModels when its model list is empty. " +
                        "Storing an empty entry would cause it to appear (disabled) in the UI provider selector."
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `CANCEL_REQUEST for unknown correlationId logs warning`() = testScope.runTest {
        val harness = createHarness(this)
        val cancelAction = Action(ActionRegistry.Names.GATEWAY_CANCEL_REQUEST, buildJsonObject {
            put("correlationId", "nonexistent-correlation-id")
        })

        // ACT
        harness.store.dispatch("system", cancelAction)
        runCurrent()

        // ASSERT
        harness.runAndLogOnFailure {
            val warnLog = harness.platform.capturedLogs.find {
                it.level == app.auf.util.LogLevel.WARN && it.message.contains("nonexistent-correlation-id")
            }
            assertNotNull(warnLog, "A warning should be logged when cancelling an unknown correlationId.")
        }
    }
}