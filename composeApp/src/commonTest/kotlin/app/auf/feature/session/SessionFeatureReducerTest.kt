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
    private lateinit var testScope: TestScope

    @BeforeTest
    fun setup() {
        testScope = TestScope()
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
        val action = AgentEvent.TurnBegan(rendererFeatureName = "AgentRuntimeFeature", turnId = "turn-new", parentEntryId = null)

        val newState = feature.reducer(initialState, action)

        val transcript = getTranscript(newState)
        assertEquals(2, transcript.size)
        val lastEntry = transcript.last()
        assertTrue(lastEntry is LedgerEntry.AgentTurn)
        assertEquals("turn-new", lastEntry.entryId)
        assertEquals("AgentRuntimeFeature", lastEntry.rendererFeatureName)
    }

    @Test
    fun `reducer on TurnCompleted replaces AgentTurn with Message`() {
        val stateWithTurn = AppState(
            featureStates = mapOf(
                feature.name to SessionFeatureState(
                    sessions = mapOf(sessionId to Session("sid", "sname", listOf(userEntry, agentTurnEntry)))
                )
            )
        )
        val completionContent = listOf(TextBlock("Done."))
        val action = AgentEvent.TurnCompleted("turn-1", completionContent)

        val newState = feature.reducer(stateWithTurn, action)

        val transcript = getTranscript(newState)
        assertEquals(2, transcript.size, "Should still have 2 entries, one replaced.")
        val lastEntry = transcript.last()
        assertTrue(lastEntry is LedgerEntry.Message)
        assertEquals(completionContent, lastEntry.content)
        assertEquals("AgentRuntimeFeature", lastEntry.agentId)
    }

    @Test
    fun `reducer on TurnCancelled removes AgentTurn from ledger`() {
        val stateWithTurn = AppState(
            featureStates = mapOf(
                feature.name to SessionFeatureState(
                    sessions = mapOf(sessionId to Session("sid", "sname", listOf(userEntry, agentTurnEntry)))
                )
            )
        )
        val action = AgentCommand.TurnCancelled("turn-1")

        val newState = feature.reducer(stateWithTurn, action)

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
        assertEquals(2, transcript.size, "Should still have 2 entries, one replaced with an error.")
        val lastMessage = transcript.last()

        // --- FIX IMPLEMENTED ---
        // Replace the brittle `assertTrue` with precise, self-documenting assertions.
        assertTrue(lastMessage is LedgerEntry.Message, "The last entry should now be a Message.")
        assertEquals("CORE", lastMessage.agentId, "The author of the error should be CORE.")
        assertEquals(1, lastMessage.content.size, "The error message should have exactly one content block.")
        val textBlock = lastMessage.content.first() as TextBlock
        assertEquals("ERROR: Agent turn failed. It broke", textBlock.text, "The error text should be exact.")
    }
}