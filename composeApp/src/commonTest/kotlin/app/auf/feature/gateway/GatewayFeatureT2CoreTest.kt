package app.auf.feature.gateway

import app.auf.core.Action
import app.auf.core.generated.ActionNames
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.settings.SettingsFeature
import app.auf.test.TestEnvironment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
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
    private class FakeAgentGatewayProvider(
        override val id: String,
        private val modelsToReturn: List<String> = listOf("model-1", "model-2")
    ) : AgentGatewayProvider {
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
            return GatewayResponse("Response from $id", null, request.correlationId)
        }
    }

    private lateinit var fakeProvider1: FakeAgentGatewayProvider
    private lateinit var fakeProvider2: FakeAgentGatewayProvider
    private lateinit var testScope: TestScope

    @BeforeTest
    fun setup() {
        testScope = TestScope()
        fakeProvider1 = FakeAgentGatewayProvider("provider-1")
        fakeProvider2 = FakeAgentGatewayProvider("provider-2", modelsToReturn = listOf("gpt-x"))
    }

    private fun createHarness(
        testScope: TestScope
    ): app.auf.test.TestHarness {
        val settingsFeature = SettingsFeature(FakePlatformDependencies("test"))
        val gatewayFeature = GatewayFeature(
            FakePlatformDependencies("test"),
            testScope,
            listOf(fakeProvider1, fakeProvider2)
        )
        return TestEnvironment.create()
            .withFeature(gatewayFeature)
            .withFeature(settingsFeature)
            .build(scope = testScope)
    }

    @Test
    fun `on INITIALIZING registers settings for all providers`() = testScope.runTest {
        val harness = createHarness(this)
        harness.store.dispatch("system.test", Action(ActionNames.SYSTEM_PUBLISH_INITIALIZING))
        assertEquals(1, fakeProvider1.registerSettingsCallCount)
        assertEquals(1, fakeProvider2.registerSettingsCallCount)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `on STARTING refreshes models for all providers with API keys`() = testScope.runTest {
        val harness = createHarness(this)
        // THE FIX: Simulate settings being loaded, which is the event that populates the Gateway's API key state.
        harness.store.dispatch("settings", Action(ActionNames.SETTINGS_PUBLISH_LOADED, buildJsonObject {
            put("gateway.provider-1.apiKey", "k1")
            put("gateway.provider-2.apiKey", "k2")
        }))

        harness.store.dispatch("system.test", Action(ActionNames.SYSTEM_PUBLISH_STARTING))
        runCurrent()

        assertEquals(1, fakeProvider1.listAvailableModelsCallCount)
        assertEquals(1, fakeProvider2.listAvailableModelsCallCount)

        val finalState = harness.store.state.value.featureStates["gateway"] as GatewayState
        assertEquals(listOf("model-1", "model-2"), finalState.availableModels["provider-1"])
        assertEquals(listOf("gpt-x"), finalState.availableModels["provider-2"])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `on settings VALUE_CHANGED refreshes models for the correct provider`() = testScope.runTest {
        val harness = createHarness(this)
        // THE FIX: Simulate initial settings load and model refresh cycle.
        harness.store.dispatch("settings", Action(ActionNames.SETTINGS_PUBLISH_LOADED, buildJsonObject {
            put("gateway.provider-1.apiKey", "k1")
            put("gateway.provider-2.apiKey", "k2")
        }))
        harness.store.dispatch("system.test", Action(ActionNames.SYSTEM_PUBLISH_STARTING))
        runCurrent() // Run the initial refresh

        // THE FIX: Dispatch the VALUE_CHANGED event, which is the correct public contract.
        val valueChangedAction = Action(ActionNames.SETTINGS_PUBLISH_VALUE_CHANGED, buildJsonObject {
            put("key", "gateway.provider-2.apiKey"); put("value", "new-key")
        })
        harness.store.dispatch("settings", valueChangedAction)
        runCurrent() // Run the new refresh

        assertEquals(1, fakeProvider1.listAvailableModelsCallCount, "Provider 1 should not be refreshed.")
        assertEquals(2, fakeProvider2.listAvailableModelsCallCount, "Provider 2 should be refreshed.")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `on GENERATE_CONTENT routes to correct provider and delivers response privately`() = testScope.runTest {
        val harness = createHarness(this)
        // THE FIX: Hydrate the gateway with the necessary API key via the correct event.
        harness.store.dispatch("settings", Action(ActionNames.SETTINGS_PUBLISH_LOADED, buildJsonObject {
            put("gateway.provider-2.apiKey", "k2")
        }))
        val originatorId = "agent-feature-1"
        val correlationId = "test-turn-123"
        val action = Action(ActionNames.GATEWAY_GENERATE_CONTENT, buildJsonObject {
            put("providerId", "provider-2"); put("modelName", "gpt-x")
            put("correlationId", correlationId)
            put("contents", buildJsonArray { add(Json.encodeToJsonElement(GatewayMessage("user", "Test"))) })
        })

        harness.store.dispatch(originatorId, action)
        runCurrent()

        assertEquals(0, fakeProvider1.generateContentCallCount)
        assertEquals(1, fakeProvider2.generateContentCallCount)

        val privateData = harness.deliveredPrivateData.firstOrNull()
        assertNotNull(privateData)
        assertEquals(originatorId, privateData.recipient)
        assertEquals("gateway.response", privateData.envelope.type)
        assertEquals(correlationId, privateData.envelope.payload["correlationId"]?.jsonPrimitive?.content)
    }
}