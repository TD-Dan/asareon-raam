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
import kotlin.test.assertIs
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

    @Test
    fun `reducer session_POST adds a parsed message to the correct session ledger`() {
        // ARRANGE
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        fakePlatform.currentTime = 54321L
        val feature = SessionFeature(fakePlatform, CoroutineScope(Dispatchers.Unconfined))
        val initialSession = Session(id = "sid-1", name = "Initial", ledger = emptyList(), createdAt = 1L)
        val initialState = AppState(featureStates = mapOf(feature.name to SessionState(sessions = mapOf("sid-1" to initialSession))))
        val action = Action("session.POST", buildJsonObject {
            put("sessionId", "sid-1")
            put("agentId", "user-daniel")
            put("message", "Hello ```kt\nval x=1\n```")
        })

        // ACT
        val newState = feature.reducer(initialState, action)

        // ASSERT
        val sessionState = newState.featureStates[feature.name] as SessionState
        val updatedSession = sessionState.sessions["sid-1"]
        assertNotNull(updatedSession)
        assertEquals(1, updatedSession.ledger.size)

        val newEntry = updatedSession.ledger.first()
        assertEquals("fake-uuid-1", newEntry.id)
        assertEquals(54321L, newEntry.timestamp)
        assertEquals("user-daniel", newEntry.agentId)
        assertEquals("Hello ```kt\nval x=1\n```", newEntry.rawContent)
        assertEquals(2, newEntry.content.size)
        assertIs<ContentBlock.Text>(newEntry.content[0])
        assertEquals("Hello ", (newEntry.content[0] as ContentBlock.Text).text)
        assertIs<ContentBlock.CodeBlock>(newEntry.content[1])
        assertEquals("kt", (newEntry.content[1] as ContentBlock.CodeBlock).language)
        assertEquals("val x=1\n", (newEntry.content[1] as ContentBlock.CodeBlock).code)
    }

    @Test
    fun `reducer session_POST to non_existent session does nothing`() {
        // ARRANGE
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val feature = SessionFeature(fakePlatform, CoroutineScope(Dispatchers.Unconfined))
        val initialState = AppState(featureStates = mapOf(feature.name to SessionState()))
        val action = Action("session.POST", buildJsonObject {
            put("sessionId", "non-existent-id")
            put("agentId", "user-daniel")
            put("message", "This should not be posted.")
        })

        // ACT
        val newState = feature.reducer(initialState, action)

        // ASSERT
        assertEquals(initialState, newState, "State should be unchanged for a non-existent session ID.")
    }

    @Test
    fun `reducer session_DELETE removes a session`() {
        // ARRANGE
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val feature = SessionFeature(fakePlatform, CoroutineScope(Dispatchers.Unconfined))
        val session1 = Session(id = "sid-1", name = "s1", ledger = emptyList(), createdAt = 1L)
        val session2 = Session(id = "sid-2", name = "s2", ledger = emptyList(), createdAt = 2L)
        val initialState = AppState(featureStates = mapOf(feature.name to SessionState(sessions = mapOf("sid-1" to session1, "sid-2" to session2))))
        val action = Action("session.DELETE", buildJsonObject { put("sessionId", "sid-1") })

        // ACT
        val newState = feature.reducer(initialState, action)

        // ASSERT
        val sessionState = newState.featureStates[feature.name] as SessionState
        assertEquals(1, sessionState.sessions.size)
        assertNull(sessionState.sessions["sid-1"])
        assertNotNull(sessionState.sessions["sid-2"])
    }

    @Test
    fun `reducer session_DELETE clears activeSessionId if the active session is deleted`() {
        // ARRANGE
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val feature = SessionFeature(fakePlatform, CoroutineScope(Dispatchers.Unconfined))
        val session1 = Session(id = "sid-1", name = "s1", ledger = emptyList(), createdAt = 1L)
        val initialState = AppState(featureStates = mapOf(feature.name to SessionState(
            sessions = mapOf("sid-1" to session1),
            activeSessionId = "sid-1"
        )))
        val action = Action("session.DELETE", buildJsonObject { put("sessionId", "sid-1") })

        // ACT
        val newState = feature.reducer(initialState, action)

        // ASSERT
        val sessionState = newState.featureStates[feature.name] as SessionState
        assertTrue(sessionState.sessions.isEmpty())
        assertNull(sessionState.activeSessionId, "Active session ID should be cleared.")
    }

    @Test
    fun `reducer session_SET_ACTIVE_TAB updates the activeSessionId`() {
        // ARRANGE
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val feature = SessionFeature(fakePlatform, CoroutineScope(Dispatchers.Unconfined))
        // --- FIX: The sessions map must contain the IDs being referenced for the reducer's guard to pass. ---
        val session1 = Session(id = "sid-1", name = "s1", ledger = emptyList(), createdAt = 1L)
        val session2 = Session(id = "sid-2", name = "s2", ledger = emptyList(), createdAt = 2L)
        val initialState = AppState(featureStates = mapOf(feature.name to SessionState(
            sessions = mapOf("sid-1" to session1, "sid-2" to session2),
            activeSessionId = "sid-1"
        )))
        val action = Action("session.SET_ACTIVE_TAB", buildJsonObject { put("sessionId", "sid-2") })

        // ACT
        val newState = feature.reducer(initialState, action)

        // ASSERT
        val sessionState = newState.featureStates[feature.name] as SessionState
        assertEquals("sid-2", sessionState.activeSessionId)
    }

    @Test
    fun `reducer session_SET_ACTIVE_TAB to non_existent id does nothing`() {
        // ARRANGE
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val feature = SessionFeature(fakePlatform, CoroutineScope(Dispatchers.Unconfined))
        val initialState = AppState(featureStates = mapOf(feature.name to SessionState(activeSessionId = "sid-1")))
        val action = Action("session.SET_ACTIVE_TAB", buildJsonObject { put("sessionId", "non-existent") })

        // ACT
        val newState = feature.reducer(initialState, action)

        // ASSERT
        assertEquals(initialState, newState, "State should be unchanged.")
    }
}