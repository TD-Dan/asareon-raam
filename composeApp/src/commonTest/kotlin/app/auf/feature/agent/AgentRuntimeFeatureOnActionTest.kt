package app.auf.feature.agent

import app.auf.core.*
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreFeature
import app.auf.feature.core.CoreState
import app.auf.feature.filesystem.FileSystemFeature
import app.auf.feature.gateway.GatewayResponse
import app.auf.feature.session.LedgerEntry
import app.auf.feature.session.Session
import app.auf.feature.session.SessionFeature
import app.auf.feature.session.SessionState
import app.auf.util.FileEntry
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.*

class AgentRuntimeFeatureOnActionTest {

    private val testAppVersion = "2.0.0-test"
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val fakePlatform = FakePlatformDependencies(testAppVersion)
    private val json = Json { ignoreUnknownKeys = true }

    private class TestStore(
        initialState: AppState,
        features: List<Feature>,
        platformDependencies: PlatformDependencies
    ) : Store(initialState, features, platformDependencies) {
        val dispatchedActions = mutableListOf<Action>()
        override fun dispatch(originator: String, action: Action) {
            val stampedAction = action.copy(originator = originator)
            dispatchedActions.add(stampedAction)
            super.dispatch(originator, action)
        }
    }

    private fun createTestEnvironment(
        initialAgents: List<AgentInstance> = emptyList(),
        initialSessions: List<Session> = emptyList()
    ): Pair<AgentRuntimeFeature, TestStore> {
        val coreFeature = CoreFeature(fakePlatform)
        val sessionFeature = SessionFeature(fakePlatform, scope)
        val fileSystemFeature = FileSystemFeature(fakePlatform) // <-- Now included
        val agentFeature = AgentRuntimeFeature(fakePlatform, scope)

        val features = listOf(coreFeature, sessionFeature, fileSystemFeature, agentFeature)

        val initialState = AppState(featureStates = mapOf(
            coreFeature.name to CoreState(lifecycle = AppLifecycle.RUNNING),
            sessionFeature.name to SessionState(sessions = initialSessions.associateBy { it.id }),
            agentFeature.name to AgentRuntimeState(agents = initialAgents.associateBy { it.id })
        ))

        val store = TestStore(initialState, features, fakePlatform)
        return agentFeature to store
    }

    @Test
    fun `agent CREATE action dispatches filesystem SYSTEM_WRITE to a new sandbox directory`() {
        // ARRANGE
        val (feature, store) = createTestEnvironment()
        val createAction = Action("agent.CREATE", buildJsonObject {
            put("name", "Test Agent"); put("personaId", "p1"); put("modelProvider", "p"); put("modelName", "m")
        })

        // ACT
        store.dispatch("ui", createAction) // This runs the reducer, then onAction

        // ASSERT
        val writeAction = store.dispatchedActions.find { it.name == "filesystem.SYSTEM_WRITE" }
        assertNotNull(writeAction, "A SYSTEM_WRITE action should have been dispatched.")
        assertEquals(feature.name, writeAction.originator, "Originator should be the agent feature.")
        assertEquals("fake-uuid-1/agent.json", writeAction.payload?.get("subpath")?.jsonPrimitive?.content)

        val content = writeAction.payload?.get("content")?.jsonPrimitive?.content
        assertNotNull(content)
        val agentInFile = json.decodeFromString<AgentInstance>(content)
        assertEquals("fake-uuid-1", agentInFile.id)
        assertEquals("Test Agent", agentInFile.name)
    }

    @Test
    fun `agent DELETE action dispatches filesystem SYSTEM_DELETE_DIRECTORY for the agent's directory`() {
        // ARRANGE
        val agent = AgentInstance("aid-1", "Test", "p", "m", "m")
        val (feature, store) = createTestEnvironment(initialAgents = listOf(agent))
        val deleteAction = Action("agent.DELETE", buildJsonObject { put("agentId", "aid-1") })

        // ACT
        store.dispatch("ui", deleteAction)

        // ASSERT
        val deleteSysAction = store.dispatchedActions.find { it.name == "filesystem.SYSTEM_DELETE_DIRECTORY" }
        assertNotNull(deleteSysAction)
        assertEquals(feature.name, deleteSysAction.originator)
        assertEquals("aid-1", deleteSysAction.payload?.get("subpath")?.jsonPrimitive?.content, "Should delete the entire directory.")
    }

    @Test
    fun `system STARTING action dispatches filesystem SYSTEM_LIST`() {
        // ARRANGE
        // Create a special store that starts in the BOOTING state to correctly test the lifecycle.
        val coreFeature = CoreFeature(fakePlatform)
        val agentFeature = AgentRuntimeFeature(fakePlatform, scope)
        val fileSystemFeature = FileSystemFeature(fakePlatform)
        val features = listOf(coreFeature, agentFeature, fileSystemFeature)
        val bootState = AppState(featureStates = mapOf(coreFeature.name to CoreState(lifecycle = AppLifecycle.BOOTING)))
        val store = TestStore(bootState, features, fakePlatform)

        // ACT
        // 1. First, dispatch INITIALIZING to move the store to the correct state.
        store.dispatch("system", Action("system.INITIALIZING"))
        // 2. Now, dispatch STARTING, which should pass the guard.
        store.dispatch("system", Action("system.STARTING"))


        // ASSERT
        val listAction = store.dispatchedActions.find { it.name == "filesystem.SYSTEM_LIST" }
        assertNotNull(listAction)
        assertEquals(agentFeature.name, listAction.originator)
    }

    @Test
    fun `onPrivateData with directory list dispatches SYSTEM_READ for each agent config`() {
        // ARRANGE
        val (feature, store) = createTestEnvironment()
        val dirList = listOf(
            FileEntry("/app/agent/agent-1", true),
            FileEntry("/app/agent/agent-2", true),
            FileEntry("/app/agent/some-file.txt", false) // Should be ignored
        )

        // ACT
        feature.onPrivateData(dirList, store)

        // ASSERT
        val readActions = store.dispatchedActions.filter { it.name == "filesystem.SYSTEM_READ" }
        assertEquals(2, readActions.size)
        assertEquals("agent-1/agent.json", readActions[0].payload?.get("subpath")?.jsonPrimitive?.content)
        assertEquals("agent-2/agent.json", readActions[1].payload?.get("subpath")?.jsonPrimitive?.content)
    }

    @Test
    fun `onPrivateData with agent config content dispatches internal AGENT_LOADED`() {
        // ARRANGE
        val (feature, store) = createTestEnvironment()
        val agentJsonContent = """{"id":"loaded-agent-1","name":"Loaded Agent","personaId":"p","modelProvider":"m","modelName":"m"}"""
        val fileContentPayload = buildJsonObject {
            put("subpath", "loaded-agent-1/agent.json")
            put("content", agentJsonContent)
        }

        // ACT
        feature.onPrivateData(fileContentPayload, store)

        // ASSERT
        val loadedAction = store.dispatchedActions.find { it.name == "agent.internal.AGENT_LOADED" }
        assertNotNull(loadedAction)
        assertEquals(feature.name, loadedAction.originator)
        assertEquals("loaded-agent-1", loadedAction.payload?.get("id")?.jsonPrimitive?.content)
    }

    // --- Cognitive Cycle tests remain the same as they don't depend on persistence details ---

    @Test
    fun `TRIGGER_MANUAL_TURN dispatches SET_STATUS and GENERATE_CONTENT on success`() {
        val session = Session("sid-1", "Test", listOf(LedgerEntry("eid-1", 1L, "user", "Hello", emptyList())), 1L)
        val agent = AgentInstance("aid-1", "Test Agent", "", "gemini", "gemini-pro", "sid-1", AgentStatus.IDLE)
        val (feature, store) = createTestEnvironment(listOf(agent), listOf(session))
        val triggerAction = Action("agent.TRIGGER_MANUAL_TURN", buildJsonObject { put("agentId", "aid-1") })
        store.dispatch("ui", triggerAction)
        val setStatusAction = store.dispatchedActions.find { it.name == "agent.internal.SET_STATUS" }
        assertNotNull(setStatusAction)
        assertEquals("\"PROCESSING\"", setStatusAction.payload?.get("status").toString())
        val generateAction = store.dispatchedActions.find { it.name == "gateway.GENERATE_CONTENT" }
        assertNotNull(generateAction)
        assertEquals("gemini", generateAction.payload?.get("providerId")?.jsonPrimitive?.content)
    }

    @Test
    fun `onPrivateData with successful GatewayResponse dispatches POST and SET_STATUS`() {
        val agent = AgentInstance("aid-1", "Test Agent", "", "", "", "sid-1", AgentStatus.PROCESSING)
        val (feature, store) = createTestEnvironment(listOf(agent))
        val response = GatewayResponse("Hello back", null, "aid-1")
        feature.onPrivateData(response, store)
        val postAction = store.dispatchedActions.find { it.name == "session.POST" }
        assertNotNull(postAction)
        assertEquals("sid-1", postAction.payload?.get("sessionId")?.jsonPrimitive?.content)
        val setStatusAction = store.dispatchedActions.find { it.name == "agent.internal.SET_STATUS" }
        assertNotNull(setStatusAction)
        assertEquals("\"IDLE\"", setStatusAction.payload?.get("status").toString())
    }
}