package app.auf.feature.session

import app.auf.core.Action
import app.auf.core.AppState
import app.auf.fakes.FakePlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionFeatureReducerTest {

    private val testAppVersion = "2.0.0-test"

    @Test
    fun `reducer session_CREATE adds a new session and does NOT set it as active`() {
        // ARRANGE
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        fakePlatform.currentTime = 12345L // Set a predictable timestamp
        val feature = SessionFeature(fakePlatform, CoroutineScope(Dispatchers.Unconfined))
        val initialState = AppState(featureStates = mapOf(feature.name to SessionState(activeSessionId = "some-other-session")))
        val action = Action("session.CREATE", buildJsonObject {
            put("name", "Test Session")
        })

        // ACT
        val newState = feature.reducer(initialState, action)

        // ASSERT
        val newSessionState = newState.featureStates[feature.name] as? SessionState
        assertNotNull(newSessionState, "SessionState should not be null.")
        assertEquals(1, newSessionState.sessions.size, "There should be one session in the map.")

        val createdSession = newSessionState.sessions["fake-uuid-1"]
        assertNotNull(createdSession, "The created session should exist under the fake UUID.")
        assertEquals("fake-uuid-1", createdSession.id)
        assertEquals("Test Session", createdSession.name)
        assertTrue(createdSession.ledger.isEmpty(), "The new session's ledger should be empty.")
        assertEquals(12345L, createdSession.createdAt, "Timestamp should match the fake platform time.")

        // CRITICAL: Verify the action's scope was limited.
        assertEquals("some-other-session", newSessionState.activeSessionId, "CREATE action should NOT change the active session ID.")
    }
}