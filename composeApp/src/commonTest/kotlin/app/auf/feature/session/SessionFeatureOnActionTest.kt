package app.auf.feature.session

import app.auf.core.*
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreFeature
import app.auf.feature.core.CoreState
import app.auf.util.FileEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.*

class SessionFeatureOnActionTest {

    private val testAppVersion = "2.0.0-test"
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val coreFeature = CoreFeature(FakePlatformDependencies(testAppVersion))
    private val json = Json { ignoreUnknownKeys = true }

    private val testActionRegistry = setOf(
        "system.STARTING",
        "session.CREATE", "session.publish.SESSION_NAMES_UPDATED", "session.POST",
        "session.DELETE", "session.internal.LOADED",
        "filesystem.SYSTEM_LIST", "filesystem.SYSTEM_WRITE", "filesystem.SYSTEM_DELETE", "filesystem.SYSTEM_READ"
    )

    private class TestStore(
        initialState: AppState,
        private val features: List<Feature>,
        platformDependencies: FakePlatformDependencies,
        validActionNames: Set<String>
    ) : Store(initialState, features, platformDependencies, validActionNames) {
        val dispatchedActions = mutableListOf<Action>()

        override fun dispatch(originator: String, action: Action) {
            val stampedAction = action.copy(originator = originator)
            dispatchedActions.add(stampedAction)
            super.dispatch(originator, action)
        }
    }

    private fun createStoreWithRunningLifecycle(fakePlatform: FakePlatformDependencies, vararg initialSessions: Session): TestStore {
        val sessionFeature = SessionFeature(fakePlatform, scope)
        val features = listOf(coreFeature, sessionFeature)
        val initialSessionMap = initialSessions.associateBy { it.id }
        val initialState = AppState(featureStates = mapOf(
            coreFeature.name to CoreState(lifecycle = AppLifecycle.RUNNING),
            sessionFeature.name to SessionState(sessions = initialSessionMap)
        ))
        return TestStore(initialState, features, fakePlatform, testActionRegistry)
    }

    @Test
    fun `onAction for system STARTING dispatches SYSTEM_LIST`() {
        // ARRANGE
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val feature = SessionFeature(fakePlatform, scope)
        val store = TestStore(AppState(featureStates = mapOf(coreFeature.name to CoreState(lifecycle = AppLifecycle.INITIALIZING))), listOf(coreFeature, feature), fakePlatform, testActionRegistry)

        // ACT
        store.dispatch("system.test", Action("system.STARTING"))

        // ASSERT
        val listAction = store.dispatchedActions.find { it.name == "filesystem.SYSTEM_LIST" }
        assertNotNull(listAction, "Should have dispatched filesystem.SYSTEM_LIST")
        assertEquals(feature.name, listAction.originator)
    }

    @Test
    fun `onAction after session CREATE dispatches WRITE and updates names`() {
        // ARRANGE
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val store = createStoreWithRunningLifecycle(fakePlatform)

        // ACT
        store.dispatch("session.ui", Action("session.CREATE"))

        // ASSERT WRITE ACTION
        val writeAction = store.dispatchedActions.find { it.name == "filesystem.SYSTEM_WRITE" }
        assertNotNull(writeAction, "Should dispatch a write action to persist the new session.")
        assertEquals("session", writeAction.originator)
        assertEquals("fake-uuid-1.json", writeAction.payload?.get("subpath")?.jsonPrimitive?.content)

        // ASSERT BROADCAST ACTION
        val broadcastAction = store.dispatchedActions.find { it.name == "session.publish.SESSION_NAMES_UPDATED" }
        assertNotNull(broadcastAction, "Should dispatch a names updated broadcast.")
        assertEquals("session", broadcastAction.originator)
        val names = broadcastAction.payload?.get("names")?.let { json.decodeFromJsonElement(MapSerializer(String.serializer(), String.serializer()), it) }
        assertNotNull(names)
        assertEquals(1, names.size)
        assertEquals("New Session", names["fake-uuid-1"])
    }

    @Test
    fun `onAction for session POST dispatches filesystem SYSTEM_WRITE`() {
        // ARRANGE
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val initialSession = Session(id = "sid-1", name = "Initial", ledger = emptyList(), createdAt = 1L)
        val store = createStoreWithRunningLifecycle(fakePlatform, initialSession)
        val postAction = Action("session.POST", buildJsonObject {
            put("session", "sid-1"); put("senderId", "user"); put("message", "test")
        })

        // ACT
        store.dispatch("session.ui", postAction)

        // ASSERT
        val writeAction = store.dispatchedActions.find { it.name == "filesystem.SYSTEM_WRITE" }
        assertNotNull(writeAction, "A filesystem.SYSTEM_WRITE action should have been dispatched.")
        assertEquals("session", writeAction.originator)
        assertEquals("sid-1.json", writeAction.payload?.get("subpath")?.jsonPrimitive?.content)
    }

    @Test
    fun `onAction for session POST with transient entry does not persist the entry`() {
        // ARRANGE
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val initialSession = Session(id = "sid-1", name = "Initial", ledger = emptyList(), createdAt = 1L)
        val store = createStoreWithRunningLifecycle(fakePlatform, initialSession)
        val persistentAction = Action("session.POST", buildJsonObject {
            put("session", "sid-1"); put("senderId", "user"); put("message", "persistent")
        })
        val transientAction = Action("session.POST", buildJsonObject {
            put("session", "sid-1"); put("senderId", "agent"); put("metadata", buildJsonObject { put("is_transient", true) })
        })

        // ACT
        store.dispatch("session.ui", persistentAction) // First action will trigger a write
        store.dispatch("session.ui", transientAction)  // Second action will trigger another write

        // ASSERT
        val writeActions = store.dispatchedActions.filter { it.name == "filesystem.SYSTEM_WRITE" }
        assertEquals(2, writeActions.size, "Two post actions should trigger two write actions.")
        val finalWriteContent = writeActions.last().payload?.get("content")?.jsonPrimitive?.content
        assertNotNull(finalWriteContent, "Final write action should have content.")

        val persistedSession = json.decodeFromString<Session>(finalWriteContent)
        assertEquals(1, persistedSession.ledger.size, "Persisted ledger should only contain one entry.")
        assertEquals("persistent", persistedSession.ledger.first().rawContent)
    }

    @Test
    fun `onAction for session DELETE dispatches filesystem SYSTEM_DELETE and updates names`() {
        // ARRANGE
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val initialSession = Session(id = "sid-1", name = "Initial", ledger = emptyList(), createdAt = 1L)
        val store = createStoreWithRunningLifecycle(fakePlatform, initialSession)
        val deleteAction = Action("session.DELETE", buildJsonObject { put("session", "sid-1") })

        // ACT
        store.dispatch("session.ui", deleteAction)

        // ASSERT BROADCAST
        val broadcastAction = store.dispatchedActions.find { it.name == "session.publish.SESSION_NAMES_UPDATED" }
        assertNotNull(broadcastAction, "The names update broadcast should have been dispatched.")
        val names = broadcastAction.payload?.get("names")?.let { json.decodeFromJsonElement(MapSerializer(String.serializer(), String.serializer()), it) }
        assertNotNull(names)
        assertTrue(names.isEmpty(), "The broadcasted name map should now be empty.")

        // ASSERT DELETE
        val deleteSysAction = store.dispatchedActions.find { it.name == "filesystem.SYSTEM_DELETE" }
        assertNotNull(deleteSysAction, "The filesystem delete action should have been dispatched.")
        assertEquals("session", deleteSysAction.originator)
        assertEquals("sid-1.json", deleteSysAction.payload?.get("subpath")?.jsonPrimitive?.content)
    }

    @Test
    fun `onPrivateData with file list dispatches SYSTEM_READ for each json file`() {
        // ARRANGE
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val feature = SessionFeature(fakePlatform, scope)
        val store = createStoreWithRunningLifecycle(fakePlatform)
        val fileList = listOf(
            FileEntry("/app/session/session-1.json", false),
            FileEntry("/app/session/session-2.json", false),
            FileEntry("/app/session/notes.txt", false) // Should be ignored
        )

        // ACT
        feature.onPrivateData(fileList, store)

        // ASSERT
        val readActions = store.dispatchedActions.filter { it.name == "filesystem.SYSTEM_READ" }
        assertEquals(2, readActions.size)
        assertEquals("session-1.json", readActions[0].payload?.get("subpath")?.jsonPrimitive?.content)
        assertEquals("session-2.json", readActions[1].payload?.get("subpath")?.jsonPrimitive?.content)
    }

    @Test
    fun `onPrivateData with session file content dispatches internal LOADED`() {
        // ARRANGE
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val feature = SessionFeature(fakePlatform, scope)
        val store = createStoreWithRunningLifecycle(fakePlatform)
        val sessionJsonContent = """{"id":"loaded-1","name":"Loaded Session","ledger":[],"createdAt":1}"""
        val fileContentPayload = buildJsonObject {
            put("subpath", "loaded-1.json")
            put("content", sessionJsonContent)
        }

        // ACT
        feature.onPrivateData(fileContentPayload, store)

        // ASSERT
        val loadedAction = store.dispatchedActions.find { it.name == "session.internal.LOADED" }
        assertNotNull(loadedAction, "Should dispatch internal.LOADED action.")
        assertEquals("session", loadedAction.originator)
        assertNotNull(loadedAction.payload?.get("sessions")?.jsonObject?.get("loaded-1"))
    }
}