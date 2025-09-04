package app.auf.feature.hkgagent

import app.auf.core.*
import app.auf.feature.knowledgegraph.KnowledgeGraphFeature
import app.auf.feature.knowledgegraph.KnowledgeGraphService
import app.auf.feature.settings.SettingsFeature
import app.auf.feature.session.SessionAction
import app.auf.feature.session.SessionFeature
import app.auf.feature.session.SessionFeatureState
import app.auf.feature.systemclock.SystemClockFeature
import app.auf.util.fakes.FakePlatformDependencies
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

@OptIn(ExperimentalCoroutinesApi::class)
class HkgAgentFeatureTest {

    // --- Test Doubles ---

    class FakeAgentGateway(
        var responseContent: String,
        private val modelList: List<String> = listOf("fake-model-v1"),
        var processingDelay: Long = 0L
    ) : AgentGateway {
        var lastRequest: AgentRequest? = null
        var callCount = 0

        override suspend fun generateContent(request: AgentRequest): AgentResponse {
            kotlinx.coroutines.delay(processingDelay) // Simulate network/processing time
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
    private lateinit var platform: FakePlatformDependencies
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

        platform.writeFileContent(
            "/fake/holons/test-persona-1/test-persona-1.json",
            """
            {
              "header": { "id": "test-persona-1", "type": "AI_Persona_Root", "name": "Test Persona", "summary": "A fake persona for testing.", "version": "1.0" },
              "payload": {}
            }
            """.trimIndent()
        )
        val fakeKgService = KnowledgeGraphService(platform)


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
        // ARRANGE
        val initialAgent = (store.state.value.featureStates[agentFeature.name] as HkgAgentFeatureState).agents[agentId]!!
        assertEquals(AgentStatus.WAITING, initialAgent.status)

        // ACT 1: User posts a message, agent becomes PRIMED
        store.dispatch(SessionAction.PostEntry(sessionId, "USER", "Hello world"))
        runCurrent()
        val primedAgent = (store.state.value.featureStates[agentFeature.name] as HkgAgentFeatureState).agents[agentId]!!
        assertEquals(AgentStatus.PRIMED, primedAgent.status)

        // ACT 2: Advance time and run the pending debounce job
        testScope.testScheduler.advanceTimeBy(initialAgent.initialWaitMillis)
        runCurrent()

        // ASSERT: Agent is PROCESSING and gateway was called
        val processingAgent = (store.state.value.featureStates[agentFeature.name] as HkgAgentFeatureState).agents[agentId]!!
        assertEquals(AgentStatus.PROCESSING, processingAgent.status, "Agent should be PROCESSING after the delay.")
        assertEquals(1, fakeGateway.callCount)

        // ACT 3: Allow gateway response to complete
        runCurrent()

        // ASSERT: Agent is WAITING and transcript is updated
        val finalAgent = (store.state.value.featureStates[agentFeature.name] as HkgAgentFeatureState).agents[agentId]!!
        val finalTranscript = (store.state.value.featureStates[sessionFeature.name] as SessionFeatureState).sessions[sessionId]!!.transcript
        assertEquals(AgentStatus.WAITING, finalAgent.status)
        assertEquals(2, finalTranscript.size)
        assertEquals("This is the fake AI response.", finalTranscript.last().content)
    }

    @Test
    fun agentResetsDebounceTimerOnNewMessage() = testScope.runTest {
        // ARRANGE
        val agent = (store.state.value.featureStates[agentFeature.name] as HkgAgentFeatureState).agents[agentId]!!

        // ACT 1: User posts first message
        store.dispatch(SessionAction.PostEntry(sessionId, "USER", "First part"))
        runCurrent()
        assertEquals(AgentStatus.PRIMED, (store.state.value.featureStates[agentFeature.name] as HkgAgentFeatureState).agents[agentId]!!.status)

        // ACT 2: Advance time, but not enough to trigger the response
        testScope.testScheduler.advanceTimeBy(agent.initialWaitMillis - 500)
        runCurrent()

        // ACT 3: User posts a second message, resetting the timer
        store.dispatch(SessionAction.PostEntry(sessionId, "USER", "Second part"))
        runCurrent()

        // ACT 4: Advance time again, but not enough for the *original* timer to fire
        testScope.testScheduler.advanceTimeBy(agent.initialWaitMillis - 500)
        runCurrent()

        // ASSERT 1: Agent is still primed, gateway has not been called
        assertEquals(AgentStatus.PRIMED, (store.state.value.featureStates[agentFeature.name] as HkgAgentFeatureState).agents[agentId]!!.status)
        assertEquals(0, fakeGateway.callCount, "Gateway should not be called yet.")

        // ACT 5: Advance time past the *second* timer's expiry
        testScope.testScheduler.advanceTimeBy(1000)
        runCurrent()

        // ASSERT 2: Agent is now processing, and gateway was finally called
        assertEquals(AgentStatus.PROCESSING, (store.state.value.featureStates[agentFeature.name] as HkgAgentFeatureState).agents[agentId]!!.status)
        assertEquals(1, fakeGateway.callCount, "Gateway should have been called only once.")
    }

    @Test
    fun agentBypassesDebounceWhenMaxWaitIsExceeded() = testScope.runTest {
        // ARRANGE
        val agent = (store.state.value.featureStates[agentFeature.name] as HkgAgentFeatureState).agents[agentId]!!

        // ACT: User posts first message, starting the max-wait clock
        store.dispatch(SessionAction.PostEntry(sessionId, "USER", "Start of a long stream..."))
        runCurrent()
        assertEquals(AgentStatus.PRIMED, (store.state.value.featureStates[agentFeature.name] as HkgAgentFeatureState).agents[agentId]!!.status)

        // Simulate a stream of messages keeping the debounce timer resetting
        for (i in 1..10) {
            testScope.testScheduler.advanceTimeBy(agent.initialWaitMillis - 200)
            runCurrent()
            store.dispatch(SessionAction.PostEntry(sessionId, "USER", "message $i"))
            runCurrent()
        }
        val totalElapsedTime = testScope.testScheduler.currentTime
        assertTrue(totalElapsedTime < agent.maxWaitMillis, "Precondition: Max wait should not be exceeded yet.")
        assertEquals(0, fakeGateway.callCount, "Gateway should not have been called yet.")

        // ACT 2: Advance time to just over the maxWait limit
        testScope.testScheduler.advanceTimeBy(agent.maxWaitMillis - totalElapsedTime + 100)
        runCurrent()

        // ASSERT: Agent should now be processing, having bypassed the last debounce
        assertEquals(AgentStatus.PROCESSING, (store.state.value.featureStates[agentFeature.name] as HkgAgentFeatureState).agents[agentId]!!.status)
        assertEquals(1, fakeGateway.callCount)
    }

    @Test
    fun agentImmediatelyReTriggersIfMessageArrivesWhileProcessing() = testScope.runTest {
        // ARRANGE: Set a processing delay on the gateway
        fakeGateway.processingDelay = 5000L

        // ACT 1: Trigger the agent, it will enter PROCESSING for 5s
        store.dispatch(SessionAction.PostEntry(sessionId, "USER", "Initial prompt"))
        runCurrent()
        testScope.testScheduler.advanceTimeBy(1500L)
        runCurrent()
        assertEquals(AgentStatus.PROCESSING, (store.state.value.featureStates[agentFeature.name] as HkgAgentFeatureState).agents[agentId]!!.status)

        // ACT 2: While agent is busy, a new user message arrives
        store.dispatch(SessionAction.PostEntry(sessionId, "USER", "Oh, and another thing!"))
        runCurrent()

        // ACT 3: Advance time to let the first gateway call finish
        testScope.testScheduler.advanceTimeBy(5000L)
        runCurrent()

        // ASSERT 1: The first response is posted, but the agent immediately re-triggers
        val transcript = (store.state.value.featureStates[sessionFeature.name] as SessionFeatureState).sessions[sessionId]!!.transcript
        assertEquals(3, transcript.size, "Transcript should have user, AI, and second user message.")
        assertEquals(AgentStatus.PROCESSING, (store.state.value.featureStates[agentFeature.name] as HkgAgentFeatureState).agents[agentId]!!.status, "Agent should immediately re-enter PROCESSING state to catch up.")
        assertEquals(2, fakeGateway.callCount, "Gateway should have been called a second time immediately.")
    }

    @Test
    fun agentIgnoresItsOwnMessages() = testScope.runTest {
        // ACT 1: Trigger a normal response
        store.dispatch(SessionAction.PostEntry(sessionId, "USER", "Hello"))
        runCurrent()
        testScope.testScheduler.advanceTimeBy(1500L)
        runCurrent() // Agent is processing
        runCurrent() // Gateway responds, agent posts, returns to WAITING
        assertEquals(AgentStatus.WAITING, (store.state.value.featureStates[agentFeature.name] as HkgAgentFeatureState).agents[agentId]!!.status)
        assertEquals(1, fakeGateway.callCount)
        assertEquals(2, (store.state.value.featureStates[sessionFeature.name] as SessionFeatureState).sessions[sessionId]!!.transcript.size)

        // ACT 2: Wait for a moment
        testScope.testScheduler.advanceTimeBy(3000L)
        runCurrent()

        // ASSERT: Agent should still be waiting and should not have called the gateway again
        assertEquals(AgentStatus.WAITING, (store.state.value.featureStates[agentFeature.name] as HkgAgentFeatureState).agents[agentId]!!.status, "Agent should not be primed by its own message.")
        assertEquals(1, fakeGateway.callCount, "Gateway should not be called a second time.")
    }

    @Test
    fun agentRespondsInDumbModeWhenNoPersonaIsSelected() = testScope.runTest {
        // ARRANGE: Select "None" for the persona
        store.dispatch(HkgAgentAction.SelectHkgPersona(agentId, null))
        runCurrent()

        // ACT & ASSERT: Same flow as the first test
        store.dispatch(SessionAction.PostEntry(sessionId, "USER", "Are you there?"))
        runCurrent()
        testScope.testScheduler.advanceTimeBy(1500L)
        runCurrent()
        assertEquals(AgentStatus.PROCESSING, (store.state.value.featureStates[agentFeature.name] as HkgAgentFeatureState).agents[agentId]!!.status)
        assertEquals(1, fakeGateway.callCount)

        runCurrent()
        val finalTranscript = (store.state.value.featureStates[sessionFeature.name] as SessionFeatureState).sessions[sessionId]!!.transcript
        assertEquals(2, finalTranscript.size)
        assertTrue(fakeGateway.lastRequest!!.contents.any { it.parts.any { p -> p.text == "You are a helpful assistant." } }, "Should have used the generic dumb mode system prompt.")
    }
}