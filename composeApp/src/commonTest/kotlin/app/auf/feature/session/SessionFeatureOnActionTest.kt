package app.auf.feature.session

import app.auf.core.*
import app.auf.fakes.FakePlatformDependencies
import app.auf.util.FileEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SessionFeatureOnActionTest {

    private val testAppVersion = "2.0.0-test"

    /**
     * A high-fidelity TestStore that correctly mimics the real Store's dispatch lifecycle
     * and allows manual invocation of onPrivateData for robust side-effect testing.
     */
    private class TestStore(
        initialState: AppState,
        private val features: List<Feature>,
        platformDependencies: FakePlatformDependencies
    ) : Store(initialState, features, platformDependencies) {
        val dispatchedActions = mutableListOf<Action>()

        override fun dispatch(originator: String, action: Action) {
            val stampedAction = action.copy(originator = originator)
            dispatchedActions.add(stampedAction)
            super.dispatch(originator, action) // Run real reducers and onAction
        }

        // Expose deliverPrivateData for test setup
        public override fun deliverPrivateData(originator: String, recipient: String, data: Any) {
            super.deliverPrivateData(originator, recipient, data)
        }
    }

    @Test
    fun `onAction for system STARTING dispatches filesystem SYSTEM_LIST`() {
        // ARRANGE
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val feature = SessionFeature(fakePlatform, CoroutineScope(Dispatchers.Unconfined))
        val store = TestStore(AppState(), listOf(feature), fakePlatform)

        // ACT
        feature.onAction(Action("system.STARTING"), store)

        // ASSERT
        val dispatched = store.dispatchedActions.singleOrNull()
        assertNotNull(dispatched)
        assertEquals("filesystem.SYSTEM_LIST", dispatched.name)
        assertEquals(feature.name, dispatched.originator)
    }

    @Test
    fun `onPrivateData with file list dispatches SYSTEM_READ for each json file`() {
        // ARRANGE
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val feature = SessionFeature(fakePlatform, CoroutineScope(Dispatchers.Unconfined))
        val store = TestStore(AppState(), listOf(feature), fakePlatform)
        val fileList = listOf(
            FileEntry("/path/to/session1.json", false),
            FileEntry("/path/to/readme.txt", false),
            FileEntry("/path/to/session2.json", false)
        )

        // ACT
        feature.onPrivateData(fileList, store)

        // ASSERT
        val readActions = store.dispatchedActions.filter { it.name == "filesystem.SYSTEM_READ" }
        assertEquals(2, readActions.size, "Should only dispatch READ for .json files.")
        assertEquals("session1.json", readActions[0].payload?.get("subpath")?.jsonPrimitive?.content)
        assertEquals("session2.json", readActions[1].payload?.get("subpath")?.jsonPrimitive?.content)
    }

    @Test
    fun `onAction for session POST dispatches filesystem SYSTEM_WRITE`() {
        // ARRANGE
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val feature = SessionFeature(fakePlatform, CoroutineScope(Dispatchers.Unconfined))
        val initialSession = Session(id = "sid-1", name = "Initial", ledger = emptyList(), createdAt = 1L)
        val initialState = AppState(featureStates = mapOf(
            feature.name to SessionState(sessions = mapOf("sid-1" to initialSession))
        ))
        val store = TestStore(initialState, listOf(feature), fakePlatform)

        val postAction = Action("session.POST", buildJsonObject {
            put("sessionId", "sid-1")
            put("agentId", "user")
            put("message", "test")
        })

        // ACT
        // Dispatching the action will run the reducer (updating state) then onAction.
        store.dispatch("session.ui", postAction)

        // ASSERT
        val writeAction = store.dispatchedActions.find { it.name == "filesystem.SYSTEM_WRITE" }
        assertNotNull(writeAction)
        assertEquals(feature.name, writeAction.originator)
        assertEquals("sid-1.json", writeAction.payload?.get("subpath")?.jsonPrimitive?.content)
        assertNotNull(writeAction.payload?.get("content")?.jsonPrimitive?.content)
    }

    @Test
    fun `onAction for session DELETE dispatches filesystem SYSTEM_DELETE`() {
        // ARRANGE
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val feature = SessionFeature(fakePlatform, CoroutineScope(Dispatchers.Unconfined))
        val store = TestStore(AppState(), listOf(feature), fakePlatform)
        val deleteAction = Action("session.DELETE", buildJsonObject { put("sessionId", "sid-1") })

        // ACT
        feature.onAction(deleteAction, store)

        // ASSERT
        val deleteSysAction = store.dispatchedActions.singleOrNull()
        assertNotNull(deleteSysAction)
        assertEquals("filesystem.SYSTEM_DELETE", deleteSysAction.name)
        assertEquals(feature.name, deleteSysAction.originator)
        assertEquals("sid-1.json", deleteSysAction.payload?.get("subpath")?.jsonPrimitive?.content)
    }
}