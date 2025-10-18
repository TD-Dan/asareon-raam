package app.auf.feature.session

import app.auf.core.Action
import app.auf.feature.core.CoreState
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.core.AppLifecycle
import app.auf.feature.filesystem.FileSystemFeature
import app.auf.test.TestEnvironment
import app.auf.util.FileEntry
import app.auf.util.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Tier 2 Core Integration Test for SessionFeature.
 *
 * Mandate (P-TEST-001, T2): To test the SessionFeature's complete internal logic
 * against its foundational contract with the system (the Store).
 *
 * This file replaces the previous Reducer/OnAction split, per P-TEST-002.
 */
class SessionFeatureCoreTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    // THE FIX: Create a single, shared platform instance for all components in a test.
    private val platform = FakePlatformDependencies("test")
    private val sessionFeature = SessionFeature(platform, scope)
    private val fileSystemFeature = FileSystemFeature(platform)


    @Test
    fun `create() adds new session, sets it active, and dispatches SYSTEM_WRITE and publish`() = runTest {
        // ARRANGE
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withFeature(fileSystemFeature)
            .build(platform = platform)

        // ACT
        harness.store.dispatch("ui", Action("session.CREATE"))

        // ASSERT (State)
        val sessionState = harness.store.state.value.featureStates["session"] as? SessionState
        assertNotNull(sessionState)
        assertEquals(1, sessionState.sessions.size)
        val newSession = sessionState.sessions.values.first()
        assertEquals("New Session", newSession.name)
        assertEquals(newSession.id, sessionState.activeSessionId)

        // ASSERT (Side Effects)
        assertNotNull(harness.processedActions.find { it.name == "filesystem.SYSTEM_WRITE" })
        assertNotNull(harness.processedActions.find { it.name == "session.publish.SESSION_NAMES_UPDATED" })
    }

    @Test
    fun `delete() removes session, dispatches SYSTEM_DELETE, and publishes updated names`() = runTest {
        // ARRANGE
        val session = Session("sid-1", "To Delete", emptyList(), 1L)
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withFeature(fileSystemFeature)
            .withInitialState("session", SessionState(sessions = mapOf(session.id to session)))
            .build(platform = platform)

        // ACT
        harness.store.dispatch("ui", Action("session.DELETE", buildJsonObject { put("session", "sid-1") }))

        // ASSERT (State)
        val sessionState = harness.store.state.value.featureStates["session"] as? SessionState
        assertNotNull(sessionState)
        assertTrue(sessionState.sessions.isEmpty())

        // ASSERT (Side Effects)
        val deleteAction = harness.processedActions.find { it.name == "filesystem.SYSTEM_DELETE" }
        assertNotNull(deleteAction)
        assertEquals("sid-1.json", deleteAction.payload?.get("subpath")?.jsonPrimitive?.content)

        val publishAction = harness.processedActions.find { it.name == "session.publish.SESSION_NAMES_UPDATED" }
        assertNotNull(publishAction)
        assertEquals("{}", publishAction.payload?.get("names").toString())
    }

    @Test
    fun `post() with transient metadata does not persist the transient entry`() = runTest {
        // ARRANGE
        val session = Session("sid-1", "Test", emptyList(), 1L)
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withFeature(fileSystemFeature)
            .withInitialState("session", SessionState(sessions = mapOf(session.id to session)))
            .build(platform = platform)
        val persistentAction = Action("session.POST", buildJsonObject {
            put("session", "sid-1"); put("senderId", "user"); put("message", "persistent")
        })
        val transientAction = Action("session.POST", buildJsonObject {
            put("session", "sid-1"); put("senderId", "agent"); put("metadata", buildJsonObject { put("is_transient", true) })
        })

        // ACT
        harness.store.dispatch("ui", persistentAction) // Triggers first write
        harness.store.dispatch("ui", transientAction)  // Triggers second write

        // ASSERT
        val writeActions = harness.processedActions.filter { it.name == "filesystem.SYSTEM_WRITE" }
        assertEquals(2, writeActions.size)
        val finalWriteContent = writeActions.last().payload?.get("content")?.jsonPrimitive?.content
        assertNotNull(finalWriteContent)
        val persistedSession = Json.decodeFromString<Session>(finalWriteContent)
        assertEquals(1, persistedSession.ledger.size)
        assertEquals("persistent", persistedSession.ledger.first().rawContent)
    }

    @Test
    fun `onSystemStarting() dispatches SYSTEM_LIST to begin loading process`() = runTest {
        // ARRANGE
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            // THE FIX: Explicitly set the lifecycle state to INITIALIZING for this test.
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.INITIALIZING))
            .build(platform = platform)

        // ACT
        harness.store.dispatch("system", Action("system.STARTING"))

        // ASSERT
        val listAction = harness.processedActions.find { it.name == "filesystem.SYSTEM_LIST" && it.originator == "session" }
        assertNotNull(listAction, "Should have dispatched filesystem.SYSTEM_LIST")
    }

    @Test
    fun `onPrivateData() with file list dispatches SYSTEM_READ for each json file`() = runTest {
        // ARRANGE
        val harness = TestEnvironment.create().withFeature(sessionFeature).build(platform = platform)
        val fileList = listOf(
            FileEntry("/app/session/session-1.json", false),
            FileEntry("/app/session/session-2.json", false),
            FileEntry("/app/session/notes.txt", false) // Should be ignored
        )

        // ACT
        sessionFeature.onPrivateData(fileList, harness.store)

        // ASSERT
        val readActions = harness.processedActions.filter { it.name == "filesystem.SYSTEM_READ" }
        assertEquals(2, readActions.size)
        assertEquals("session-1.json", readActions[0].payload?.get("subpath")?.jsonPrimitive?.content)
        assertEquals("session-2.json", readActions[1].payload?.get("subpath")?.jsonPrimitive?.content)
    }

    @Test
    fun `onPrivateData() with valid session content loads session into state`() = runTest {
        // ARRANGE
        val harness = TestEnvironment.create().withFeature(sessionFeature).build(platform = platform)
        val sessionJsonContent = """{"id":"loaded-1","name":"Loaded Session","ledger":[],"createdAt":1}"""
        val fileContentPayload = buildJsonObject {
            put("subpath", "loaded-1.json")
            put("content", sessionJsonContent)
        }

        // ACT
        sessionFeature.onPrivateData(fileContentPayload, harness.store)

        // ASSERT
        // THE FIX: Assert on the resulting state change, not the internal action dispatch.
        val finalState = harness.store.state.value.featureStates["session"] as? SessionState
        assertNotNull(finalState)
        assertTrue(finalState.sessions.containsKey("loaded-1"), "Session map should contain the new session.")
        assertEquals("Loaded Session", finalState.sessions["loaded-1"]?.name)
    }

    @Test
    fun `onPrivateData() with corrupted session content logs an error and does not load`() = runTest {
        // ARRANGE
        val harness = TestEnvironment.create().withFeature(sessionFeature).build(platform = platform)
        val corruptedJsonContent = """{"id":"bad-1","name":"Bad Session",}""" // Invalid trailing comma
        val fileContentPayload = buildJsonObject {
            put("subpath", "bad-1.json") // Add subpath for better error logging
            put("content", corruptedJsonContent)
        }

        // ACT
        sessionFeature.onPrivateData(fileContentPayload, harness.store)

        // ASSERT
        val loadedAction = harness.processedActions.find { it.name == "session.internal.LOADED" }
        assertNull(loadedAction, "LOADED should not be dispatched for corrupted content.")
        val log = harness.platform.capturedLogs.find { it.level == LogLevel.ERROR }
        assertNotNull(log)
        assertTrue(log.message.contains("Failed to parse session file"))
    }
}