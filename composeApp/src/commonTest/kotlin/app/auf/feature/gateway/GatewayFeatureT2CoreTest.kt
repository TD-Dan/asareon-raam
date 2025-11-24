package app.auf.feature.gateway

import app.auf.core.Action
import app.auf.core.generated.ActionNames
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
            dispatch(Action(ActionNames.SETTINGS_ADD, buildJsonObject {
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
        harness.store.dispatch("system", Action(ActionNames.SYSTEM_PUBLISH_INITIALIZING))

        harness.runAndLogOnFailure {
            assertEquals(1, fakeProvider1.registerSettingsCallCount)
            assertEquals(1, fakeProvider2.registerSettingsCallCount)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `on STARTING refreshes models for all providers with API keys`() = testScope.runTest {
        val harness = createHarness(this, initialLifecycle = AppLifecycle.INITIALIZING)
        harness.store.dispatch("settings", Action(ActionNames.SETTINGS_PUBLISH_LOADED, buildJsonObject {
            put("gateway.provider-1.apiKey", "k1")
            put("gateway.provider-2.apiKey", "k2")
        }))

        harness.store.dispatch("system", Action(ActionNames.SYSTEM_PUBLISH_STARTING))
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
        harness.store.dispatch("settings", Action(ActionNames.SETTINGS_PUBLISH_LOADED, buildJsonObject {
            put("gateway.provider-1.apiKey", "k1")
            put("gateway.provider-2.apiKey", "k2")
        }))
        harness.store.dispatch("system", Action(ActionNames.SYSTEM_PUBLISH_STARTING))
        runCurrent()

        val valueChangedAction = Action(ActionNames.SETTINGS_PUBLISH_VALUE_CHANGED, buildJsonObject {
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
        harness.store.dispatch("settings", Action(ActionNames.SETTINGS_PUBLISH_LOADED, buildJsonObject {
            put("gateway.provider-2.apiKey", "k2")
        }))
        val originatorId = "agent-feature-1"
        val correlationId = "test-turn-123"
        val message = GatewayMessage("user", "Test", "user-1", "User", 1L)
        val action = Action(ActionNames.GATEWAY_GENERATE_CONTENT, buildJsonObject {
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

            val privateData = harness.deliveredPrivateData.firstOrNull()
            assertNotNull(privateData)
            assertEquals(originatorId, privateData.recipient)
            assertEquals("gateway.response.RESPONSE", privateData.envelope.type)
            assertEquals(correlationId, privateData.envelope.payload["correlationId"]?.jsonPrimitive?.content)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `PREPARE_PREVIEW uses polymorphic generatePreview`() = testScope.runTest {
        val harness = createHarness(this)
        val originatorId = "agent-feature-1"
        val correlationId = "test-preview-456"
        val message = GatewayMessage("user", "Preview Test", "user-1", "User", 1L)
        val systemPrompt = "You are a test assistant."

        val action = Action(ActionNames.GATEWAY_PREPARE_PREVIEW, buildJsonObject {
            put("providerId", "provider-1"); put("modelName", "model-1")
            put("correlationId", correlationId); put("systemPrompt", systemPrompt)
            put("contents", buildJsonArray { add(Json.encodeToJsonElement(message)) })
        })

        // ACT
        harness.store.dispatch(originatorId, action)
        runCurrent()

        // ASSERT
        harness.runAndLogOnFailure {
            val privateData = harness.deliveredPrivateData.firstOrNull()
            assertNotNull(privateData, "Private data for preview should have been delivered.")
            assertEquals("gateway.response.PREVIEW", privateData.envelope.type)

            val rawJson = privateData.envelope.payload["rawRequestJson"]?.jsonPrimitive?.content
            // Verify we got the string from FakeProvider.generatePreview, NOT empty map
            assertEquals("Fake Preview for model-1", rawJson)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `CANCEL_REQUEST cancels the job and cleans up via internal action`() = testScope.runTest {
        val harness = createHarness(this)
        harness.store.dispatch("settings", Action(ActionNames.SETTINGS_PUBLISH_LOADED, buildJsonObject {
            put("gateway.provider-2.apiKey", "k2")
        }))
        val correlationId = "cancel-test-123"

        // 1. Start a long running request (Provider 2 has 1000ms delay)
        val generateAction = Action(ActionNames.GATEWAY_GENERATE_CONTENT, buildJsonObject {
            put("providerId", "provider-2"); put("modelName", "gpt-x")
            put("correlationId", correlationId)
            put("contents", buildJsonArray { add(Json.encodeToJsonElement(GatewayMessage("user", "hi", "u1", "U", 1L))) })
        })
        harness.store.dispatch("agent-1", generateAction)
        runCurrent() // Launches coroutine, effectively "in flight"

        // 2. Dispatch Cancel
        val cancelAction = Action(ActionNames.GATEWAY_CANCEL_REQUEST, buildJsonObject {
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
            val cleanupAction = harness.processedActions.find { it.name == ActionNames.GATEWAY_INTERNAL_REQUEST_COMPLETED }
            assertNotNull(cleanupAction, "Cleanup action should be dispatched")
            assertEquals(correlationId, cleanupAction.payload?.get("correlationId")?.jsonPrimitive?.content)

            // 6. Verify NO response envelope was sent (job was cancelled)
            assertTrue(harness.deliveredPrivateData.isEmpty(), "No response should be delivered for cancelled request")
        }
    }
}