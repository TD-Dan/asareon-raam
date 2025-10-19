package app.auf.feature.session

import app.auf.core.Action
import app.auf.core.PrivateDataEnvelope
import app.auf.core.generated.ActionNames
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
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Tier 2 Unit Tests for SessionFeature.
 * These tests focus on features interaction with the Core
 */
class SessionFeatureT2CoreTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val platform = FakePlatformDependencies("test")
    private val sessionFeature = SessionFeature(platform, scope)
    private val fileSystemFeature = FileSystemFeature(platform)


    @Test
    fun `create() adds new session, sets it active, and dispatches SYSTEM_WRITE and publish`() = runTest {
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withFeature(fileSystemFeature)
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.store.dispatch("ui", Action(ActionNames.SESSION_CREATE))

        val sessionState = harness.store.state.value.featureStates["session"] as? SessionState
        assertNotNull(sessionState)
        assertEquals(1, sessionState.sessions.size)
        val newSession = sessionState.sessions.values.first()
        assertEquals("New Session", newSession.name)
        assertEquals(newSession.id, sessionState.activeSessionId)

        assertNotNull(harness.processedActions.find { it.name == ActionNames.FILESYSTEM_SYSTEM_WRITE })
        assertNotNull(harness.processedActions.find { it.name == ActionNames.SESSION_PUBLISH_SESSION_NAMES_UPDATED })
    }

    @Test
    fun `delete() removes session, dispatches SYSTEM_DELETE, and publishes updated names`() = runTest {
        val session = Session("sid-1", "To Delete", emptyList(), 1L)
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withFeature(fileSystemFeature)
            .withInitialState("session", SessionState(sessions = mapOf(session.id to session)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.store.dispatch("ui", Action(ActionNames.SESSION_DELETE, buildJsonObject { put("session", "sid-1") }))

        val sessionState = harness.store.state.value.featureStates["session"] as? SessionState
        assertNotNull(sessionState)
        assertTrue(sessionState.sessions.isEmpty())

        val deleteAction = harness.processedActions.find { it.name == ActionNames.FILESYSTEM_SYSTEM_DELETE }
        assertNotNull(deleteAction)
        assertEquals("sid-1.json", deleteAction.payload?.get("subpath")?.jsonPrimitive?.content)

        val publishAction = harness.processedActions.find { it.name == ActionNames.SESSION_PUBLISH_SESSION_NAMES_UPDATED }
        assertNotNull(publishAction)
        assertEquals("{}", publishAction.payload?.get("names").toString())

        assertNotNull(harness.processedActions.find { it.name == ActionNames.SESSION_PUBLISH_SESSION_DELETED })
    }

    @Test
    fun `post() with transient metadata does not persist and publishes event`() = runTest {
        val session = Session("sid-1", "Test", emptyList(), 1L)
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withFeature(fileSystemFeature)
            .withInitialState("session", SessionState(sessions = mapOf(session.id to session)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)
        val persistentAction = Action(ActionNames.SESSION_POST, buildJsonObject {
            put("session", "sid-1"); put("senderId", "user"); put("message", "persistent")
        })
        val transientAction = Action(ActionNames.SESSION_POST, buildJsonObject {
            put("session", "sid-1"); put("senderId", "agent"); put("metadata", buildJsonObject { put("is_transient", true) })
        })

        harness.store.dispatch("ui", persistentAction)
        harness.store.dispatch("ui", transientAction)

        val writeActions = harness.processedActions.filter { it.name == ActionNames.FILESYSTEM_SYSTEM_WRITE }
        assertEquals(2, writeActions.size)
        val finalWriteContent = writeActions.last().payload?.get("content")?.jsonPrimitive?.content
        assertNotNull(finalWriteContent)
        val persistedSession = Json.decodeFromString<Session>(finalWriteContent)
        assertEquals(1, persistedSession.ledger.size)
        assertEquals("persistent", persistedSession.ledger.first().rawContent)

        val publishedEvents = harness.processedActions.filter { it.name == ActionNames.SESSION_PUBLISH_MESSAGE_POSTED }
        assertEquals(2, publishedEvents.size, "Should publish an event for every POST action, transient or not.")
    }

    @Test
    fun `deleteMessage() publishes MESSAGE_DELETED event`() = runTest {
        val entry = LedgerEntry("msg-1", 1L, "user", "Hello")
        val session = Session("sid-1", "Test", listOf(entry), 1L)
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withFeature(fileSystemFeature)
            .withInitialState("session", SessionState(sessions = mapOf(session.id to session)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.store.dispatch("ui", Action(ActionNames.SESSION_DELETE_MESSAGE, buildJsonObject {
            put("session", "sid-1"); put("messageId", "msg-1")
        }))

        val deletedEvent = harness.processedActions.find { it.name == ActionNames.SESSION_PUBLISH_MESSAGE_DELETED }
        assertNotNull(deletedEvent)
        assertEquals("sid-1", deletedEvent.payload?.get("sessionId")?.jsonPrimitive?.content)
        assertEquals("msg-1", deletedEvent.payload?.get("messageId")?.jsonPrimitive?.content)
    }

    @Test
    fun `onSystemStarting() dispatches SYSTEM_LIST to begin loading process`() = runTest {
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.INITIALIZING))
            .build(platform = platform)

        harness.store.dispatch("system", Action(ActionNames.SYSTEM_PUBLISH_STARTING))

        val listAction = harness.processedActions.find { it.name == ActionNames.FILESYSTEM_SYSTEM_LIST && it.originator == "session" }
        assertNotNull(listAction, "Should have dispatched filesystem.SYSTEM_LIST")
    }

    @Test
    fun `onPrivateData() with file list dispatches SYSTEM_READ for each json file`() = runTest {
        val harness = TestEnvironment.create().withFeature(sessionFeature).build(platform = platform)
        val fileList = listOf(
            FileEntry("/app/session/session-1.json", false),
            FileEntry("/app.auf/session/session-2.json", false),
            FileEntry("/app/session/notes.txt", false)
        )
        // THE FIX: Provide explicit type parameter to encodeToJsonElement for the list.
        val payload = buildJsonObject { put("listing", Json.encodeToJsonElement<List<FileEntry>>(fileList)) }
        val envelope = PrivateDataEnvelope("filesystem.response.list", payload)


        sessionFeature.onPrivateData(envelope, harness.store)

        val readActions = harness.processedActions.filter { it.name == ActionNames.FILESYSTEM_SYSTEM_READ }
        assertEquals(2, readActions.size)
        assertEquals("session-1.json", readActions[0].payload?.get("subpath")?.jsonPrimitive?.content)
        assertEquals("session-2.json", readActions[1].payload?.get("subpath")?.jsonPrimitive?.content)
    }

    @Test
    fun `onPrivateData() with valid session content loads session into state`() = runTest {
        val harness = TestEnvironment.create().withFeature(sessionFeature).build(platform = platform)
        val sessionJsonContent = """{"id":"loaded-1","name":"Loaded Session","ledger":[],"createdAt":1}"""
        val payload = buildJsonObject {
            put("subpath", "loaded-1.json")
            put("content", sessionJsonContent)
        }
        val envelope = PrivateDataEnvelope("filesystem.response.read", payload)


        sessionFeature.onPrivateData(envelope, harness.store)

        val finalState = harness.store.state.value.featureStates["session"] as? SessionState
        assertNotNull(finalState)
        assertTrue(finalState.sessions.containsKey("loaded-1"), "Session map should contain the new session.")
        assertEquals("Loaded Session", finalState.sessions["loaded-1"]?.name)
    }

    @Test
    fun `onPrivateData() with corrupted session content logs an error and does not load`() = runTest {
        val harness = TestEnvironment.create().withFeature(sessionFeature).build(platform = platform)
        val corruptedJsonContent = """{"id":"bad-1","name":"Bad Session",}"""
        val payload = buildJsonObject {
            put("subpath", "bad-1.json")
            put("content", corruptedJsonContent)
        }
        val envelope = PrivateDataEnvelope("filesystem.response.read", payload)


        sessionFeature.onPrivateData(envelope, harness.store)

        val loadedAction = harness.processedActions.find { it.name == ActionNames.SESSION_INTERNAL_LOADED }
        assertNull(loadedAction, "LOADED should not be dispatched for corrupted content.")
        val log = harness.platform.capturedLogs.find { it.level == LogLevel.ERROR }
        assertNotNull(log)
        assertTrue(log.message.contains("Failed to parse session file"))
    }
}