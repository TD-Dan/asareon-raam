package app.auf.feature.session

import app.auf.core.Action
import app.auf.core.Identity
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
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Tier 2 Core Tests for SessionFeature.
 *
 * Mandate (P-TEST-001, T2): To test the feature's reducer and onAction handlers working
 * together within a realistic TestEnvironment that includes the real Store.
 *
 * Modernization (P-TEST-005): All verifications wrapped in runAndLogOnFailure.
 */
class SessionFeatureT2CoreTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val platform = FakePlatformDependencies("test")
    private val sessionFeature = SessionFeature(platform, scope)
    private val fileSystemFeature = FileSystemFeature(platform)

    @Test
    fun `when a session is created it should be added to the state, set active, and persisted`() = runTest {
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withFeature(fileSystemFeature)
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("ui", Action(ActionNames.SESSION_CREATE))
            testScheduler.advanceUntilIdle()

            val sessionState = harness.store.state.value.featureStates["session"] as? SessionState
            assertNotNull(sessionState)
            assertEquals(1, sessionState.sessions.size)
            val newSession = sessionState.sessions.values.first()
            assertEquals("New Session", newSession.name)
            assertEquals(newSession.id, sessionState.activeSessionId)

            assertNotNull(harness.processedActions.find { it.name == ActionNames.FILESYSTEM_SYSTEM_WRITE })
            assertNotNull(harness.processedActions.find { it.name == ActionNames.SESSION_PUBLISH_SESSION_NAMES_UPDATED })
        }
    }

    @Test
    fun `when a session is deleted it should be removed from state and its file should be deleted`() = runTest {
        val session = Session("sid-1", "To Delete", emptyList(), 1L)
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withFeature(fileSystemFeature)
            .withInitialState("session", SessionState(sessions = mapOf(session.id to session)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("ui", Action(ActionNames.SESSION_DELETE, buildJsonObject { put("session", "sid-1") }))
            testScheduler.advanceUntilIdle()

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
    }

    @Test
    fun `reducer correctly merges user and agent identity broadcasts`() = runTest {
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // ACT 1: Broadcast user identities from CoreFeature
            val userIdentities = listOf(Identity("user-1", "User Alpha"))
            val coreBroadcast = Action(ActionNames.CORE_PUBLISH_IDENTITIES_UPDATED, buildJsonObject {
                put("identities", Json.encodeToJsonElement(userIdentities))
            })
            harness.store.dispatch("core", coreBroadcast)
            testScheduler.advanceUntilIdle()

            // ASSERT 1
            val stateAfterCore = harness.store.state.value.featureStates["session"] as SessionState
            // Size is 2 because "system" is default + "user-1"
            assertEquals(2, stateAfterCore.identityNames.size)
            assertEquals("User Alpha", stateAfterCore.identityNames["user-1"])

            // ACT 2: Broadcast agent identities from AgentRuntimeFeature
            val agentNames = mapOf("agent-1" to "Agent Beta")
            val agentBroadcast = Action(ActionNames.AGENT_PUBLISH_AGENT_NAMES_UPDATED, buildJsonObject {
                put("names", Json.encodeToJsonElement(agentNames))
            })
            harness.store.dispatch("agent", agentBroadcast)
            testScheduler.advanceUntilIdle()

            // ASSERT 2
            val finalState = harness.store.state.value.featureStates["session"] as SessionState
            // Size is 3: system, user-1, agent-1
            assertEquals(3, finalState.identityNames.size, "Should contain system, user, and agent identities.")
            assertEquals("User Alpha", finalState.identityNames["user-1"])
            assertEquals("Agent Beta", finalState.identityNames["agent-1"])
        }
    }

    @Test
    fun `when a transient message is posted it should not be included in the persisted file`() = runTest {
        val session = Session("sid-1", "Test", emptyList(), 1L)
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withFeature(fileSystemFeature)
            .withInitialState("session", SessionState(sessions = mapOf(session.id to session)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            val persistentAction = Action(ActionNames.SESSION_POST, buildJsonObject {
                put("session", "sid-1"); put("senderId", "user"); put("message", "persistent")
            })
            val transientAction = Action(ActionNames.SESSION_POST, buildJsonObject {
                put("session", "sid-1"); put("senderId", "agent"); put("metadata", buildJsonObject { put("is_transient", true) })
            })

            harness.store.dispatch("ui", persistentAction)
            harness.store.dispatch("ui", transientAction)
            testScheduler.advanceUntilIdle()

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
    }

    @Test
    fun `when posting with a valid afterMessageId it should insert the message at the correct position`() = runTest {
        val entry1 = LedgerEntry("msg-1", 1L, "user", "First")
        val entry2 = LedgerEntry("msg-2", 2L, "user", "Third")
        val session = Session("sid-1", "Test", listOf(entry1, entry2), 1L)
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withFeature(fileSystemFeature)
            .withInitialState("session", SessionState(sessions = mapOf(session.id to session)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            val insertAction = Action(ActionNames.SESSION_POST, buildJsonObject {
                put("session", "sid-1")
                put("senderId", "agent")
                put("message", "Second")
                put("messageId", "msg-inserted")
                put("afterMessageId", "msg-1")
            })
            harness.store.dispatch("agent", insertAction)
            testScheduler.advanceUntilIdle()

            val finalState = harness.store.state.value.featureStates["session"] as SessionState
            val finalLedger = finalState.sessions["sid-1"]?.ledger
            assertNotNull(finalLedger)
            assertEquals(3, finalLedger.size)
            assertEquals("msg-1", finalLedger[0].id)
            assertEquals("msg-inserted", finalLedger[1].id)
            assertEquals("msg-2", finalLedger[2].id)
        }
    }

    @Test
    fun `when posting with a non-existent afterMessageId it should append the message to the end`() = runTest {
        val entry1 = LedgerEntry("msg-1", 1L, "user", "First")
        val session = Session("sid-1", "Test", listOf(entry1), 1L)
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withFeature(fileSystemFeature)
            .withInitialState("session", SessionState(sessions = mapOf(session.id to session)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            val appendAction = Action(ActionNames.SESSION_POST, buildJsonObject {
                put("session", "sid-1")
                put("senderId", "agent")
                put("message", "Appended")
                put("messageId", "msg-appended")
                put("afterMessageId", "msg-id-that-does-not-exist")
            })
            harness.store.dispatch("agent", appendAction)
            testScheduler.advanceUntilIdle()

            val finalState = harness.store.state.value.featureStates["session"] as SessionState
            val finalLedger = finalState.sessions["sid-1"]?.ledger
            assertNotNull(finalLedger)
            assertEquals(2, finalLedger.size)
            assertEquals("msg-appended", finalLedger.last().id)
        }
    }

    @Test
    fun `when posting without an afterMessageId it should append the message to the end`() = runTest {
        val entry1 = LedgerEntry("msg-1", 1L, "user", "First")
        val session = Session("sid-1", "Test", listOf(entry1), 1L)
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withFeature(fileSystemFeature)
            .withInitialState("session", SessionState(sessions = mapOf(session.id to session)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            val appendAction = Action(ActionNames.SESSION_POST, buildJsonObject {
                put("session", "sid-1")
                put("senderId", "agent")
                put("message", "Appended")
                put("messageId", "msg-appended")
            })
            harness.store.dispatch("agent", appendAction)
            testScheduler.advanceUntilIdle()

            val finalState = harness.store.state.value.featureStates["session"] as SessionState
            val finalLedger = finalState.sessions["sid-1"]?.ledger
            assertNotNull(finalLedger)
            assertEquals(2, finalLedger.size)
            assertEquals("msg-appended", finalLedger.last().id)
        }
    }

    @Test
    fun `when a message is deleted it should publish a MESSAGE_DELETED event`() = runTest {
        val entry = LedgerEntry("msg-1", 1L, "user", "Hello")
        val session = Session("sid-1", "Test", listOf(entry), 1L)
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withFeature(fileSystemFeature)
            .withInitialState("session", SessionState(sessions = mapOf(session.id to session)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("ui", Action(ActionNames.SESSION_DELETE_MESSAGE, buildJsonObject {
                put("session", "sid-1"); put("messageId", "msg-1")
            }))
            testScheduler.advanceUntilIdle()

            val deletedEvent = harness.processedActions.find { it.name == ActionNames.SESSION_PUBLISH_MESSAGE_DELETED }
            assertNotNull(deletedEvent)
            assertEquals("sid-1", deletedEvent.payload?.get("sessionId")?.jsonPrimitive?.content)
            assertEquals("msg-1", deletedEvent.payload?.get("messageId")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `when the system starts it should dispatch SYSTEM_LIST to begin loading process`() = runTest {
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.INITIALIZING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("system", Action(ActionNames.SYSTEM_PUBLISH_STARTING))
            testScheduler.advanceUntilIdle()

            val listAction = harness.processedActions.find { it.name == ActionNames.FILESYSTEM_SYSTEM_LIST && it.originator == "session" }
            assertNotNull(listAction, "Should have dispatched filesystem.SYSTEM_LIST")
        }
    }

    @Test
    fun `when it receives a file list it should dispatch SYSTEM_READ for each json file`() = runTest {
        val harness = TestEnvironment.create().withFeature(sessionFeature).build(platform = platform)

        harness.runAndLogOnFailure {
            val fileList = listOf(
                FileEntry("/app/session/session-1.json", false),
                FileEntry("/app.auf/session/session-2.json", false),
                FileEntry("/app/session/notes.txt", false)
            )
            val payload = buildJsonObject { put("listing", Json.encodeToJsonElement<List<FileEntry>>(fileList)) }
            val envelope = PrivateDataEnvelope(ActionNames.Envelopes.FILESYSTEM_RESPONSE_LIST, payload)

            // FIX: Use store.deliverPrivateData instead of calling onPrivateData directly.
            // This ensures that the Store's event loop is triggered to process deferred actions.
            harness.store.deliverPrivateData("filesystem", "session", envelope)
            testScheduler.advanceUntilIdle()

            val readActions = harness.processedActions.filter { it.name == ActionNames.FILESYSTEM_SYSTEM_READ }
            assertEquals(2, readActions.size)
            assertEquals("session-1.json", readActions[0].payload?.get("subpath")?.jsonPrimitive?.content)
            assertEquals("session-2.json", readActions[1].payload?.get("subpath")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `when it receives valid session content it should load the session into state`() = runTest {
        val harness = TestEnvironment.create().withFeature(sessionFeature).build(platform = platform)

        harness.runAndLogOnFailure {
            val sessionJsonContent = """{"id":"loaded-1","name":"Loaded Session","ledger":[],"createdAt":1}"""
            val payload = buildJsonObject {
                put("subpath", "loaded-1.json")
                put("content", sessionJsonContent)
            }
            val envelope = PrivateDataEnvelope(ActionNames.Envelopes.FILESYSTEM_RESPONSE_READ, payload)

            // FIX: Use store.deliverPrivateData to pump the event loop.
            harness.store.deliverPrivateData("filesystem", "session", envelope)
            testScheduler.advanceUntilIdle()

            val finalState = harness.store.state.value.featureStates["session"] as? SessionState
            assertNotNull(finalState)
            assertTrue(finalState.sessions.containsKey("loaded-1"), "Session map should contain the new session.")
            assertEquals("Loaded Session", finalState.sessions["loaded-1"]?.name)
        }
    }

    @Test
    fun `when it receives corrupted session content it should log an error and not load`() = runTest {
        val harness = TestEnvironment.create().withFeature(sessionFeature).build(platform = platform)

        harness.runAndLogOnFailure {
            val corruptedJsonContent = """{"id":"bad-1","name":"Bad Session",}"""
            val payload = buildJsonObject {
                put("subpath", "bad-1.json")
                put("content", corruptedJsonContent)
            }
            val envelope = PrivateDataEnvelope(ActionNames.Envelopes.FILESYSTEM_RESPONSE_READ, payload)

            // FIX: Use store.deliverPrivateData for consistency, though expecting no dispatch.
            harness.store.deliverPrivateData("filesystem", "session", envelope)
            testScheduler.advanceUntilIdle()

            val loadedAction = harness.processedActions.find { it.name == ActionNames.SESSION_INTERNAL_LOADED }
            assertNull(loadedAction, "LOADED should not be dispatched for corrupted content.")
            val log = harness.platform.capturedLogs.find { it.level == LogLevel.ERROR }
            assertNotNull(log)
            assertTrue(log.message.contains("Failed to parse session file"))
        }
    }

    @Test
    fun `when posting to an unknown session it should log an error`() = runTest {
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("ui", Action(ActionNames.SESSION_POST, buildJsonObject {
                put("session", "unknown-session-id")
                put("senderId", "user")
                put("message", "Test")
            }))
            testScheduler.advanceUntilIdle()

            val log = harness.platform.capturedLogs.find { it.level == LogLevel.ERROR }
            assertNotNull(log)
            assertTrue(log.message.contains("Could not resolve session with identifier 'unknown-session-id'"))
        }
    }
}