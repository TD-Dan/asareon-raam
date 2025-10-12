package app.auf.feature.session

import app.auf.core.Action
import app.auf.core.AppState
import app.auf.fakes.FakePlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.*
import kotlin.test.*

class SessionFeatureReducerTest {

    private val testAppVersion = "2.0.0-test"
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Test
    fun `reducer session_CREATE adds a new session and sets it as active`() {
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        fakePlatform.currentTime = 12345L
        val feature = SessionFeature(fakePlatform, scope)
        val initialState = AppState(featureStates = mapOf(feature.name to SessionState()))
        val action = Action("session.CREATE", buildJsonObject { put("name", "Test Session") })

        val newState = feature.reducer(initialState, action)

        val newSessionState = newState.featureStates[feature.name] as SessionState
        assertEquals(1, newSessionState.sessions.size)
        val createdSession = newSessionState.sessions["fake-uuid-1"]
        assertNotNull(createdSession)
        assertEquals("Test Session", createdSession.name)
        assertEquals("fake-uuid-1", newSessionState.activeSessionId, "Newly created session should be active.")
    }

    @Test
    fun `reducer session_CREATE proactively de-duplicates session names`() {
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val feature = SessionFeature(fakePlatform, scope)
        val existingSession = Session(id = "sid-1", name = "Test Session", ledger = emptyList(), createdAt = 1L)
        val existingSession2 = Session(id = "sid-2", name = "Test Session-2", ledger = emptyList(), createdAt = 2L)
        val initialState = AppState(featureStates = mapOf(feature.name to SessionState(sessions = mapOf("sid-1" to existingSession, "sid-2" to existingSession2))))
        val action = Action("session.CREATE", buildJsonObject { put("name", "Test Session") })

        val newState = feature.reducer(initialState, action)

        val newSessionState = newState.featureStates[feature.name] as SessionState
        assertEquals(3, newSessionState.sessions.size)
        val createdSession = newSessionState.sessions["fake-uuid-1"]
        assertNotNull(createdSession)
        assertEquals("Test Session-3", createdSession.name, "Name should be de-duplicated with the next available suffix.")
    }

    @Test
    fun `reducer session_UPDATE_CONFIG renames a session and handles de-duplication`() {
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val feature = SessionFeature(fakePlatform, scope)
        val session1 = Session(id = "sid-1", name = "Original Name", ledger = emptyList(), createdAt = 1L)
        val session2 = Session(id = "sid-2", name = "Existing Name", ledger = emptyList(), createdAt = 2L)
        val initialState = AppState(featureStates = mapOf(feature.name to SessionState(sessions = mapOf("sid-1" to session1, "sid-2" to session2))))
        val action = Action("session.UPDATE_CONFIG", buildJsonObject {
            put("session", "sid-1")
            put("name", "Existing Name")
        })

        val newState = feature.reducer(initialState, action)

        val newSessionState = newState.featureStates[feature.name] as SessionState
        val updatedSession = newSessionState.sessions["sid-1"]
        assertNotNull(updatedSession)
        assertEquals("Existing Name-2", updatedSession.name)
        assertNull(newSessionState.editingSessionId, "Editing should be cancelled after update.")
    }

    @Test
    fun `reducer resolves session by name for POST action`() {
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val feature = SessionFeature(fakePlatform, scope)
        val session = Session(id = "sid-1", name = "My Session", ledger = emptyList(), createdAt = 1L)
        val initialState = AppState(featureStates = mapOf(feature.name to SessionState(sessions = mapOf("sid-1" to session))))
        val action = Action("session.POST", buildJsonObject {
            put("session", "My Session")
            put("agentId", "user")
            put("message", "Hello")
        })

        val newState = feature.reducer(initialState, action)
        val sessionState = newState.featureStates[feature.name] as SessionState
        assertEquals(1, sessionState.sessions["sid-1"]?.ledger?.size)
    }

    @Test
    fun `reducer agent_publish_AGENT_NAMES_UPDATED updates local cache`() {
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val feature = SessionFeature(fakePlatform, scope)
        val initialState = AppState(featureStates = mapOf(feature.name to SessionState()))
        val nameMap = mapOf("agent-1" to "Agent One", "agent-2" to "Agent Two")
        val action = Action("agent.publish.AGENT_NAMES_UPDATED", buildJsonObject {
            put("names", Json.encodeToJsonElement(nameMap))
        })

        val newState = feature.reducer(initialState, action)

        val newSessionState = newState.featureStates[feature.name] as SessionState
        assertEquals(nameMap, newSessionState.agentNames)
    }

    @Test
    fun `reducer TOGGLE_MESSAGE_COLLAPSED correctly updates persistent UI state`() {
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val feature = SessionFeature(fakePlatform, scope)
        val session = Session(id = "sid-1", name = "My Session", ledger = emptyList(), createdAt = 1L)
        val initialState = AppState(featureStates = mapOf(feature.name to SessionState(sessions = mapOf("sid-1" to session))))
        val action = Action("session.TOGGLE_MESSAGE_COLLAPSED", buildJsonObject {
            put("sessionId", "sid-1")
            put("messageId", "msg-1")
        })

        // First toggle: should set collapsed to true
        val stateAfterCollapse = feature.reducer(initialState, action)
        val sessionState1 = stateAfterCollapse.featureStates[feature.name] as SessionState
        val msgUiState1 = sessionState1.sessions["sid-1"]?.messageUiState?.get("msg-1")
        assertNotNull(msgUiState1)
        assertTrue(msgUiState1.isCollapsed)
        assertFalse(msgUiState1.isRawView)

        // Second toggle: should set collapsed back to false
        val stateAfterExpand = feature.reducer(stateAfterCollapse, action)
        val sessionState2 = stateAfterExpand.featureStates[feature.name] as SessionState
        val msgUiState2 = sessionState2.sessions["sid-1"]?.messageUiState?.get("msg-1")
        assertNotNull(msgUiState2)
        assertFalse(msgUiState2.isCollapsed)
    }

    @Test
    fun `reducer UPDATE_MESSAGE correctly modifies ledger entry`() {
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val feature = SessionFeature(fakePlatform, scope)
        val message = LedgerEntry("msg-1", 1L, "user", "old", emptyList())
        val session = Session(id = "sid-1", name = "My Session", ledger = listOf(message), createdAt = 1L)
        val initialState = AppState(featureStates = mapOf(feature.name to SessionState(sessions = mapOf("sid-1" to session))))
        val action = Action("session.UPDATE_MESSAGE", buildJsonObject {
            put("session", "sid-1")
            put("messageId", "msg-1")
            put("newContent", "new")
        })

        val newState = feature.reducer(initialState, action)

        val sessionState = newState.featureStates[feature.name] as SessionState
        val updatedMessage = sessionState.sessions["sid-1"]?.ledger?.first()
        assertNotNull(updatedMessage)
        assertEquals("new", updatedMessage.rawContent, "Raw content should be updated.")
        assertIs<ContentBlock.Text>(updatedMessage.content.first())
        assertEquals("new", (updatedMessage.content.first() as ContentBlock.Text).text, "Parsed content should also be updated.")
        assertNull(sessionState.editingMessageId, "Editing state should be cleared after update.")
    }

    @Test
    fun `reducer session_DELETE clears activeSessionId and picks most recent if available`() {
        val fakePlatform = FakePlatformDependencies(testAppVersion)
        val feature = SessionFeature(fakePlatform, scope)
        val session1 = Session(id = "sid-1", name = "s1", ledger = emptyList(), createdAt = 1L)
        val session2 = Session(id = "sid-2", name = "s2", ledger = emptyList(), createdAt = 2L)
        val session3 = Session(id = "sid-3", name = "s3", ledger = emptyList(), createdAt = 3L)
        val initialState = AppState(featureStates = mapOf(feature.name to SessionState(
            sessions = mapOf("sid-1" to session1, "sid-2" to session2, "sid-3" to session3),
            activeSessionId = "sid-3"
        )))
        val action = Action("session.DELETE", buildJsonObject { put("session", "sid-3") })

        val newState = feature.reducer(initialState, action)
        val sessionState = newState.featureStates[feature.name] as SessionState

        assertEquals(2, sessionState.sessions.size)
        assertEquals("sid-2", sessionState.activeSessionId, "Active session should become the next most recent one.")
    }
}