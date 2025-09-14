package app.auf.feature.agent

import app.auf.core.*
import app.auf.feature.session.LedgerEntry
import app.auf.feature.session.SessionAction
import app.auf.feature.session.SessionFeature
import app.auf.feature.session.SessionFeatureState
import app.auf.util.fakes.FakePlatformDependencies
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

@OptIn(ExperimentalCoroutinesApi::class)
class AgentSessionIntegrationTest {

    // --- Test Doubles ---
    class FakeAgentGateway(var responseContent: String, var processingDelay: Long = 1000L) : AgentGateway {
        var callCount = 0
        override suspend fun generate(request: AgentRequest): AgentResponse {
            kotlinx.coroutines.delay(processingDelay)
            callCount++
            return AgentResponse(listOf(TextBlock(responseContent)))
        }
    }

    // --- Test Environment ---
    private lateinit var store: Store
    private lateinit var fakeGateway: FakeAgentGateway
    private lateinit var testScope: TestScope
    private val sessionId = "default-session"

    @BeforeTest
    fun setup() {
        testScope = TestScope()
        val platform = FakePlatformDependencies()
        val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }
        fakeGateway = FakeAgentGateway("This is the fake AI response.")

        val features = mutableListOf<Feature>()
        val agentFeature = AgentRuntimeFeature(fakeGateway, platform, testScope)
        val sessionFeature = SessionFeature(platform, jsonParser, testScope, features)
        features.addAll(listOf(agentFeature, sessionFeature))

        store = Store(
            initialState = AppState(),
            rootReducer = ::appReducer,
            features = features,
            coroutineScope = testScope
        )

        store.dispatch(SessionAction._CreateSession(sessionId, "Test Session"))
        store.dispatch(AgentRuntimeAction._UpdateStatus(AgentStatus.WAITING)) // Set initial state
        store.startFeatureLifecycles()
        testScope.runCurrent()
    }

    private fun getAgentState(): AgentRuntimeState? {
        val featureState = store.state.value.featureStates["AgentRuntimeFeature"] as? AgentRuntimeFeatureState
        return featureState?.agent
    }

    private fun getTranscript(): List<LedgerEntry> {
        val featureState = store.state.value.featureStates["SessionFeature"] as? SessionFeatureState
        return featureState?.sessions?.get(sessionId)?.transcript ?: emptyList()
    }

    @Test
    fun `full turn lifecycle is correctly orchestrated by the store`() = testScope.runTest {
        // ARRANGE
        assertEquals(AgentStatus.WAITING, getAgentState()?.status)
        assertEquals(0, getTranscript().size)

        // ACT 1: Stimulate the agent, which will move it to PROCESSING and begin a turn.
        store.dispatch(AgentRuntimeAction._UpdateStatus(AgentStatus.PRIMED))
        runCurrent()

        // ASSERT 1: Agent is now processing and a placeholder exists in the ledger.
        assertEquals(AgentStatus.PROCESSING, getAgentState()?.status)
        assertTrue(getAgentState()?.activeTurnId != null, "Agent should have an active turn ID.")
        assertEquals(1, getTranscript().size)
        assertTrue(getTranscript().first() is LedgerEntry.AgentTurn, "Ledger should contain an AgentTurn placeholder.")

        // ACT 2: Advance time to allow the gateway to respond.
        advanceTimeBy(fakeGateway.processingDelay + 100)
        runCurrent()

        // ASSERT 2: The turn is complete, the placeholder is replaced, and the agent is waiting.
        assertEquals(AgentStatus.WAITING, getAgentState()?.status)
        assertEquals(null, getAgentState()?.activeTurnId, "Active turn ID should be cleared.")
        assertEquals(1, getTranscript().size)
        assertTrue(getTranscript().first() is LedgerEntry.Message, "Placeholder should be replaced by a Message.")
        assertEquals(1, fakeGateway.callCount)
    }

    @Test
    fun `cancellation correctly interrupts processing and cleans up ledger`() = testScope.runTest {
        // ARRANGE: Set a long delay to ensure we can cancel mid-flight.
        fakeGateway.processingDelay = 5000L

        // ACT 1: Start the turn.
        store.dispatch(AgentRuntimeAction._UpdateStatus(AgentStatus.PRIMED))
        runCurrent()

        // ASSERT 1: Verify we are in the processing state.
        assertEquals(AgentStatus.PROCESSING, getAgentState()?.status)
        val turnId = getAgentState()?.activeTurnId
        assertTrue(turnId != null)
        assertEquals(1, getTranscript().size)
        assertTrue(getTranscript().first() is LedgerEntry.AgentTurn)

        // ACT 2: Advance time part-way, then dispatch the cancellation.
        advanceTimeBy(1000L)
        store.dispatch(AgentAction.TurnCancelled(turnId!!))
        runCurrent()

        // ASSERT 2: The agent is now waiting and the placeholder has been removed.
        assertEquals(AgentStatus.WAITING, getAgentState()?.status, "Agent should have returned to WAITING.")
        assertEquals(null, getAgentState()?.activeTurnId, "Active turn ID should be cleared after cancellation.")
        assertEquals(0, getTranscript().size, "Transcript should be empty after placeholder is removed.")

        // ACT 3: Advance time past the original gateway delay to ensure it doesn't post a message.
        advanceTimeBy(5000L)
        runCurrent()

        // ASSERT 3: The transcript remains empty, confirming the gateway response was ignored.
        assertEquals(0, getTranscript().size)
    }
}