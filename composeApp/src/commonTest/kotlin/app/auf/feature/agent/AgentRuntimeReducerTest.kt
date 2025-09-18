package app.auf.feature.agent

import app.auf.core.*
import app.auf.fakes.FakePlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentRuntimeReducerTest {

    private lateinit var feature: AgentRuntimeFeature
    private lateinit var initialState: AppState

    @BeforeTest
    fun setup() {
        // Use a fake platform and a test scope
        feature = AgentRuntimeFeature(
            agentGateway = FakeAgentGateway(), // A simple fake for testing
            platform = FakePlatformDependencies(),
            coroutineScope = CoroutineScope(Job())
        )
        // Ensure the feature state is initialized
        initialState = AppState().copy(
            featureStates = mapOf(
                feature.name to AgentRuntimeFeatureState(agent = AgentRuntimeState())
            )
        )
    }

    private fun getState(state: AppState): AgentRuntimeState {
        return (state.featureStates[feature.name] as AgentRuntimeFeatureState).agent
    }

    @Test
    fun `_StartProcessing when Idle should transition to Processing`() = runTest {
        // Arrange
        val job = Job()
        val action = AgentRuntimeAction._StartProcessing("turn-1", "parent-1", job)

        // Act
        val newState = feature.reducer(initialState, action)
        val agentState = getState(newState)

        // Assert
        assertTrue(agentState.turn is AgentTurn.Processing, "Turn should be Processing")
        val processingTurn = agentState.turn as AgentTurn.Processing
        assertEquals("turn-1", processingTurn.turnId)
        assertEquals("parent-1", processingTurn.parentEntryId)
        assertEquals(job, processingTurn.job)
    }

    @Test
    fun `_StartProcessing when already Processing should not change state`() = runTest {
        // Arrange
        val initialJob = Job()
        val processingState = initialState.copy(
            featureStates = mapOf(
                feature.name to AgentRuntimeFeatureState(
                    agent = AgentRuntimeState(turn = AgentTurn.Processing("turn-1", "parent-1", initialJob))
                )
            )
        )
        val secondAction = AgentRuntimeAction._StartProcessing("turn-2", "parent-2", Job())

        // Act
        val newState = feature.reducer(processingState, secondAction)
        val agentState = getState(newState)

        // Assert
        assertTrue(agentState.turn is AgentTurn.Processing)
        assertEquals("turn-1", (agentState.turn as AgentTurn.Processing).turnId, "Turn ID should not have changed")
        assertEquals(initialJob, (agentState.turn as AgentTurn.Processing).job, "Job should not have changed")
    }

    @Test
    fun `_FinishProcessing when Processing should transition to Idle`() = runTest {
        // Arrange
        val processingState = initialState.copy(
            featureStates = mapOf(
                feature.name to AgentRuntimeFeatureState(
                    agent = AgentRuntimeState(turn = AgentTurn.Processing("turn-1", "parent-1", Job()))
                )
            )
        )
        val action = AgentRuntimeAction._FinishProcessing

        // Act
        val newState = feature.reducer(processingState, action)
        val agentState = getState(newState)

        // Assert
        assertTrue(agentState.turn is AgentTurn.Idle, "Turn should be Idle")
    }

    @Test
    fun `TurnCancelled with matching turnId should cancel job and transition to Idle`() = runTest {
        // Arrange
        val job = Job()
        val processingState = initialState.copy(
            featureStates = mapOf(
                feature.name to AgentRuntimeFeatureState(
                    agent = AgentRuntimeState(turn = AgentTurn.Processing("turn-1", "parent-1", job))
                )
            )
        )
        val action = AgentAction.TurnCancelled("turn-1")

        // Act
        val newState = feature.reducer(processingState, action)
        val agentState = getState(newState)

        // Assert
        assertTrue(job.isCancelled, "Job should be cancelled")
        assertTrue(agentState.turn is AgentTurn.Idle, "Turn should be Idle")
    }

    @Test
    fun `TurnCancelled with mismatched turnId should not change state`() = runTest {
        // Arrange
        val job = Job()
        val processingState = initialState.copy(
            featureStates = mapOf(
                feature.name to AgentRuntimeFeatureState(
                    agent = AgentRuntimeState(turn = AgentTurn.Processing("turn-1", "parent-1", job))
                )
            )
        )
        val action = AgentCommand.TurnCancelled("turn-999") // Mismatched ID

        // Act
        val newState = feature.reducer(processingState, action)
        val agentState = getState(newState)

        // Assert
        assertTrue(!job.isCancelled, "Job should NOT be cancelled")
        assertTrue(agentState.turn is AgentTurn.Processing, "Turn should still be Processing")
    }
}

// A minimal fake implementation for testing purposes
private class FakeAgentGateway : AgentGateway {
    override suspend fun generateContent(request: AgentRequest): AgentResponse {
        return AgentResponse("fake response", null, null)
    }
    override suspend fun listAvailableModels(): List<String> {
        return listOf("fake-model")
    }
}