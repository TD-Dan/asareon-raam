package app.auf.integration

import app.auf.core.AppState
import app.auf.core.Store
import app.auf.core.appReducer
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.agent.AgentGateway
import app.auf.feature.agent.AgentRequest
import app.auf.feature.agent.AgentResponse
import app.auf.feature.agent.AgentRuntimeFeature
import app.auf.feature.session.LedgerEntry
import app.auf.feature.session.SessionFeature
import app.auf.feature.session.SessionFeatureState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentSessionIntegrationTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `when AgentRuntimeFeature starts a turn SessionFeature should add an AgentTurn to the transcript`() = runTest {
        // ARRANGE
        val platform = FakePlatformDependencies()

        val fakeGateway = object : AgentGateway {
            override suspend fun generateContent(request: AgentRequest) = AgentResponse("ok", null, null)
            override suspend fun listAvailableModels(): List<String> = emptyList()
        }

        // Inject the TestScope (`this`) into the features so they run on the test scheduler.
        val agentFeature = AgentRuntimeFeature(fakeGateway, platform, coroutineScope = this)
        val sessionFeature = SessionFeature(platform, Json, coroutineScope = this, allFeatures = listOf(agentFeature))

        val features = listOf(agentFeature, sessionFeature)

        val store = Store(
            initialState = AppState(),
            rootReducer = ::appReducer,
            features = features
        )

        // ACT
        // 1. Enqueue the startup coroutines from the features.
        store.startFeatureLifecycles()
        // 2. Explicitly execute all pending tasks on the scheduler.
        runCurrent()

        // ASSERT
        // 3. Now, the state is final and can be asserted on synchronously.
        val finalState = store.state.value

        val sessionState = finalState.featureStates[sessionFeature.name] as SessionFeatureState
        val transcript = sessionState.sessions["default-session"]?.transcript
        assertNotNull(transcript, "Transcript should not be null.")
        assertEquals(1, transcript.size, "Transcript should have exactly one entry.")

        val entry = transcript.first()
        assertTrue(entry is LedgerEntry.AgentTurn, "The entry should be an AgentTurn placeholder.")
        assertEquals(agentFeature.name, entry.rendererFeatureName, "The renderer name should match the agent feature's name.")
    }
}