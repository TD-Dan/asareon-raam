package app.auf.feature.gateway

import app.auf.core.Action
import app.auf.core.generated.ActionNames
import app.auf.feature.settings.SettingsFeature
import app.auf.feature.settings.SettingsState
import app.auf.test.TestEnvironment
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
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

    private val json = Json { ignoreUnknownKeys = true }

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
    private lateinit var gatewayFeature: GatewayFeature
    private lateinit var settingsFeature: SettingsFeature
    private lateinit var testScope: TestScope

    @BeforeTest
    fun setup() {
        testScope = TestScope()
        fakeProvider1 = FakeAgentGatewayProvider("provider-1")
        fakeProvider2 = FakeAgentGatewayProvider("provider-2", modelsToReturn = listOf("gpt-x"))
        gatewayFeature = GatewayFeature(testScope.backgroundScope.coroutineContext, listOf(fakeProvider1, fakeProvider2))
        settingsFeature = SettingsFeature()
    }

    private fun createHarness(settingsValues: Map<String, String> = emptyMap()): app.auf.test.TestHarness {
        return TestEnvironment.create()
            .withFeature(gatewayFeature)
            .withFeature(settingsFeature)
            .withInitialState("settings", SettingsState(values = settingsValues))
            .build()
    }

    @Test
    fun `on INITIALIZING registers settings for all providers`() = testScope.runTest {
        val harness = createHarness()
        harness.store.dispatch("system.test", Action(ActionNames.SYSTEM_PUBLISH_INITIALIZING))
        assertEquals(1, fakeProvider1.registerSettingsCallCount)
        assertEquals(1, fakeProvider2.registerSettingsCallCount)
    }

    @Test
    fun `on STARTING refreshes models for all providers`() = testScope.runTest {
        val harness = createHarness(mapOf(
            "gateway.provider-1.apiKey" to "k1",
            "gateway.provider-2.apiKey" to "k2"
        ))
        harness.store.dispatch("system.test", Action(ActionNames.SYSTEM_PUBLISH_STARTING))
        runCurrent()

        assertEquals(1, fakeProvider1.listAvailableModelsCallCount)
        assertEquals(1, fakeProvider2.listAvailableModelsCallCount)

        val finalState = harness.store.state.value.featureStates[gatewayFeature.name] as GatewayState
        assertEquals(listOf("model-1", "model-2"), finalState.availableModels["provider-1"])
        assertEquals(listOf("gpt-x"), finalState.availableModels["provider-2"])
    }

    @Test
    fun `on settings VALUE_CHANGED refreshes models for the correct provider`() = testScope.runTest {
        val harness = createHarness(mapOf(
            "gateway.provider-1.apiKey" to "k1",
            "gateway.provider-2.apiKey" to "k2"
        ))
        harness.store.dispatch("system.test", Action(ActionNames.SYSTEM_PUBLISH_STARTING))
        runCurrent()

        val valueChangedAction = Action(ActionNames.SETTINGS_PUBLISH_VALUE_CHANGED, buildJsonObject {
            put("key", "gateway.provider-2.apiKey"); put("value", "new-key")
        })
        harness.store.dispatch("settings", valueChangedAction)
        runCurrent()

        assertEquals(1, fakeProvider1.listAvailableModelsCallCount)
        assertEquals(2, fakeProvider2.listAvailableModelsCallCount)
    }

    @Test
    fun `on GENERATE_CONTENT routes to correct provider and delivers response privately`() = testScope.runTest {
        val harness = createHarness(mapOf("gateway.provider-2.apiKey" to "k2"))
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