package app.auf.feature.gateway

import app.auf.core.*
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreFeature
import app.auf.feature.core.CoreState
import app.auf.feature.settings.SettingsFeature
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
    private val json = Json { ignoreUnknownKeys = true }

    private val testActionRegistry = setOf(
        "system.INITIALIZING", "system.STARTING",
        "settings.ADD",
        "settings.publish.VALUE_CHANGED",
        "settings.UPDATE", // Needed for the reducer to handle the change
        "gateway.REQUEST_AVAILABLE_MODELS", "gateway.publish.AVAILABLE_MODELS_UPDATED",
        "gateway.GENERATE_CONTENT",
        "gateway.internal.MODELS_UPDATED"
    )

    private data class CapturedPrivateData(val originator: String, val recipient: String, val data: Any)

    private class TestStore(
        initialState: AppState,
        features: List<Feature>,
        platformDependencies: PlatformDependencies,
        validActionNames: Set<String>
    ) : Store(initialState, features, platformDependencies, validActionNames) {
        val dispatchedActions = mutableListOf<Action>()
        var capturedPrivateData: CapturedPrivateData? = null

        override fun dispatch(originator: String, action: Action) {
            val stampedAction = action.copy(originator = originator)
            dispatchedActions.add(stampedAction)
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
        val apiKeySettingKey = "gateway.$id.apiKey"

        override fun registerSettings(dispatch: (Action) -> Unit) {
            registerSettingsCallCount++
            dispatch(Action("settings.ADD", buildJsonObject {
                put("key", apiKeySettingKey)
                put("type", "STRING")
                put("label", "$id Key")
                put("description", "API Key for $id")
                put("section", "API Keys")
                put("defaultValue", "")
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
    private lateinit var coreFeature: CoreFeature
    private lateinit var settingsFeature: SettingsFeature
    private lateinit var testStore: TestStore
    private lateinit var testScope: TestScope

    @BeforeTest
    fun setup() {
        testScope = TestScope()
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        fakeProvider1 = FakeAgentGatewayProvider("provider-1")
        fakeProvider2 = FakeAgentGatewayProvider("provider-2", modelsToReturn = listOf("gpt-x"))
        gatewayFeature = GatewayFeature(testScope, listOf(fakeProvider1, fakeProvider2))
        coreFeature = CoreFeature(fakePlatform)
        settingsFeature = SettingsFeature(fakePlatform)

        val settingsValues = mapOf(
            "gateway.provider-1.apiKey" to "key1",
            "gateway.provider-2.apiKey" to "key2"
        )
        val initialState = AppState(featureStates = mapOf(
            settingsFeature.name to SettingsState(values = settingsValues),
            gatewayFeature.name to GatewayState(),
            coreFeature.name to CoreState(lifecycle = AppLifecycle.RUNNING)
        ))

        val features = listOf(gatewayFeature, coreFeature, settingsFeature)
        testStore = TestStore(initialState, features, fakePlatform, testActionRegistry)
        features.forEach { it.init(testStore) }
    }

    @Test
    fun `on INITIALIZING registers settings for all providers`() = testScope.runTest {
        val bootingState = AppState(featureStates = mapOf(
            gatewayFeature.name to GatewayState(),
            coreFeature.name to CoreState(lifecycle = AppLifecycle.BOOTING),
            settingsFeature.name to SettingsState()
        ))
        val bootingStore = TestStore(bootingState, listOf(gatewayFeature, coreFeature, settingsFeature), FakePlatformDependencies(testAppVersion), testActionRegistry)
        bootingStore.dispatch("system.test", Action("system.INITIALIZING"))

        assertEquals(1, fakeProvider1.registerSettingsCallCount)
        assertEquals(1, fakeProvider2.registerSettingsCallCount)
    }

    @Test
    fun `on STARTING refreshes models for all providers`() = testScope.runTest {
        val bootingState = AppState(featureStates = mapOf(
            "settings" to SettingsState(values = mapOf("gateway.provider-1.apiKey" to "k1", "gateway.provider-2.apiKey" to "k2")),
            gatewayFeature.name to GatewayState(),
            coreFeature.name to CoreState(lifecycle = AppLifecycle.BOOTING)
        ))
        testStore = TestStore(bootingState, listOf(gatewayFeature, coreFeature, settingsFeature), FakePlatformDependencies(testAppVersion), testActionRegistry)

        testStore.dispatch("system.test", Action("system.INITIALIZING"))
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
        val bootingState = AppState(featureStates = mapOf(
            settingsFeature.name to SettingsState(values = mapOf(
                "gateway.provider-1.apiKey" to "key1",
                "gateway.provider-2.apiKey" to "old-key"
            )),
            gatewayFeature.name to GatewayState(),
            coreFeature.name to CoreState(lifecycle = AppLifecycle.BOOTING)
        ))
        testStore = TestStore(bootingState, listOf(gatewayFeature, coreFeature, settingsFeature), FakePlatformDependencies(testAppVersion), testActionRegistry)
        testStore.dispatch("system.test", Action("system.INITIALIZING"))
        testStore.dispatch("system.test", Action("system.STARTING"))
        testScheduler.runCurrent() // Run startup coroutines which calls listAvailableModels once for each.

        // Now that the system is properly initialized, dispatch the change action
        val valueChangedAction = Action("settings.publish.VALUE_CHANGED", buildJsonObject {
            put("key", "gateway.provider-2.apiKey")
            put("value", "new-key")
        })

        // Also dispatch an UPDATE action so the reducer updates the state.
        val updateAction = Action("settings.UPDATE", valueChangedAction.payload)
        testStore.dispatch("settings.feature", updateAction)
        testStore.dispatch("settings.feature", valueChangedAction)
        testScheduler.runCurrent() // Run the refresh coroutine triggered by the change.

        // --- THE FIX: Assert for the correct call counts based on the full lifecycle. ---
        // Provider 1 was only called once at startup.
        assertEquals(1, fakeProvider1.listAvailableModelsCallCount)
        // Provider 2 was called once at startup, and once again for the settings change.
        assertEquals(2, fakeProvider2.listAvailableModelsCallCount)
    }

    @Test
    fun `on REQUEST_AVAILABLE_MODELS broadcasts current state`() = testScope.runTest {
        val bootingState = AppState(featureStates = mapOf(
            "settings" to (testStore.state.value.featureStates["settings"] as SettingsState),
            gatewayFeature.name to GatewayState(),
            coreFeature.name to CoreState(lifecycle = AppLifecycle.BOOTING)
        ))
        testStore = TestStore(bootingState, listOf(gatewayFeature, coreFeature, settingsFeature), FakePlatformDependencies(testAppVersion), testActionRegistry)
        testStore.dispatch("system.test", Action("system.INITIALIZING"))
        testStore.dispatch("system.test", Action("system.STARTING"))
        testScheduler.runCurrent()

        testStore.dispatchedActions.clear()
        testStore.dispatch("agent.test", Action("gateway.REQUEST_AVAILABLE_MODELS"))

        val broadcastAction = testStore.dispatchedActions.last()
        assertEquals("gateway.publish.AVAILABLE_MODELS_UPDATED", broadcastAction.name)
        val payload = broadcastAction.payload!!
        assertEquals(2, payload.size, "Payload should contain model lists for two providers.")
    }

    @Test
    fun `on GENERATE_CONTENT routes to correct provider and delivers response privately`() = testScope.runTest {
        val originatorId = "agent-feature-1"
        val correlationId = "test-turn-123"

        val messages = listOf(GatewayMessage("user", "Test prompt"))
        val contentsPayload = json.encodeToJsonElement(messages)

        val action = Action("gateway.GENERATE_CONTENT", buildJsonObject {
            put("providerId", "provider-2")
            put("modelName", "gpt-x")
            put("correlationId", correlationId)
            put("contents", contentsPayload)
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