package app.auf.feature.session

import app.auf.core.*
import app.auf.fakes.FakePlatformDependencies
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json

@OptIn(ExperimentalCoroutinesApi::class)
class SessionFeatureReducerTest {

    private lateinit var feature: SessionFeature
    private lateinit var initialState: AppState
    private val platform = FakePlatformDependencies()
    private val sessionId = "default-session"
    private val userEntry = LedgerEntry.Message("entry-1", 1000L, "USER", listOf(TextBlock("Hello")))
    private val agentTurnEntry = LedgerEntry.AgentTurn("turn-1", 2000L, "AgentRuntimeFeature", parentEntryId = null)
    private lateinit var testScope: TestScope // Added for coroutine context

    @BeforeTest
    fun setup() {
        // --- FIX: Provide a TestScope instance for the feature's coroutineScope ---
        testScope = TestScope()
        // FIX: Provide the 'allFeatures' parameter, even if it's empty for this test.
        feature = SessionFeature(platform, Json, coroutineScope = testScope, allFeatures = emptyList())

        initialState = AppState(
            featureStates = mapOf(
                feature.name to SessionFeatureState(
                    sessions = mapOf(
                        sessionId to Session(
                            id = sessionId,
                            name = "Test Session",
                            transcript = listOf(userEntry)
                        )
                    )
                )
            )
        )
    }

    private fun getState(appState: AppState): SessionFeatureState {
        return appState.featureStates[feature.name] as SessionFeatureState
    }

    private fun getTranscript(appState: AppState): List<LedgerEntry> {
        return getState(appState).sessions[sessionId]!!.transcript
    }

    @Test
    fun `reducer on TurnBegan appends AgentTurn to ledger`() {
        // ARRANGE
        val action = AgentEvent.TurnBegan("AgentRuntimeFeature", "turn-new", parentEntryId = null)

        // ACT
        val newState = feature.reducer(initialState, action)

        // ASSERT
        val transcript = getTranscript(newState)
        assertEquals(2, transcript.size)
        assertTrue(transcript.last() is LedgerEntry.AgentTurn)
        assertEquals("turn-new", (transcript.last() as LedgerEntry.AgentTurn).entryId)
    }

    @Test
    fun `reducer on TurnBegan with parentId inserts AgentTurn in correct position`() {
        // ARRANGE
        val action = AgentEvent.TurnBegan("AgentRuntimeFeature", "turn-new", parentEntryId = "entry-1")

        // ACT
        val newState = feature.reducer(initialState, action)

        // ASSERT
        val transcript = getTranscript(newState)
        assertEquals(2, transcript.size)
        assertTrue(transcript is LedgerEntry.Message) // The original entry
        assertTrue(transcript is LedgerEntry.AgentTurn) // The new entry inserted after
    }

    @Test
    fun `reducer on TurnCompleted replaces AgentTurn with Message`() {
        // ARRANGE
        val stateWithTurn = AppState(
            featureStates = mapOf(
                feature.name to SessionFeatureState(
                    sessions = mapOf(sessionId to Session("sid", "sname", listOf(userEntry, agentTurnEntry)))
                )
            )
        )
        val completionContent = listOf(TextBlock("Done."))
        val action = AgentEvent.TurnCompleted("turn-1", completionContent)

        // ACT
        val newState = feature.reducer(stateWithTurn, action)

        // ASSERT
        val transcript = getTranscript(newState)
        assertEquals(2, transcript.size, "FIX: Should still have 2 entries, one replaced.")
        assertTrue(transcript.last() is LedgerEntry.Message)
        assertEquals(completionContent, (transcript.last() as LedgerEntry.Message).content)
    }

    @Test
    fun `reducer on TurnCancelled removes AgentTurn from ledger`() {
        // ARRANGE
        val stateWithTurn = AppState(
            featureStates = mapOf(
                feature.name to SessionFeatureState(
                    sessions = mapOf(sessionId to Session("sid", "sname", listOf(userEntry, agentTurnEntry)))
                )
            )
        )
        // FIX: Update to the correct Command type
        val action = AgentCommand.TurnCancelled("turn-1")

        // ACT
        val newState = feature.reducer(stateWithTurn, action)

        // ASSERT
        val transcript = getTranscript(newState)
        assertEquals(1, transcript.size)
        assertTrue(transcript.last() is LedgerEntry.Message)
    }

    @Test
    fun `reducer on TurnFailed replaces AgentTurn with an error Message`() {
        // ARRANGE
        val stateWithTurn = AppState(
            featureStates = mapOf(
                feature.name to SessionFeatureState(
                    sessions = mapOf(sessionId to Session("sid", "sname", listOf(userEntry, agentTurnEntry)))
                )
            )
        )
        val action = AgentEvent.TurnFailed("turn-1", "It broke")

        // ACT
        val newState = feature.reducer(stateWithTurn, action)

        // ASSERT
        val transcript = getTranscript(newState)
        assertEquals(2, transcript.size, "FIX: Should still have 2 entries, one replaced with an error.")
        val lastMessage = transcript.last()
        assertTrue(lastMessage is LedgerEntry.Message)
        assertTrue((lastMessage.content.first() as TextBlock).text.contains("ERROR: It broke"))
    }
}