package app.auf.feature.agent

import app.auf.core.AgentCommand
import app.auf.core.AppState
import app.auf.fakes.FakePlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentRuntimeReducerTest {

    private lateinit var feature: AgentRuntimeFeature
    private lateinit var initialState: AppState

    // Define two distinct agents for testing state isolation
    private val agent1 = AgentRuntimeState(
        id = "agent-1",
        archetypeId = "arch-1",
        displayName = "Agent One",
        gatewayId = "gemini",
        selectedModelId = "model-a",
        hkgPersonaId = null,
        turn = AgentTurn.Idle
    )

    private val agent2 = AgentRuntimeState(
        id = "agent-2",
        archetypeId = "arch-2",
        displayName = "Agent Two",
        gatewayId = "gemini",
        selectedModelId = "model-b",
        hkgPersonaId = null,
        turn = AgentTurn.Idle
    )

    @BeforeTest
    fun setup() {
        // Use a fake gateway that requires no real API key or network
        val fakeGateway = object : AgentGateway {
            override suspend fun generateContent(request: AgentRequest) = AgentResponse("ok", null, null)
            override suspend fun listAvailableModels() = listOf("model-a", "model-b")
        }

        feature = AgentRuntimeFeature(
            agentGateway = fakeGateway,
            platform = FakePlatformDependencies(),
            coroutineScope = CoroutineScope(Job()) // A scope for the feature itself
        )

        // The initial state has the feature registered and two idle agents
        initialState = AppState(
            featureStates = mapOf(
                feature.name to AgentRuntimeFeatureState(
                    agents = mapOf(
                        agent1.id to agent1,
                        agent2.id to agent2
                    )
                )
            )
        )
    }

    private fun getFeatureState(state: AppState): AgentRuntimeFeatureState {
        return state.featureStates[feature.name] as AgentRuntimeFeatureState
    }

    @Test
    fun `StartProcessing when agent is Idle should transition that agent to Processing`() = runTest {
        // Arrange
        val job = Job()
        val action = StartProcessing(agentId = "agent-1", turnId = "turn-1", parentEntryId = "parent-1", job = job)

        // Act
        val newState = feature.reducer(initialState, action)
        val newFeatureState = getFeatureState(newState)
        val updatedAgent1 = newFeatureState.agents["agent-1"]!!
        val untouchedAgent2 = newFeatureState.agents["agent-2"]!!

        // Assert
        assertTrue(updatedAgent1.turn is AgentTurn.Processing, "Agent 1 should be Processing")
        val processingTurn = updatedAgent1.turn as AgentTurn.Processing
        assertEquals("turn-1", processingTurn.turnId)
        assertEquals(job, processingTurn.job)
        assertTrue(untouchedAgent2.turn is AgentTurn.Idle, "Agent 2 should remain Idle")
    }

    @Test
    fun `StartProcessing when agent is already Processing should not change state`() = runTest {
        // Arrange
        val initialJob = Job()
        val processingState = AppState(
            featureStates = mapOf(
                feature.name to AgentRuntimeFeatureState(
                    agents = mapOf(
                        agent1.id to agent1.copy(turn = AgentTurn.Processing("turn-1", "parent-1", initialJob)),
                        agent2.id to agent2
                    )
                )
            )
        )
        val secondAction = StartProcessing(agentId = "agent-1", turnId = "turn-2", parentEntryId = "parent-2", job = Job())

        // Act
        val newState = feature.reducer(processingState, secondAction)

        // Assert
        assertEquals(processingState, newState, "State should not change if agent is already processing.")
    }

    @Test
    fun `FinishProcessing when agent is Processing should transition that agent to Idle`() = runTest {
        // Arrange
        val processingState = AppState(
            featureStates = mapOf(
                feature.name to AgentRuntimeFeatureState(
                    agents = mapOf(
                        agent1.id to agent1.copy(turn = AgentTurn.Processing("turn-1", "parent-1", Job())),
                        agent2.id to agent2
                    )
                )
            )
        )
        val action = FinishProcessing(agentId = "agent-1")

        // Act
        val newState = feature.reducer(processingState, action)
        val newFeatureState = getFeatureState(newState)
        val updatedAgent1 = newFeatureState.agents["agent-1"]!!
        val untouchedAgent2 = newFeatureState.agents["agent-2"]!!

        // Assert
        assertTrue(updatedAgent1.turn is AgentTurn.Idle, "Agent 1 should be Idle")
        assertTrue(untouchedAgent2.turn is AgentTurn.Idle, "Agent 2 should remain untouched")
    }

    @Test
    fun `TurnCancelled with matching turnId should cancel job and transition correct agent to Idle`() = runTest {
        // Arrange
        val job1 = Job()
        val job2 = Job() // For the other agent, to ensure it's not cancelled
        val processingState = AppState(
            featureStates = mapOf(
                feature.name to AgentRuntimeFeatureState(
                    agents = mapOf(
                        agent1.id to agent1.copy(turn = AgentTurn.Processing("turn-to-cancel", "parent-1", job1)),
                        agent2.id to agent2.copy(turn = AgentTurn.Processing("other-turn", "parent-2", job2))
                    )
                )
            )
        )
        val action = AgentCommand.TurnCancelled("turn-to-cancel")

        // Act
        val newState = feature.reducer(processingState, action)
        val newFeatureState = getFeatureState(newState)
        val updatedAgent1 = newFeatureState.agents["agent-1"]!!
        val untouchedAgent2 = newFeatureState.agents["agent-2"]!!

        // Assert
        assertTrue(job1.isCancelled, "Job for agent 1 should be cancelled")
        assertFalse(job2.isCancelled, "Job for agent 2 should NOT be cancelled")
        assertTrue(updatedAgent1.turn is AgentTurn.Idle, "Agent 1 should transition to Idle")
        assertTrue(untouchedAgent2.turn is AgentTurn.Processing, "Agent 2 should remain Processing")
    }

    @Test
    fun `TurnCancelled with mismatched turnId should not change any agent state`() = runTest {
        // Arrange
        val job1 = Job()
        val processingState = AppState(
            featureStates = mapOf(
                feature.name to AgentRuntimeFeatureState(
                    agents = mapOf(
                        agent1.id to agent1.copy(turn = AgentTurn.Processing("turn-1", "parent-1", job1))
                    )
                )
            )
        )
        val action = AgentCommand.TurnCancelled("turn-999") // Mismatched ID

        // Act
        val newState = feature.reducer(processingState, action)

        // Assert
        assertFalse(job1.isCancelled, "Job should NOT be cancelled")
        assertEquals(processingState, newState, "State should be unchanged")
    }

    @Test
    fun `UpdateAgentConfig should correctly modify a single agent's configuration`() {
        // Arrange
        val action = UpdateAgentConfig(
            agentId = "agent-2",
            gatewayId = "new-gateway",
            selectedModelId = "new-model",
            hkgPersonaId = "persona-123"
        )

        // Act
        val newState = feature.reducer(initialState, action)
        val newFeatureState = getFeatureState(newState)
        val untouchedAgent1 = newFeatureState.agents["agent-1"]!!
        val updatedAgent2 = newFeatureState.agents["agent-2"]!!

        // Assert
        assertEquals("new-gateway", updatedAgent2.gatewayId)
        assertEquals("new-model", updatedAgent2.selectedModelId)
        assertEquals("persona-123", updatedAgent2.hkgPersonaId)
        assertEquals(agent1, untouchedAgent1, "Agent 1 should be unchanged.")
    }
}