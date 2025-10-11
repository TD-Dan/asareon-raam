package app.auf.feature.gateway

import app.auf.core.*
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreFeature
import app.auf.feature.core.CoreState
import app.auf.feature.settings.SettingsState
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayFeatureTest {

    private val testAppVersion = "2.0.0-test"

    // --- Test Doubles & Fakes ---

    private data class CapturedPrivateData(val originator: String, val recipient: String, val data: Any)

    /**
     * A high-fidelity test Store that correctly includes all necessary features
     * (like CoreFeature for lifecycle) to create a realistic test environment.
     */
    private class TestStore(
        initialState: AppState,
        features: List<Feature>,
        platformDependencies: PlatformDependencies
    ) : Store(initialState, features, platformDependencies) {
        val dispatchedActions = mutableListOf<Action>()
        var capturedPrivateData: CapturedPrivateData? = null

        override fun dispatch(originator: String, action: Action) {
            val stampedAction = action.copy(originator = originator)
            dispatchedActions.add(stampedAction)
            super.dispatch(originator, stampedAction)
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
        val apiKeySettingKey = "gateway.$id.apiKey"

        override fun registerSettings(dispatch: (Action) -> Unit) {
            registerSettingsCallCount++
        }

        override suspend fun listAvailableModels(settings: Map<String, String>): List<String> {
            val apiKey = settings[apiKeySettingKey].orEmpty()
            if (apiKey.isBlank()) return emptyList() // Guard clause
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
    private lateinit var coreFeature: CoreFeature
    private lateinit var testStore: TestStore
    private lateinit var testScope: TestScope

    @BeforeTest
    fun setup() {
        testScope = TestScope()
        fakeProvider1 = FakeAgentGatewayProvider("provider-1")
        fakeProvider2 = FakeAgentGatewayProvider("provider-2", modelsToReturn = listOf("gpt-x"))
        gatewayFeature = GatewayFeature(testScope, listOf(fakeProvider1, fakeProvider2))
        // THE FIX: CoreFeature MUST be part of the test to manage lifecycle state.
        coreFeature = CoreFeature(FakePlatformDependencies(testAppVersion))

        // THE FIX: Provide API keys for ALL providers to prevent silent failures in guard clauses.
        val settingsValues = mapOf(
            "gateway.provider-1.apiKey" to "key1",
            "gateway.provider-2.apiKey" to "key2"
        )
        // THE FIX: The initial state MUST include the CoreState and be in the RUNNING lifecycle phase.
        val initialState = AppState(featureStates = mapOf(
            "settings" to SettingsState(values = settingsValues),
            gatewayFeature.name to GatewayState(),
            coreFeature.name to CoreState(lifecycle = AppLifecycle.RUNNING)
        ))

        // THE FIX: The TestStore must be aware of all features involved in the test.
        val features = listOf(gatewayFeature, coreFeature)
        testStore = TestStore(initialState, features, FakePlatformDependencies(testAppVersion))
        features.forEach { it.init(testStore) }
    }

    // --- Test Cases ---

    @Test
    fun `on INITIALIZING registers settings for all providers`() = testScope.runTest {
        // To test INITIALIZING, we need a store that is in BOOTING state.
        val bootingState = AppState(featureStates = mapOf(
            gatewayFeature.name to GatewayState(),
            coreFeature.name to CoreState(lifecycle = AppLifecycle.BOOTING)
        ))
        val bootingStore = TestStore(bootingState, listOf(gatewayFeature, coreFeature), FakePlatformDependencies(testAppVersion))
        bootingStore.dispatch("system.test", Action("system.INITIALIZING"))

        assertEquals(1, fakeProvider1.registerSettingsCallCount, "Provider 1 should register settings.")
        assertEquals(1, fakeProvider2.registerSettingsCallCount, "Provider 2 should register settings.")
    }

    @Test
    fun `on STARTING refreshes models for all providers`() = testScope.runTest {
        testStore.dispatch("system.test", Action("system.STARTING"))

        testScheduler.runCurrent()

        assertEquals(1, fakeProvider1.listAvailableModelsCallCount, "Provider 1 should be refreshed.")
        assertEquals(1, fakeProvider2.listAvailableModelsCallCount, "Provider 2 should be refreshed.")

        val finalState = testStore.state.value.featureStates[gatewayFeature.name] as GatewayState
        assertEquals(listOf("model-1", "model-2"), finalState.availableModels["provider-1"])
        assertEquals(listOf("gpt-x"), finalState.availableModels["provider-2"])
    }

    @Test
    fun `on settings VALUE_CHANGED refreshes models for the correct provider`() = testScope.runTest {
        // ARRANGE: Create a specific store for this test where provider 2 initially has no key.
        val initialSettings = mapOf("gateway.provider-1.apiKey" to "key1")
        val initialState = AppState(featureStates = mapOf(
            "settings" to SettingsState(values = initialSettings),
            gatewayFeature.name to GatewayState(),
            coreFeature.name to CoreState(lifecycle = AppLifecycle.RUNNING)
        ))
        val specificStore = TestStore(initialState, listOf(gatewayFeature, coreFeature), FakePlatformDependencies(testAppVersion))

        // ACT: Dispatch the action that changes the key for provider 2.
        val action = Action("settings.VALUE_CHANGED", buildJsonObject {
            put("key", "gateway.provider-2.apiKey")
            put("value", "new-key")
        })

        // Since the SettingsFeature is not in this test harness, its reducer won't run.
        // We must manually update the store's state to simulate the change before calling onAction.
        val updatedSettings = initialSettings + ("gateway.provider-2.apiKey" to "new-key")
        val updatedState = specificStore.state.value.copy(
            featureStates = specificStore.state.value.featureStates +
                    ("settings" to SettingsState(values = updatedSettings))
        )
        @Suppress("UNCHECKED_CAST")
        (specificStore.state as MutableStateFlow<AppState>).value = updatedState

        // Now, call the onAction handler directly with the updated store.
        gatewayFeature.onAction(action, specificStore)
        testScheduler.runCurrent()

        // ASSERT
        assertEquals(0, fakeProvider1.listAvailableModelsCallCount, "Provider 1 should not be refreshed.")
        assertEquals(1, fakeProvider2.listAvailableModelsCallCount, "Provider 2 should be refreshed.")
    }


    @Test
    fun `on REQUEST_AVAILABLE_MODELS broadcasts current state`() = testScope.runTest {
        testStore.dispatch("system.test", Action("system.STARTING"))
        testScheduler.runCurrent()

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

        testScheduler.runCurrent()

        assertEquals(0, fakeProvider1.generateContentCallCount, "Provider 1 should not be called.")
        assertEquals(1, fakeProvider2.generateContentCallCount, "Provider 2 should be called.")
        assertEquals("gpt-x", fakeProvider2.lastRequest?.modelName)
        assertEquals(correlationId, fakeProvider2.lastRequest?.correlationId)

        val privateData = testStore.capturedPrivateData
        assertNotNull(privateData)
        assertEquals(gatewayFeature.name, privateData.originator)
        assertEquals(originatorId, privateData.recipient)
        val response = privateData.data as GatewayResponse
        assertEquals("Response from provider-2", response.rawContent)
        assertEquals(correlationId, response.correlationId)
    }
}