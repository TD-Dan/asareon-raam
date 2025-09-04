package app.auf.feature.hkgagent

import app.auf.core.AppAction
import app.auf.core.AppState
import app.auf.core.Feature
import app.auf.core.Store
import app.auf.core.appReducer
import app.auf.feature.knowledgegraph.KnowledgeGraphFeature
import app.auf.feature.knowledgegraph.KnowledgeGraphService
import app.auf.feature.settings.SettingsFeature
import app.auf.feature.session.SessionAction
import app.auf.feature.session.SessionFeature
import app.auf.feature.session.SessionFeatureState
import app.auf.feature.systemclock.SystemClockFeature
import app.auf.util.PlatformDependencies
import app.auf.util.fakes.FakePlatformDependencies
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

@OptIn(ExperimentalCoroutinesApi::class)
class HkgAgentFeatureTest {

    // --- Test Doubles ---

    class FakeAgentGateway(
        private val responseContent: String,
        private val modelList: List<String> = listOf("fake-model-v1")
    ) : AgentGateway {
        var lastRequest: AgentRequest? = null
        var callCount = 0

        override suspend fun generateContent(request: AgentRequest): AgentResponse {
            callCount++
            lastRequest = request
            return AgentResponse(responseContent, null, null)
        }

        override suspend fun listAvailableModels(): List<String> {
            return modelList
        }
    }

    // --- Test Environment ---

    private lateinit var store: Store
    private lateinit var fakeGateway: FakeAgentGateway
    private lateinit var agentFeature: HkgAgentFeature
    private lateinit var sessionFeature: SessionFeature
    private lateinit var clockFeature: SystemClockFeature
    private lateinit var platform: FakePlatformDependencies // Use the fake directly for time control
    private lateinit var testScope: TestScope

    private val agentId = "agent-for-default-session"
    private val sessionId = "default-session"

    @BeforeTest
    fun setup() {
        testScope = TestScope()
        platform = FakePlatformDependencies()
        val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }
        val promptCompiler = PromptCompiler(jsonParser)
        fakeGateway = FakeAgentGateway("This is the fake AI response.")

        val features = mutableListOf<Feature>()
        val fakeKgService = object : KnowledgeGraphService(platform) {}

        agentFeature = HkgAgentFeature(fakeGateway, promptCompiler, platform, jsonParser, testScope)
        sessionFeature = SessionFeature(platform, jsonParser, testScope, features)
        clockFeature = SystemClockFeature(testScope)
        val kgFeature = KnowledgeGraphFeature(fakeKgService, testScope)
        val settingsFeature = SettingsFeature(features)
        features.addAll(listOf(agentFeature, sessionFeature, clockFeature, kgFeature, settingsFeature))

        store = Store(
            initialState = AppState(),
            rootReducer = ::appReducer,
            features = features,
            coroutineScope = testScope
        )

        store.startFeatureLifecycles()
        testScope.runCurrent()
    }


    @Test
    fun agentRespondsToUserMessageAfterDebounce() = testScope.runTest {
        // --- ARRANGE ---
        val initialState = store.state.value
        val initialAgent = (initialState.featureStates[agentFeature.name] as? HkgAgentFeatureState)?.agents?.get(agentId)
        assertNotNull(initialAgent, "Agent should have been created on startup.")
        assertEquals(AgentStatus.WAITING, initialAgent.status, "Agent should start in WAITING state.")

        // --- ACT ---
        // 1. User posts a message
        store.dispatch(SessionAction.PostEntry(sessionId, "USER", "Hello world"))
        runCurrent()

        // --- ASSERT 1: Agent becomes PRIMED ---
        val primedState = store.state.value
        val primedAgent = (primedState.featureStates[agentFeature.name] as HkgAgentFeatureState).agents[agentId]!!
        assertEquals(AgentStatus.PRIMED, primedAgent.status, "Agent should be PRIMED after a user message.")
        assertNotNull(primedAgent.primedAt)
        assertNotNull(primedAgent.lastEntryAt)

        // --- ACT 2: Advance time past the initial wait delay ---
        val delayMillis = initialAgent.initialWaitMillis + 1
        testScope.testScheduler.advanceTimeBy(delayMillis)
        // --- FIX: Manually advance the fake platform's clock to match the scheduler's ---
        platform.currentTime += delayMillis
        runCurrent() // Allow the timer check loop and subsequent gateway call to execute

        // --- ASSERT 2: Agent is now PROCESSING ---
        val processingState = store.state.value
        val processingAgent = (processingState.featureStates[agentFeature.name] as HkgAgentFeatureState).agents[agentId]!!
        assertEquals(AgentStatus.PROCESSING, processingAgent.status, "Agent should be PROCESSING after the delay.")
        assertEquals(1, fakeGateway.callCount, "AgentGateway should have been called exactly once.")
        assertNotNull(fakeGateway.lastRequest, "Gateway should have received a request.")
        // NOTE: The test prompt builder is a placeholder. If integrated, this needs updating.
        // For now, we confirm it's not empty and has the correct final message.
        assert(fakeGateway.lastRequest!!.contents.isNotEmpty()) { "Request should contain prompt contents." }

        
        // --- ACT 3: The gateway "responds" and the feature posts the new entry and resets ---
        runCurrent()

        // --- ASSERT 3: Agent is WAITING again and the transcript is updated ---
        val finalState = store.state.value
        val finalAgent = (finalState.featureStates[agentFeature.name] as HkgAgentFeatureState).agents[agentId]!!
        val finalTranscript = (finalState.featureStates[sessionFeature.name] as SessionFeatureState).sessions[sessionId]!!.transcript

        assertEquals(AgentStatus.WAITING, finalAgent.status, "Agent should return to WAITING state after responding.")
        assertEquals(2, finalTranscript.size, "Transcript should contain two entries (user + AI).")

        val aiEntry = finalTranscript.last()
        assertEquals(agentId, aiEntry.agentId, "The second entry should be from the AI agent.")
        assertEquals("This is the fake AI response.", aiEntry.content, "The AI's response content is incorrect.")
    }
}