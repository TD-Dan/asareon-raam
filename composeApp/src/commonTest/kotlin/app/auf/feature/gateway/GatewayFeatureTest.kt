package app.auf.feature.gateway

import app.auf.core.*
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.settings.SettingsState
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayFeatureTest {

    private val testAppVersion = "2.0.0-test"

    // --- Test Doubles & Fakes ---

    private data class CapturedPrivateData(val originator: String, val recipient: String, val data: Any)

    private class TestStore(
        initialState: AppState,
        features: List<Feature>,
        platformDependencies: PlatformDependencies
    ) : Store(initialState, features, platformDependencies) {
        val dispatchedActions = mutableListOf<Action>()
        var capturedPrivateData: CapturedPrivateData? = null

        override fun dispatch(originator: String, action: Action) {
            dispatchedActions.add(action.copy(originator = originator))
            super.dispatch(originator, action)
        }

        override fun deliverPrivateData(originator: String, recipient: String, data: Any) {
            capturedPrivateData = CapturedPrivateData(originator, recipient, data)
            super.deliverPrivateData(originator, recipient, data)
        }
    }

    private class FakeAgentGatewayProvider(
        override val id: String,
        private val modelsToReturn: List<String> = listOf("model-1", "model-2")
    ) : AgentGatewayProvider {
        var registerSettingsCallCount = 0
        var listAvailableModelsCallCount = 0
        var generateContentCallCount = 0
        var lastRequest: GatewayRequest? = null

        override fun registerSettings(dispatch: (Action) -> Unit) {
            registerSettingsCallCount++
        }

        override suspend fun listAvailableModels(settings: Map<String, String>): List<String> {
            listAvailableModelsCallCount++
            return modelsToReturn
        }

        override suspend fun generateContent(request: GatewayRequest, settings: Map<String, String>): GatewayResponse {
            generateContentCallCount++
            lastRequest = request
            return GatewayResponse("Response from $id", null, request.correlationId)
        }
    }


    // --- Test Setup ---

    private lateinit var fakeProvider1: FakeAgentGatewayProvider
    private lateinit var fakeProvider2: FakeAgentGatewayProvider
    private lateinit var gatewayFeature: GatewayFeature
    private lateinit var testStore: TestStore
    private lateinit var testScope: TestScope

    @BeforeTest
    fun setup() {
        testScope = TestScope()
        fakeProvider1 = FakeAgentGatewayProvider("provider-1")
        fakeProvider2 = FakeAgentGatewayProvider("provider-2", modelsToReturn = listOf("gpt-x"))
        gatewayFeature = GatewayFeature(testScope, listOf(fakeProvider1, fakeProvider2))

        val initialState = AppState(featureStates = mapOf(
            "settings" to SettingsState(values = mapOf("gateway.provider-1.apiKey" to "key1")),
            gatewayFeature.name to GatewayState()
        ))
        testStore = TestStore(initialState, listOf(gatewayFeature), FakePlatformDependencies(testAppVersion))
        gatewayFeature.init(testStore)
    }

    // --- Test Cases ---

    @Test
    fun `on INITIALIZING registers settings for all providers`() = testScope.runTest {
        testStore.dispatch("system.test", Action("system.INITIALIZING"))
        // No coroutines here, so no scheduler advance needed.
        assertEquals(1, fakeProvider1.registerSettingsCallCount, "Provider 1 should register settings.")
        assertEquals(1, fakeProvider2.registerSettingsCallCount, "Provider 2 should register settings.")
    }

    @Test
    fun `on STARTING refreshes models for all providers`() = testScope.runTest {
        testStore.dispatch("system.test", Action("system.STARTING"))

        // THE FIX: Explicitly advance the scheduler to execute the launched coroutines.
        testScheduler.runCurrent()

        assertEquals(1, fakeProvider1.listAvailableModelsCallCount, "Provider 1 should be refreshed.")
        assertEquals(1, fakeProvider2.listAvailableModelsCallCount, "Provider 2 should be refreshed.")

        val finalState = testStore.state.value.featureStates[gatewayFeature.name] as GatewayState
        assertEquals(listOf("model-1", "model-2"), finalState.availableModels["provider-1"])
        assertEquals(listOf("gpt-x"), finalState.availableModels["provider-2"])
    }

    @Test
    fun `on settings VALUE_CHANGED refreshes models for the correct provider`() = testScope.runTest {
        val action = Action("settings.VALUE_CHANGED", buildJsonObject {
            put("key", "gateway.provider-2.apiKey")
            put("value", "new-key")
        })
        testStore.dispatch("settings.test", action)

        // THE FIX: Explicitly advance the scheduler.
        testScheduler.runCurrent()

        assertEquals(0, fakeProvider1.listAvailableModelsCallCount, "Provider 1 should not be refreshed.")
        assertEquals(1, fakeProvider2.listAvailableModelsCallCount, "Provider 2 should be refreshed.")
    }

    @Test
    fun `on REQUEST_AVAILABLE_MODELS broadcasts current state`() = testScope.runTest {
        // First, populate the state by simulating startup.
        testStore.dispatch("system.test", Action("system.STARTING"))
        testScheduler.runCurrent() // Must advance scheduler here too.

        // Now, ask for it
        testStore.dispatch("agent.test", Action("gateway.REQUEST_AVAILABLE_MODELS"))

        val broadcastAction = testStore.dispatchedActions.last()
        assertEquals("gateway.AVAILABLE_MODELS_UPDATED", broadcastAction.name)
        assertEquals(gatewayFeature.name, broadcastAction.originator)
        val payload = broadcastAction.payload!!
        assertEquals(2, payload.size)
        assertEquals("model-1", payload["provider-1"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("gpt-x", payload["provider-2"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `on GENERATE_CONTENT routes to correct provider and delivers response privately`() = testScope.runTest {
        val originatorId = "agent-feature-1"
        val correlationId = "test-turn-123"
        val prompt = buildJsonArray { add(buildJsonObject { put("role", "user") }) }

        val action = Action("gateway.GENERATE_CONTENT", buildJsonObject {
            put("providerId", "provider-2")
            put("modelName", "gpt-x")
            put("correlationId", correlationId)
            put("contents", prompt)
        })

        testStore.dispatch(originatorId, action)

        // THE FIX: Explicitly advance the scheduler.
        testScheduler.runCurrent()

        // Verify routing
        assertEquals(0, fakeProvider1.generateContentCallCount, "Provider 1 should not be called.")
        assertEquals(1, fakeProvider2.generateContentCallCount, "Provider 2 should be called.")
        assertEquals("gpt-x", fakeProvider2.lastRequest?.modelName)
        assertEquals(correlationId, fakeProvider2.lastRequest?.correlationId)

        // Verify private data delivery
        val privateData = testStore.capturedPrivateData
        assertNotNull(privateData)
        assertEquals(gatewayFeature.name, privateData.originator)
        assertEquals(originatorId, privateData.recipient)
        val response = privateData.data as GatewayResponse
        assertEquals("Response from provider-2", response.rawContent)
        assertEquals(correlationId, response.correlationId)
    }
}