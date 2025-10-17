package app.auf.feature.agent

import app.auf.core.*
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreFeature
import app.auf.feature.core.CoreState
import app.auf.feature.filesystem.FileSystemFeature
import app.auf.feature.session.LedgerEntry
import app.auf.feature.session.Session
import app.auf.feature.session.SessionFeature
import app.auf.feature.session.SessionState
import app.auf.util.FileEntry
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.*

class AgentRuntimeFeatureOnActionTest {

    private val testAppVersion = "2.0.0-test"
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val fakePlatform = FakePlatformDependencies(testAppVersion)
    private val json = Json { ignoreUnknownKeys = true }

    // THE FIX: Added the new and previously missing actions to the registry for tests.
    private val testActionRegistry = setOf(
        "system.INITIALIZING", "system.STARTING",
        "agent.CREATE", "agent.UPDATE_CONFIG", "agent.DELETE", "agent.TRIGGER_MANUAL_TURN", "agent.CANCEL_TURN",
        "agent.internal.SET_STATUS", "agent.internal.AGENT_LOADED", "gateway.REQUEST_AVAILABLE_MODELS",
        "filesystem.SYSTEM_WRITE", "filesystem.SYSTEM_DELETE_DIRECTORY", "filesystem.SYSTEM_LIST", "filesystem.SYSTEM_READ",
        "gateway.GENERATE_CONTENT", "gateway.publish.CONTENT_GENERATED", "agent.publish.AGENT_DELETED",
        "agent.publish.AGENT_NAMES_UPDATED",
        "session.POST", "session.DELETE_MESSAGE"
    )

    // THE FIX: This TestStore was passing an empty list to its parent, causing onAction calls to be skipped.
    // It now correctly passes the feature list to the parent Store.
    private class TestStore(
        initialState: AppState,
        features: List<Feature>, // It accepts the list of features...
        platformDependencies: PlatformDependencies,
        validActionNames: Set<String>
    ) : Store(initialState, features, platformDependencies, validActionNames) { // ...and now correctly passes it here.
        val dispatchedActions = mutableListOf<Action>()
        override fun dispatch(originator: String, action: Action) {
            val stampedAction = action.copy(originator = originator)
            dispatchedActions.add(stampedAction)
            super.dispatch(originator, action)
        }
    }

    private fun createTestEnvironment(
        initialAgents: List<AgentInstance> = emptyList(),
        initialSessions: List<Session> = emptyList(),
        initialAvatarCards: Map<String, Map<AgentStatus, String>> = emptyMap()
    ): Pair<AgentRuntimeFeature, TestStore> {
        val coreFeature = CoreFeature(fakePlatform)
        val sessionFeature = SessionFeature(fakePlatform, scope)
        val fileSystemFeature = FileSystemFeature(fakePlatform)
        val agentFeature = AgentRuntimeFeature(fakePlatform, scope)

        val features = listOf(coreFeature, sessionFeature, fileSystemFeature, agentFeature)

        val initialState = AppState(featureStates = mapOf(
            coreFeature.name to CoreState(lifecycle = AppLifecycle.RUNNING),
            sessionFeature.name to SessionState(sessions = initialSessions.associateBy { it.id }),
            agentFeature.name to AgentRuntimeState(agents = initialAgents.associateBy { it.id }, agentAvatarCardIds = initialAvatarCards)
        ))

        val store = TestStore(initialState, features, fakePlatform, testActionRegistry)
        store.initFeatureLifecycles()
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
        store.dispatch("ui", createAction)

        // ASSERT
        val writeAction = store.dispatchedActions.find { it.name == "filesystem.SYSTEM_WRITE" }
        assertNotNull(writeAction, "A SYSTEM_WRITE action should have been dispatched.")
        assertEquals(feature.name, writeAction.originator)
        assertEquals("fake-uuid-1/agent.json", writeAction.payload?.get("subpath")?.jsonPrimitive?.content)
    }

    @Test
    fun `agent DELETE action dispatches filesystem SYSTEM_DELETE_DIRECTORY and AGENT_DELETED`() {
        // ARRANGE
        val agent = AgentInstance("aid-1", "Test", "p", "m", "m")
        val (feature, store) = createTestEnvironment(initialAgents = listOf(agent))
        val deleteAction = Action("agent.DELETE", buildJsonObject { put("agentId", "aid-1") })

        // ACT
        store.dispatch("ui", deleteAction)

        // ASSERT
        val deleteSysAction = store.dispatchedActions.find { it.name == "filesystem.SYSTEM_DELETE_DIRECTORY" }
        assertNotNull(deleteSysAction, "SYSTEM_DELETE_DIRECTORY should be dispatched.")

        val deletedPublishAction = store.dispatchedActions.find { it.name == "agent.publish.AGENT_DELETED" }
        assertNotNull(deletedPublishAction, "AGENT_DELETED should be published.")
        assertEquals("aid-1", deletedPublishAction.payload?.get("agentId")?.jsonPrimitive?.content)
    }

    @Test
    fun `agent DELETE action dispatches session DELETE_MESSAGE for its tracked avatar cards`() {
        // ARRANGE
        val agent = AgentInstance("aid-1", "Test", "p", "m", "m", primarySessionId = "sid-1")
        val avatarCards = mapOf("aid-1" to mapOf(AgentStatus.IDLE to "msg-123", AgentStatus.PROCESSING to "msg-456"))
        val (feature, store) = createTestEnvironment(initialAgents = listOf(agent), initialAvatarCards = avatarCards)
        val deleteAction = Action("agent.DELETE", buildJsonObject { put("agentId", "aid-1") })

        // ACT
        store.dispatch("ui", deleteAction)

        // ASSERT
        val deleteMsgActions = store.dispatchedActions.filter { it.name == "session.DELETE_MESSAGE" }
        assertEquals(2, deleteMsgActions.size, "Should dispatch a delete action for each tracked card.")
        val deletedIds = deleteMsgActions.map { it.payload?.get("messageId")?.jsonPrimitive?.content }.toSet()
        assertTrue(deletedIds.contains("msg-123"))
        assertTrue(deletedIds.contains("msg-456"))
    }


    @Test
    fun `system STARTING action dispatches filesystem SYSTEM_LIST and gateway REQUEST_AVAILABLE_MODELS`() {
        // ARRANGE
        val (feature, store) = createTestEnvironment()

        // ACT
        store.dispatch("system", Action("system.STARTING"))

        // ASSERT
        val listAction = store.dispatchedActions.find { it.name == "filesystem.SYSTEM_LIST" && it.originator == feature.name }
        assertNotNull(listAction, "AgentFeature should request file list on start.")

        val requestModelsAction = store.dispatchedActions.find { it.name == "gateway.REQUEST_AVAILABLE_MODELS" }
        assertNotNull(requestModelsAction, "AgentFeature should request available models on start.")
    }

    @Test
    fun `onPrivateData with directory list dispatches SYSTEM_READ for each agent config`() {
        // ARRANGE
        val (feature, store) = createTestEnvironment()
        val dirList = listOf(FileEntry("/fake/path/agent-1", true), FileEntry("/fake/path/agent-2", true))

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
        val fileContentPayload = buildJsonObject { put("content", agentJsonContent) }

        // ACT
        feature.onPrivateData(fileContentPayload, store)

        // ASSERT
        val loadedAction = store.dispatchedActions.find { it.name == "agent.internal.AGENT_LOADED" }
        assertNotNull(loadedAction, "AGENT_LOADED should be dispatched now that it is in the registry.")
    }

    @Test
    fun `TRIGGER_MANUAL_TURN dispatches SET_STATUS, POST for avatar, and GENERATE_CONTENT`() {
        // ARRANGE
        val session = Session("sid-1", "Test", listOf(LedgerEntry("eid-1", 1L, "user", "Hello", emptyList())), 1L)
        val agent = AgentInstance("aid-1", "Test Agent", "", "gemini", "gemini-pro", "sid-1", false, AgentStatus.IDLE)
        val (feature, store) = createTestEnvironment(listOf(agent), listOf(session))
        val triggerAction = Action("agent.TRIGGER_MANUAL_TURN", buildJsonObject { put("agentId", "aid-1") })

        // ACT
        store.dispatch("ui", triggerAction)

        // ASSERT
        val setStatusAction = store.dispatchedActions.find { it.name == "agent.internal.SET_STATUS" }
        assertNotNull(setStatusAction, "Should set internal status to PROCESSING")
        assertEquals("\"PROCESSING\"", setStatusAction.payload?.get("status").toString())

        val postAction = store.dispatchedActions.find { it.name == "session.POST" }
        assertNotNull(postAction, "Should post new avatar card to session")
        assertEquals("aid-1", postAction.payload?.get("senderId")?.jsonPrimitive?.content)
        assertEquals(true, postAction.payload?.get("metadata")?.jsonObject?.get("render_as_partial")?.jsonPrimitive?.boolean)
        assertEquals("PROCESSING", postAction.payload?.get("metadata")?.jsonObject?.get("agentStatus")?.jsonPrimitive?.content)

        val generateAction = store.dispatchedActions.find { it.name == "gateway.GENERATE_CONTENT" }
        assertNotNull(generateAction, "Should request content from the gateway")
    }

    @Test
    fun `gateway CONTENT_GENERATED dispatches DELETE for old card, POST for response, and POST for new IDLE card`() {
        // ARRANGE
        val agent = AgentInstance("aid-1", "Test Agent", "", "", "", "sid-1", false, AgentStatus.PROCESSING)
        val avatarCards = mapOf("aid-1" to mapOf(AgentStatus.PROCESSING to "msg-processing-123"))
        val (feature, store) = createTestEnvironment(listOf(agent), initialAvatarCards = avatarCards)
        val gatewayResponse = Action("gateway.publish.CONTENT_GENERATED", buildJsonObject {
            put("correlationId", "aid-1"); put("rawContent", "Hello back")
        })

        // ACT
        store.dispatch("gateway", gatewayResponse)

        // ASSERT
        val deleteAction = store.dispatchedActions.find { it.name == "session.DELETE_MESSAGE" }
        assertNotNull(deleteAction, "Should delete the old PROCESSING card.")
        assertEquals("msg-processing-123", deleteAction.payload?.get("messageId")?.jsonPrimitive?.content)

        val postActions = store.dispatchedActions.filter { it.name == "session.POST" }
        assertEquals(2, postActions.size, "Should post two new entries: the response and the new IDLE card.")

        val responsePost = postActions.find { it.payload?.containsKey("message") == true }
        assertNotNull(responsePost, "Should post the agent's text response.")
        assertEquals("Hello back", responsePost.payload?.get("message")?.jsonPrimitive?.content)

        val idleCardPost = postActions.find { it.payload?.containsKey("message") == false }
        assertNotNull(idleCardPost, "Should post the new IDLE avatar card.")
        assertEquals("IDLE", idleCardPost.payload?.get("metadata")?.jsonObject?.get("agentStatus")?.jsonPrimitive?.content)
    }
}