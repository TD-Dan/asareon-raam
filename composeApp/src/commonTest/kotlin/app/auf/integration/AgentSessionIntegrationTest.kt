package app.auf.integration

import app.auf.core.*
import app.auf.feature.agent.*
import app.auf.feature.session.LedgerEntry
import app.auf.feature.session.SessionAction
import app.auf.feature.session.SessionFeature
import app.auf.feature.session.SessionFeatureState
import app.auf.fakes.FakePlatformDependencies
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

    // --- FIX: Reinstate the FakeAgentGateway to satisfy the non-null contract ---
    class FakeAgentGateway : AgentGateway {
        override suspend fun generateContent(request: AgentRequest): AgentResponse {
            return AgentResponse(rawContent = "Fake response", errorMessage = null)
        }
        override suspend fun listAvailableModels(): List<String> = listOf("fake-model-1")
    }

    // --- Test Environment ---
    private lateinit var store: Store
    private lateinit var testScope: TestScope
    private val sessionId = "default-session"
    private val agentId = "agent-1"

    @BeforeTest
    fun setup() {
        testScope = TestScope()
        val platform = FakePlatformDependencies()
        val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }
        val fakeGateway = FakeAgentGateway() // Instantiate the fake

        val features = mutableListOf<Feature>()
        // --- FIX: Pass the non-null fakeGateway instance ---
        val agentFeature = AgentRuntimeFeature(agentGateway = fakeGateway, platform = platform, coroutineScope = testScope)
        val sessionFeature = SessionFeature(platform, jsonParser, testScope, features)
        features.addAll(listOf(agentFeature, sessionFeature))

        val initialAgentState = AgentRuntimeFeatureState(
            agent = AgentRuntimeState(id = agentId, sessionId = sessionId, status = AgentStatus.WAITING)
        )
        val initialAppState = AppState(
            featureStates = mapOf("AgentRuntimeFeature" to initialAgentState)
        )

        store = Store(
            initialState = initialAppState,
            rootReducer = ::appReducer,
            features = features,
            coroutineScope = testScope
        )

        store.dispatch(SessionAction._CreateSession(sessionId, "Test Session"))
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

    private fun findActiveTurnId(): String? {
        return getTranscript().filterIsInstance<LedgerEntry.AgentTurn>().firstOrNull()?.entryId
    }

    @Test
    fun `full turn lifecycle is correctly orchestrated by the store`() = testScope.runTest {
        // ARRANGE
        assertEquals(AgentStatus.WAITING, getAgentState()?.status)
        assertEquals(0, getTranscript().size)

        // ACT 1: Stimulate the agent with a user message.
        store.dispatch(SessionAction.PostUserMessage(sessionId, "Go"))
        runCurrent()

        // ASSERT 1: Agent is now PROCESSING and a placeholder exists in the ledger.
        assertEquals(AgentStatus.PROCESSING, getAgentState()?.status)
        assertTrue(findActiveTurnId() != null, "An agent turn should be active in the transcript.")
        assertEquals(2, getTranscript().size) // User message + AgentTurn
        assertTrue(getTranscript().last() is LedgerEntry.AgentTurn, "Ledger should contain an AgentTurn placeholder.")

        // ACT 2: Advance time to allow the agent's internal logic (hardcoded delay) to complete.
        advanceTimeBy(2000 + 100)
        runCurrent()

        // ASSERT 3: The turn is complete, the placeholder is replaced, and the agent is WAITING.
        assertEquals(AgentStatus.WAITING, getAgentState()?.status)
        assertTrue(findActiveTurnId() == null, "Active turn ID should be cleared from transcript.")
        assertEquals(2, getTranscript().size) // User message + Agent message
        assertTrue(getTranscript().last() is LedgerEntry.Message, "Placeholder should be replaced by a Message.")
    }

    @Test
    fun `cancellation correctly interrupts processing and cleans up ledger`() = testScope.runTest {
        // ACT 1: Start the turn.
        store.dispatch(SessionAction.PostUserMessage(sessionId, "Go"))
        runCurrent()

        // ASSERT 1: Verify we are in the processing state.
        assertEquals(AgentStatus.PROCESSING, getAgentState()?.status)
        val turnId = findActiveTurnId()
        assertTrue(turnId != null)

        // ACT 2: Advance time part-way, then dispatch the cancellation.
        advanceTimeBy(1000L)
        store.dispatch(AgentAction.TurnCancelled(turnId!!))
        runCurrent()

        // ASSERT 2: The agent is now WAITING and the placeholder has been removed.
        assertEquals(AgentStatus.WAITING, getAgentState()?.status, "Agent should have returned to WAITING.")
        assertTrue(findActiveTurnId() == null, "Active turn ID should be cleared from transcript.")
        assertEquals(1, getTranscript().size, "Transcript should only contain the user message after placeholder is removed.")

        // ACT 3: Advance time past the original delay to ensure it doesn't post a message.
        advanceTimeBy(2000L)
        runCurrent()

        // ASSERT 3: The transcript remains at 1, confirming the agent logic was cancelled and did not complete.
        assertEquals(1, getTranscript().size)
    }
}