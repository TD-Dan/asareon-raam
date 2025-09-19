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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentSessionIntegrationTest {

    private lateinit var store: Store
    private lateinit var agentFeature: AgentRuntimeFeature
    private lateinit var sessionFeature: SessionFeature
    private lateinit var platform: FakePlatformDependencies
    private lateinit var coroutineScope: CoroutineScope

    @BeforeTest
    fun setup() {
        platform = FakePlatformDependencies()
        coroutineScope = CoroutineScope(Job())

        val fakeGateway = object : AgentGateway {
            override suspend fun generateContent(request: AgentRequest) = AgentResponse("ok", null, null)
            override suspend fun listAvailableModels(): List<String> = emptyList()
        }

        agentFeature = AgentRuntimeFeature(fakeGateway, platform, coroutineScope)
        sessionFeature = SessionFeature(platform, Json, coroutineScope, listOf(agentFeature))

        // --- FIX IMPLEMENTED ---
        // The order of features is critical for initialization. SessionFeature must be
        // initialized before AgentRuntimeFeature to ensure a session exists when the
        // agent feature starts dispatching events.
        val features = listOf(sessionFeature, agentFeature)

        store = Store(
            initialState = AppState(),
            rootReducer = ::appReducer,
            features = features,
            coroutineScope = coroutineScope
        )
    }

    @Test
    fun `when AgentRuntimeFeature starts a turn SessionFeature should add an AgentTurn to the transcript`() = runTest {
        // ACT
        // startFeatureLifecycles triggers the start() methods in the CORRECT order.
        // 1. SessionFeature creates a default session.
        // 2. AgentRuntimeFeature sees its agent is idle and dispatches TurnBegan.
        // 3. SessionFeature's reducer receives TurnBegan and now correctly finds the session.
        store.startFeatureLifecycles()

        // ASSERT
        // The test will now pass because the transcript will become non-empty.
        val finalState = store.state.first {
            val sessionState = it.featureStates[sessionFeature.name] as? SessionFeatureState
            sessionState?.sessions?.get("default-session")?.transcript?.isNotEmpty() == true
        }

        val sessionState = finalState.featureStates[sessionFeature.name] as SessionFeatureState
        val transcript = sessionState.sessions["default-session"]?.transcript
        assertNotNull(transcript, "Transcript should not be null.")
        assertEquals(1, transcript.size, "Transcript should have exactly one entry.")

        val entry = transcript.first()
        assertTrue(entry is LedgerEntry.AgentTurn, "The entry should be an AgentTurn placeholder.")
        assertEquals(agentFeature.name, entry.rendererFeatureName, "The renderer name should match the agent feature's name.")
    }
}