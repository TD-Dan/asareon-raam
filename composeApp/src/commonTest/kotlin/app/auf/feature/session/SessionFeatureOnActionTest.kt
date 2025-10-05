package app.auf.feature.session

import app.auf.core.*
import app.auf.fakes.FakePlatformDependencies
import app.auf.util.BasePath
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
     * (reducer -> state update -> onAction) for robust side-effect testing.
     */
    private class TestStore(
        initialState: AppState,
        private val features: List<Feature>,
        platformDependencies: PlatformDependencies
    ) : Store(initialState, features, platformDependencies) {
        val dispatchedActions = mutableListOf<Action>()

        override fun dispatch(originator: String, action: Action) {
            // Mimic the real dispatch sequence for accurate testing.
            // 1. Capture the action.
            val stampedAction = action.copy(originator = originator)
            dispatchedActions.add(stampedAction)

            // 2. Run the real reducer logic to update state.
            super.dispatch(originator, action)
        }
    }

    @Test
    fun `onAction for session POST dispatches filesystem STAGE_UPDATE`() {
        // ARRANGE
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val feature = SessionFeature(fakePlatform, CoroutineScope(Dispatchers.Unconfined))
        val initialSession = Session(id = "sid-1", name = "Initial", ledger = emptyList(), createdAt = 1L)
        val initialState = AppState(featureStates = mapOf(
            feature.name to SessionState(sessions = mapOf("sid-1" to initialSession))
        ))
        val store = TestStore(initialState, listOf(feature), fakePlatform)
        feature.init(store)

        val postAction = Action("session.POST", buildJsonObject {
            put("sessionId", "sid-1")
            put("agentId", "user-daniel")
            put("message", "Live long and prosper.")
        })

        // ACT
        // The original dispatcher must be identified. Let's assume it's a UI component.
        store.dispatch("session.ui", postAction)

        // ASSERT
        // The store should have captured two actions: the original POST, and the subsequent STAGE_UPDATE.
        assertEquals(2, store.dispatchedActions.size)

        val fileSystemAction = store.dispatchedActions.last()
        assertEquals("filesystem.STAGE_UPDATE", fileSystemAction.name)
        assertEquals(feature.name, fileSystemAction.originator, "The originator of the filesystem action should be the SessionFeature itself.")


        // Verify the payload of the filesystem action.
        val payload = fileSystemAction.payload
        assertNotNull(payload)

        // Check path
        val sessionsBasePath = fakePlatform.getBasePathFor(BasePath.SESSIONS)
        val expectedPath = "$sessionsBasePath/sid-1.json"
        assertEquals(expectedPath, payload["path"]?.jsonPrimitive?.content)

        // Check content
        val updatedSession = store.state.value.featureStates[feature.name]
            ?.let { it as SessionState }
            ?.sessions?.get("sid-1")
        assertNotNull(updatedSession)
        val expectedJsonContent = Json { prettyPrint = true }.encodeToString(updatedSession)
        assertEquals(expectedJsonContent, payload["newContent"]?.jsonPrimitive?.content)
    }
}