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
import kotlinx.coroutines.flow.asStateFlow
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
     * (like CoreFeature for lifecycle) and allows safe state manipulation for testing.
     */
    private class TestStore(
        initialState: AppState,
        features: List<Feature>,
        platformDependencies: PlatformDependencies
    ) : Store(initialState, features, platformDependencies) {
        val dispatchedActions = mutableListOf<Action>()
        var capturedPrivateData: CapturedPrivateData? = null

        // THE FIX: Provide a safe way to manipulate state for tests.
        private val _testState = MutableStateFlow(initialState)
        override val state = _testState.asStateFlow()
        fun setState(newState: AppState) {
            _testState.value = newState
        }
        // END FIX

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
        coreFeature = CoreFeature(FakePlatformDependencies(testAppVersion))

        val settingsValues = mapOf(
            "gateway.provider-1.apiKey" to "key1",
            "gateway.provider-2.apiKey" to "key2"
        )
        val initialState = AppState(featureStates = mapOf(
            "settings" to SettingsState(values = settingsValues),
            gatewayFeature.name to GatewayState(),
            coreFeature.name to CoreState(lifecycle = AppLifecycle.RUNNING)
        ))

        val features = listOf(gatewayFeature, coreFeature)
        testStore = TestStore(initialState, features, FakePlatformDependencies(testAppVersion))
        features.forEach { it.init(testStore) }
    }

    // --- Test Cases ---

    @Test
    fun `on INITIALIZING registers settings for all providers`() = testScope.runTest {
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
        // THE FIX: To test 'system.STARTING', we must begin in the 'INITIALIZING' state.
        val initializingState = AppState(featureStates = testStore.state.value.featureStates +
                (coreFeature.name to CoreState(lifecycle = AppLifecycle.INITIALIZING)))
        testStore.setState(initializingState)
        // END FIX

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
        val action = Action("settings.VALUE_CHANGED", buildJsonObject {
            put("key", "gateway.provider-2.apiKey")
            put("value", "new-key")
        })

        // THE FIX: Simulate the SettingsFeature's reducer by safely setting the new state
        // before dispatching the action that triggers the side effect.
        val currentSettings = (testStore.state.value.featureStates["settings"] as SettingsState).values
        val updatedSettings = currentSettings + ("gateway.provider-2.apiKey" to "new-key")
        val updatedState = testStore.state.value.copy(
            featureStates = testStore.state.value.featureStates +
                    ("settings" to SettingsState(values = updatedSettings))
        )
        testStore.setState(updatedState)
        // END FIX

        // Now dispatch the action to trigger the onAction handler.
        testStore.dispatch("settings.feature", action)
        testScheduler.runCurrent()

        assertEquals(0, fakeProvider1.listAvailableModelsCallCount, "Provider 1 should not be refreshed.")
        assertEquals(1, fakeProvider2.listAvailableModelsCallCount, "Provider 2 should be refreshed.")
    }

    @Test
    fun `on REQUEST_AVAILABLE_MODELS broadcasts current state`() = testScope.runTest {
        // THE FIX: Simulate a proper startup sequence to populate state.
        val initializingState = AppState(featureStates = testStore.state.value.featureStates +
                (coreFeature.name to CoreState(lifecycle = AppLifecycle.INITIALIZING)))
        testStore.setState(initializingState)
        testStore.dispatch("system.test", Action("system.STARTING"))
        testScheduler.runCurrent()
        // END FIX

        testStore.dispatch("agent.test", Action("gateway.REQUEST_AVAILABLE_MODELS"))

        val broadcastAction = testStore.dispatchedActions.last()
        assertEquals("gateway.AVAILABLE_MODELS_UPDATED", broadcastAction.name)
        val payload = broadcastAction.payload!!
        assertEquals(2, payload.size)
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

        assertEquals(0, fakeProvider1.generateContentCallCount)
        assertEquals(1, fakeProvider2.generateContentCallCount)

        val privateData = testStore.capturedPrivateData
        assertNotNull(privateData)
        assertEquals(originatorId, privateData.recipient)
    }
}