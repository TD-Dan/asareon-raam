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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.*

class SessionFeatureOnActionTest {

    private val testAppVersion = "2.0.0-test"
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val coreFeature = CoreFeature(FakePlatformDependencies(testAppVersion))
    private val json = Json { ignoreUnknownKeys = true }

    private val testActionRegistry = setOf(
        // THE FIX: The lifecycle actions are also required.
        "system.INITIALIZING",
        "system.STARTING",
        "session.REQUEST_SESSION_NAMES",
        "session.CREATE",
        "session.publish.SESSION_NAMES_UPDATED",
        "session.POST",
        "session.DELETE",
        "filesystem.SYSTEM_LIST",
        "filesystem.SYSTEM_WRITE",
        "filesystem.SYSTEM_DELETE"
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
    fun `onAction for system STARTING dispatches SYSTEM_LIST and REQUEST_SESSION_NAMES`() {
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val feature = SessionFeature(fakePlatform, scope)
        // THE FIX: Start in the BOOTING state to correctly test the lifecycle.
        val store = TestStore(AppState(featureStates = mapOf(coreFeature.name to CoreState(lifecycle = AppLifecycle.BOOTING))), listOf(coreFeature, feature), fakePlatform, testActionRegistry)

        // THE FIX: Dispatch INITIALIZING first to move to the correct state.
        store.dispatch("system.test", Action("system.INITIALIZING"))
        store.dispatch("system.test", Action("system.STARTING"))

        val listAction = store.dispatchedActions.find { it.name == "filesystem.SYSTEM_LIST" }
        val requestNamesAction = store.dispatchedActions.find { it.name == "session.REQUEST_SESSION_NAMES" }

        assertNotNull(listAction, "Should have dispatched filesystem.SYSTEM_LIST")
        assertEquals(feature.name, listAction.originator)

        assertNotNull(requestNamesAction, "Should have dispatched session.REQUEST_SESSION_NAMES")
        assertEquals(feature.name, requestNamesAction.originator)
    }

    @Test
    fun `onAction after session CREATE dispatches session names updated`() {
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val store = createStoreWithRunningLifecycle(fakePlatform)

        store.dispatch("session.ui", Action("session.CREATE"))

        val broadcastAction = store.dispatchedActions.last()
        assertEquals("session.publish.SESSION_NAMES_UPDATED", broadcastAction.name)
        assertEquals("session", broadcastAction.originator)
        assertNotNull(broadcastAction.payload)

        val names = broadcastAction.payload["names"]?.let { json.decodeFromJsonElement(MapSerializer(String.serializer(), String.serializer()), it) }
        assertNotNull(names)
        assertEquals(1, names.size)
        assertEquals("New Session", names["fake-uuid-1"])
    }

    @Test
    fun `onAction for session POST dispatches filesystem SYSTEM_WRITE`() {
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val initialSession = Session(id = "sid-1", name = "Initial", ledger = emptyList(), createdAt = 1L)
        val store = createStoreWithRunningLifecycle(fakePlatform, initialSession)
        val postAction = Action("session.POST", buildJsonObject {
            put("session", "sid-1")
            put("agentId", "user")
            put("message", "test")
        })

        store.dispatch("session.ui", postAction)

        val writeAction = store.dispatchedActions.find { it.name == "filesystem.SYSTEM_WRITE" }
        assertNotNull(writeAction, "A filesystem.SYSTEM_WRITE action should have been dispatched.")
        assertEquals("session", writeAction.originator)
        assertEquals("sid-1.json", writeAction.payload?.get("subpath")?.jsonPrimitive?.content)
    }

    @Test
    fun `onAction for session DELETE dispatches filesystem SYSTEM_DELETE and updates names`() {
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val initialSession = Session(id = "sid-1", name = "Initial", ledger = emptyList(), createdAt = 1L)
        val store = createStoreWithRunningLifecycle(fakePlatform, initialSession)
        val deleteAction = Action("session.DELETE", buildJsonObject { put("session", "sid-1") })

        store.dispatch("session.ui", deleteAction)

        val deleteSysAction = store.dispatchedActions.find { it.name == "filesystem.SYSTEM_DELETE" }
        assertNotNull(deleteSysAction)
        assertEquals("session", deleteSysAction.originator)
        assertEquals("sid-1.json", deleteSysAction.payload?.get("subpath")?.jsonPrimitive?.content)

        val broadcastAction = store.dispatchedActions.last()
        assertEquals("session.publish.SESSION_NAMES_UPDATED", broadcastAction.name)
        val names = broadcastAction.payload?.get("names")?.let { json.decodeFromJsonElement(MapSerializer(String.serializer(), String.serializer()), it) }
        assertNotNull(names)
        assertTrue(names.isEmpty(), "The broadcasted name map should now be empty.")
    }
}