package app.auf.feature.session

import app.auf.core.Action
import app.auf.core.Identity
import app.auf.core.PrivateDataEnvelope
import app.auf.core.generated.ActionRegistry
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Tier 2 Core Tests for SessionFeature.
 *
 * Mandate (P-TEST-001, T2): To test the feature's reducer and onAction handlers working
 * together within a realistic TestEnvironment that includes the real Store.
 *
 * Phase 4 updates: Session creation is now two-phase (SESSION_CREATE → pending →
 * RETURN_REGISTER_IDENTITY → actual session). Tests updated accordingly.
 * Session data model uses Identity instead of id/name fields.
 * File paths use uuid/localHandle.json folder structure.
 */
class SessionFeatureT2CoreTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val platform = FakePlatformDependencies("test")
    private val sessionFeature = SessionFeature(platform, scope)
    private val fileSystemFeature = FileSystemFeature(platform)

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** Helper to build a test Session with a properly constructed Identity. */
    private fun testSession(
        localHandle: String,
        name: String,
        ledger: List<LedgerEntry> = emptyList(),
        createdAt: Long = 1L,
        uuid: String = "00000000-0000-4000-a000-${localHandle.hashCode().toUInt().toString(16).padStart(12, '0')}",
        isHidden: Boolean = false,
        isPrivate: Boolean = false,
        orderIndex: Int = 0,
        messageUiState: Map<String, MessageUiState> = emptyMap()
    ): Session {
        val identity = Identity(
            uuid = uuid,
            localHandle = localHandle,
            handle = "session.$localHandle",
            name = name,
            parentHandle = "session"
        )
        return Session(
            identity = identity,
            ledger = ledger,
            createdAt = createdAt,
            isHidden = isHidden,
            isPrivate = isPrivate,
            orderIndex = orderIndex,
            messageUiState = messageUiState
        )
    }

    @Test
    fun `when a session is created it should go through two-phase flow and be added to state`() = runTest {
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withFeature(fileSystemFeature)
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("ui", Action(ActionRegistry.Names.SESSION_CREATE))
            testScheduler.advanceUntilIdle()

            val sessionState = harness.store.state.value.featureStates["session"] as? SessionState
            assertNotNull(sessionState)

            // Phase 4: Session creation is two-phase. After SESSION_CREATE, a pending is stashed.
            // CoreFeature processes REGISTER_IDENTITY and sends RETURN_REGISTER_IDENTITY.
            // The session should now exist in state (assuming CoreFeature is in the harness via TestEnvironment).
            assertEquals(1, sessionState.sessions.size, "Session should exist after two-phase creation")
            val newSession = sessionState.sessions.values.first()
            assertEquals("New Session", newSession.identity.name)
            assertEquals(newSession.identity.localHandle, sessionState.activeSessionLocalHandle)

            // Pending should be cleared
            assertTrue(sessionState.pendingCreations.isEmpty(), "Pending creations should be cleared after approval")

            // Verify persistence and broadcast
            assertNotNull(harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_SYSTEM_WRITE })
            assertNotNull(harness.processedActions.find { it.name == ActionRegistry.Names.SESSION_SESSION_NAMES_UPDATED })
        }
    }

    @Test
    fun `when a session is deleted it should be removed from state and its folder should be deleted`() = runTest {
        val session = testSession("sid-1", "To Delete")
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withFeature(fileSystemFeature)
            .withInitialState("session", SessionState(sessions = mapOf(session.identity.localHandle to session)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("ui", Action(ActionRegistry.Names.SESSION_DELETE, buildJsonObject { put("session", "sid-1") }))
            testScheduler.advanceUntilIdle()

            val sessionState = harness.store.state.value.featureStates["session"] as? SessionState
            assertNotNull(sessionState)
            assertTrue(sessionState.sessions.isEmpty())

            // Phase 4: Delete now removes the UUID folder, not a flat .json file
            val deleteAction = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_SYSTEM_DELETE }
            assertNotNull(deleteAction)
            assertEquals(session.identity.uuid, deleteAction.payload?.get("subpath")?.jsonPrimitive?.content,
                "Should delete the UUID-named folder")

            val publishAction = harness.processedActions.find { it.name == ActionRegistry.Names.SESSION_SESSION_NAMES_UPDATED }
            assertNotNull(publishAction)
            assertEquals("{}", publishAction.payload?.get("names").toString())

            assertNotNull(harness.processedActions.find { it.name == ActionRegistry.Names.SESSION_SESSION_DELETED })
        }
    }

    // [REMOVED] Test `reducer correctly merges user and agent identity broadcasts` was deleted.
    // The identityNames field was removed from SessionState and AGENT_AGENT_NAMES_UPDATED
    // was replaced by CORE_IDENTITY_REGISTRY_UPDATED. Identity resolution now goes through
    // the centralized identity registry in CoreFeature.

    @Test
    fun `CORE_IDENTITIES_UPDATED caches activeUserId on SessionState`() = runTest {
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            val userIdentities = listOf(Identity(uuid = "user-1", localHandle = "alice", handle = "user-1", name = "Alice"))
            harness.store.dispatch("core", Action(ActionRegistry.Names.CORE_IDENTITIES_UPDATED, buildJsonObject {
                put("identities", Json.encodeToJsonElement<List<Identity>>(userIdentities))
                put("activeId", "user-1")
            }))
            testScheduler.advanceUntilIdle()

            val sessionState = harness.store.state.value.featureStates["session"] as SessionState
            assertEquals("user-1", sessionState.activeUserId, "activeUserId should be cached from broadcast")
        }
    }

    @Test
    fun `when a transient message is posted it should not be included in the persisted file`() = runTest {
        val session = testSession("sid-1", "Test")
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withFeature(fileSystemFeature)
            .withInitialState("session", SessionState(sessions = mapOf(session.identity.localHandle to session)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            val persistentAction = Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
                put("session", "sid-1"); put("senderId", "user"); put("message", "persistent")
            })
            val transientAction = Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
                put("session", "sid-1"); put("senderId", "agent"); put("metadata", buildJsonObject { put("is_transient", true) })
            })

            harness.store.dispatch("ui", persistentAction)
            harness.store.dispatch("ui", transientAction)
            testScheduler.advanceUntilIdle()

            // Filter to session ledger writes only. input.json is also written on user posts
            // (draft/history persistence) — those are correct but irrelevant to this assertion.
            val writeActions = harness.processedActions.filter {
                it.name == ActionRegistry.Names.FILESYSTEM_SYSTEM_WRITE &&
                        it.payload?.get("subpath")?.jsonPrimitive?.content?.endsWith("/input.json") == false
            }
            assertEquals(2, writeActions.size)
            val finalWriteContent = writeActions.last().payload?.get("content")?.jsonPrimitive?.content
            assertNotNull(finalWriteContent)
            val persistedSession = json.decodeFromString<Session>(finalWriteContent)
            assertEquals(1, persistedSession.ledger.size)
            assertEquals("persistent", persistedSession.ledger.first().rawContent)

            val publishedEvents = harness.processedActions.filter { it.name == ActionRegistry.Names.SESSION_MESSAGE_POSTED }
            assertEquals(2, publishedEvents.size, "Should publish an event for every POST action, transient or not.")
        }
    }

    @Test
    fun `when posting with a valid afterMessageId it should insert the message at the correct position`() = runTest {
        val entry1 = LedgerEntry("msg-1", 1L, "user", "First")
        val entry2 = LedgerEntry("msg-2", 2L, "user", "Third")
        val session = testSession("sid-1", "Test", listOf(entry1, entry2))
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withFeature(fileSystemFeature)
            .withInitialState("session", SessionState(sessions = mapOf(session.identity.localHandle to session)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            val insertAction = Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
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
        val session = testSession("sid-1", "Test", listOf(entry1))
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withFeature(fileSystemFeature)
            .withInitialState("session", SessionState(sessions = mapOf(session.identity.localHandle to session)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            val appendAction = Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
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
        val session = testSession("sid-1", "Test", listOf(entry1))
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withFeature(fileSystemFeature)
            .withInitialState("session", SessionState(sessions = mapOf(session.identity.localHandle to session)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            val appendAction = Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
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
        val session = testSession("sid-1", "Test", listOf(entry))
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withFeature(fileSystemFeature)
            .withInitialState("session", SessionState(sessions = mapOf(session.identity.localHandle to session)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("ui", Action(ActionRegistry.Names.SESSION_DELETE_MESSAGE, buildJsonObject {
                put("session", "sid-1"); put("messageId", "msg-1")
            }))
            testScheduler.advanceUntilIdle()

            val deletedEvent = harness.processedActions.find { it.name == ActionRegistry.Names.SESSION_MESSAGE_DELETED }
            assertNotNull(deletedEvent)
            assertEquals("sid-1", deletedEvent.payload?.get("sessionId")?.jsonPrimitive?.content)
            assertEquals("msg-1", deletedEvent.payload?.get("messageId")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `when the system starts it should dispatch LIST to begin loading process`() = runTest {
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.INITIALIZING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("system", Action(ActionRegistry.Names.SYSTEM_STARTING))
            testScheduler.advanceUntilIdle()

            val listAction = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_LIST && it.originator == "session" }
            assertNotNull(listAction, "Should have dispatched filesystem.LIST")
        }
    }

    @Test
    fun `when it receives a file list with UUID folders it should list their contents`() = runTest {
        val harness = TestEnvironment.create().withFeature(sessionFeature).build(platform = platform)

        harness.runAndLogOnFailure {
            // Phase 4: File structure is now uuid-folders containing .json files
            val fileList = listOf(
                FileEntry("/app/session/28c273db-aaef-4791-b184-11fea21db4cf", true),
                FileEntry("/app/session/9a1b2c3d-4e5f-6789-abcd-ef0123456789", true),
                FileEntry("/app/session/notes.txt", false)
            )
            val payload = buildJsonObject { put("listing", Json.encodeToJsonElement<List<FileEntry>>(fileList)) }

            // Use the targeted action path (Phase 3 migration)
            harness.store.dispatch("filesystem", Action(
                ActionRegistry.Names.FILESYSTEM_RETURN_LIST,
                payload,
                targetRecipient = "session"
            ))
            testScheduler.advanceUntilIdle()

            // Should dispatch LIST for each UUID folder to list its contents
            val listActions = harness.processedActions.filter {
                it.name == ActionRegistry.Names.FILESYSTEM_LIST && it.originator == "session"
            }
            assertEquals(2, listActions.size, "Should list contents of each UUID folder")
        }
    }

    @Test
    fun `when it receives valid session content it should load the session into state`() = runTest {
        val harness = TestEnvironment.create().withFeature(sessionFeature).build(platform = platform)

        harness.runAndLogOnFailure {
            // Phase 4: Session JSON now uses identity object instead of id/name fields
            val testIdentity = Identity(
                uuid = "00000000-0000-4000-a000-00000000000a",
                localHandle = "loaded-1",
                handle = "session.loaded-1",
                name = "Loaded Session",
                parentHandle = "session"
            )
            val testSession = Session(
                identity = testIdentity,
                ledger = emptyList(),
                createdAt = 1L
            )
            val sessionJsonContent = json.encodeToString(testSession)

            val payload = buildJsonObject {
                put("subpath", "00000000-0000-4000-a000-00000000000a/loaded-1.json")
                put("content", sessionJsonContent)
            }

            harness.store.dispatch("filesystem", Action(
                ActionRegistry.Names.FILESYSTEM_RETURN_READ,
                payload,
                targetRecipient = "session"
            ))
            testScheduler.advanceUntilIdle()

            val finalState = harness.store.state.value.featureStates["session"] as? SessionState
            assertNotNull(finalState)
            assertTrue(finalState.sessions.containsKey("loaded-1"), "Session map should contain the new session keyed by localHandle.")
            assertEquals("Loaded Session", finalState.sessions["loaded-1"]?.identity?.name)
        }
    }

    @Test
    fun `when it receives corrupted session content it should log an error and not load`() = runTest {
        val harness = TestEnvironment.create().withFeature(sessionFeature).build(platform = platform)

        harness.runAndLogOnFailure {
            val corruptedJsonContent = """{"identity":{"uuid":"bad-1","localHandle":"bad","handle":"session.bad","name":"Bad",}"""
            val payload = buildJsonObject {
                put("subpath", "bad-uuid/bad.json")
                put("content", corruptedJsonContent)
            }

            harness.store.dispatch("filesystem", Action(
                ActionRegistry.Names.FILESYSTEM_RETURN_READ,
                payload,
                targetRecipient = "session"
            ))
            testScheduler.advanceUntilIdle()

            val loadedAction = harness.processedActions.find { it.name == ActionRegistry.Names.SESSION_LOADED }
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
            harness.store.dispatch("ui", Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
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

    @Test
    fun `persistSession writes to uuid-slash-localHandle-dot-json path`() = runTest {
        val session = testSession("my-chat", "My Chat")
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withFeature(fileSystemFeature)
            .withInitialState("session", SessionState(sessions = mapOf(session.identity.localHandle to session)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // Post a message to trigger persistence
            harness.store.dispatch("ui", Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
                put("session", "my-chat"); put("senderId", "user"); put("message", "hello")
            }))
            testScheduler.advanceUntilIdle()

            val writeAction = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_SYSTEM_WRITE }
            assertNotNull(writeAction)
            val subpath = writeAction.payload?.get("subpath")?.jsonPrimitive?.content
            assertNotNull(subpath)
            assertTrue(subpath.endsWith("/my-chat.json"), "File path should end with /localHandle.json, got: $subpath")
            assertTrue(subpath.startsWith(session.identity.uuid!!), "File path should start with UUID, got: $subpath")
        }
    }

    @Test
    fun `resolveSessionId resolves by localHandle, full handle, and display name`() = runTest {
        val session = testSession("my-chat", "My Chat Room")
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withFeature(fileSystemFeature)
            .withInitialState("session", SessionState(sessions = mapOf(session.identity.localHandle to session)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // Resolve by localHandle
            harness.store.dispatch("ui", Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
                put("session", "my-chat"); put("senderId", "user"); put("message", "by localHandle")
            }))
            testScheduler.advanceUntilIdle()
            var postAction = harness.processedActions.findLast { it.name == ActionRegistry.Names.SESSION_MESSAGE_POSTED }
            assertNotNull(postAction, "Should resolve by localHandle")

            // Resolve by display name
            harness.store.dispatch("ui", Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
                put("session", "My Chat Room"); put("senderId", "user"); put("message", "by name")
            }))
            testScheduler.advanceUntilIdle()
            postAction = harness.processedActions.findLast { it.name == ActionRegistry.Names.SESSION_MESSAGE_POSTED }
            assertNotNull(postAction, "Should resolve by display name")
        }
    }
}