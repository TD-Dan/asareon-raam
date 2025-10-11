package app.auf.feature.agent

import app.auf.core.*
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreFeature
import app.auf.feature.core.CoreState
import app.auf.feature.gateway.GatewayResponse
import app.auf.feature.session.LedgerEntry
import app.auf.feature.session.Session
import app.auf.feature.session.SessionFeature
import app.auf.feature.session.SessionState
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

class AgentRuntimeFeatureOnActionTest {

    private val testAppVersion = "2.0.0-test"
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val fakePlatform = FakePlatformDependencies(testAppVersion)

    /**
     * A high-fidelity TestStore that correctly mimics the real Store's dispatch lifecycle
     * and allows manual invocation of onPrivateData for robust side-effect testing.
     */
    private class TestStore(
        initialState: AppState,
        private val features: List<Feature>,
        platformDependencies: PlatformDependencies
    ) : Store(initialState, features, platformDependencies) {
        val dispatchedActions = mutableListOf<Action>()

        override fun dispatch(originator: String, action: Action) {
            val stampedAction = action.copy(originator = originator)
            dispatchedActions.add(stampedAction)
            // CRITICAL: We call the real dispatch logic to ensure reducers run before onAction
            super.dispatch(originator, action)
        }
    }

    private fun createTestEnvironment(
        initialAgents: List<AgentInstance> = emptyList(),
        initialSessions: List<Session> = emptyList()
    ): Pair<AgentRuntimeFeature, TestStore> {
        val coreFeature = CoreFeature(fakePlatform)
        val sessionFeature = SessionFeature(fakePlatform, scope)
        val agentFeature = AgentRuntimeFeature(fakePlatform, scope)

        val features = listOf(coreFeature, sessionFeature, agentFeature)

        val initialState = AppState(featureStates = mapOf(
            coreFeature.name to CoreState(lifecycle = AppLifecycle.RUNNING),
            sessionFeature.name to SessionState(sessions = initialSessions.associateBy { it.id }),
            agentFeature.name to AgentRuntimeState(agents = initialAgents.associateBy { it.id })
        ))

        val store = TestStore(initialState, features, fakePlatform)
        return agentFeature to store
    }

    @Test
    fun `TRIGGER_MANUAL_TURN dispatches SET_STATUS and GENERATE_CONTENT on success`() {
        // ARRANGE
        val session = Session("sid-1", "Test Session", listOf(LedgerEntry("eid-1", 1L, "user", "Hello", emptyList())), 1L)
        val agent = AgentInstance("aid-1", "Test Agent", "", "gemini", "gemini-pro", "sid-1", AgentStatus.IDLE)
        val (feature, store) = createTestEnvironment(listOf(agent), listOf(session))

        val triggerAction = Action("agent.TRIGGER_MANUAL_TURN", buildJsonObject { put("agentId", "aid-1") })

        // ACT
        store.dispatch("ui", triggerAction)

        // ASSERT
        val setStatusAction = store.dispatchedActions.find { it.name == "agent.internal.SET_STATUS" }
        assertNotNull(setStatusAction)
        assertEquals("PROCESSING", setStatusAction.payload?.get("status")?.toString()?.trim('"'))

        val generateAction = store.dispatchedActions.find { it.name == "gateway.GENERATE_CONTENT" }
        assertNotNull(generateAction)
        assertEquals("gemini", generateAction.payload?.get("providerId")?.toString()?.trim('"'))
        assertEquals("aid-1", generateAction.payload?.get("correlationId")?.toString()?.trim('"'))
    }

    @Test
    fun `TRIGGER_MANUAL_TURN is ignored if agent is not IDLE`() {
        // ARRANGE
        val agent = AgentInstance("aid-1", "Test Agent", "", "", "", "sid-1", AgentStatus.PROCESSING)
        val (feature, store) = createTestEnvironment(listOf(agent))
        val triggerAction = Action("agent.TRIGGER_MANUAL_TURN", buildJsonObject { put("agentId", "aid-1") })

        // ACT
        store.dispatch("ui", triggerAction)

        // ASSERT
        val generateAction = store.dispatchedActions.find { it.name == "gateway.GENERATE_CONTENT" }
        assertNull(generateAction, "Should not dispatch GENERATE_CONTENT for a busy agent.")
    }

    @Test
    fun `onPrivateData with successful GatewayResponse dispatches POST and SET_STATUS`() {
        // ARRANGE
        val agent = AgentInstance("aid-1", "Test Agent", "", "", "", "sid-1", AgentStatus.PROCESSING)
        val (feature, store) = createTestEnvironment(listOf(agent))
        val response = GatewayResponse("Hello back", null, "aid-1")

        // ACT
        feature.onPrivateData(response, store)

        // ASSERT
        val postAction = store.dispatchedActions.find { it.name == "session.POST" }
        assertNotNull(postAction)
        assertEquals("sid-1", postAction.payload?.get("sessionId")?.toString()?.trim('"'))
        assertEquals("Hello back", postAction.payload?.get("message")?.toString()?.trim('"'))

        val setStatusAction = store.dispatchedActions.find { it.name == "agent.internal.SET_STATUS" }
        assertNotNull(setStatusAction)
        assertEquals("IDLE", setStatusAction.payload?.get("status")?.toString()?.trim('"'))
    }

    @Test
    fun `onPrivateData with failed GatewayResponse dispatches error POST and SET_STATUS`() {
        // ARRANGE
        val agent = AgentInstance("aid-1", "Test Agent", "", "", "", "sid-1", AgentStatus.PROCESSING)
        val (feature, store) = createTestEnvironment(listOf(agent))
        val response = GatewayResponse(null, "API Key Invalid", "aid-1")

        // ACT
        feature.onPrivateData(response, store)

        // ASSERT
        val postAction = store.dispatchedActions.find { it.name == "session.POST" }
        assertNotNull(postAction)
        assertTrue(postAction.payload?.get("message")?.toString()?.contains("API Key Invalid") == true)

        val setStatusAction = store.dispatchedActions.find { it.name == "agent.internal.SET_STATUS" }
        assertNotNull(setStatusAction)
        assertEquals("ERROR", setStatusAction.payload?.get("status")?.toString()?.trim('"'))
    }

    @Test
    fun `agent CREATE action dispatches an agent UPDATED broadcast`() {
        // ARRANGE
        val (feature, store) = createTestEnvironment()
        val createAction = Action("agent.CREATE", buildJsonObject {
            put("name", "Test Agent")
            put("personaId", "p1")
            put("modelProvider", "p")
            put("modelName", "m")
        })

        // ACT
        store.dispatch("ui", createAction)

        // ASSERT
        val updatedAction = store.dispatchedActions.find { it.name == "agent.UPDATED" }
        assertNotNull(updatedAction)
        assertEquals(feature.name, updatedAction.originator)
        assertNotNull(updatedAction.payload?.get("agents")?.toString())
    }
}