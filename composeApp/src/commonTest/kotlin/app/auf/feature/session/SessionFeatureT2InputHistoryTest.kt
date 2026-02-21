package app.auf.feature.session

import app.auf.core.Action
import app.auf.core.Identity
import app.auf.core.generated.ActionRegistry
import app.auf.feature.core.CoreState
import app.auf.feature.filesystem.FileSystemFeature
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.core.AppLifecycle
import app.auf.test.TestEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Tier 2 Core Tests for SessionFeature — Input Draft Persistence & History Navigation.
 *
 * Mandate (P-TEST-001, T2): Tests the reducer and handleSideEffects working together
 * for the three new capabilities:
 *   1. Per-session draft persisted across view switches and app restarts (input.json).
 *   2. Sent-message history with Up/Down arrow navigation.
 *   3. Pre-navigation draft restoration when Down-arrowing past the bottom of history.
 *
 * Filesystem contract: {uuid}/input.json  (alongside {localHandle}.json)
 * State fields (all @Transient on SessionState):
 *   draftInputs     — live draft text, keyed by localHandle
 *   inputHistories  — newest-first list of sent messages, keyed by localHandle
 *   historyNavIndex — current cursor (-1 = not navigating), keyed by localHandle
 *   preNavDrafts    — draft saved on the first UP press, keyed by localHandle
 */
class SessionFeatureT2InputHistoryTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val platform = FakePlatformDependencies("test")
    private val sessionFeature = SessionFeature(platform, scope)
    private val fileSystemFeature = FileSystemFeature(platform)

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** Builds a well-formed test Session. Mirror of SessionFeatureT2CoreTest.testSession(). */
    private fun testSession(
        localHandle: String,
        name: String,
        uuid: String = "00000000-0000-4000-a000-${localHandle.hashCode().toUInt().toString(16).padStart(12, '0')}"
    ): Session {
        val identity = Identity(
            uuid = uuid,
            localHandle = localHandle,
            handle = "session.$localHandle",
            name = name,
            parentHandle = "session"
        )
        return Session(identity = identity, ledger = emptyList(), createdAt = 1L)
    }

    // ============================================================
    // 1. INPUT_DRAFT_CHANGED — reducer state + debounced persistence
    // ============================================================

    @Test
    fun `INPUT_DRAFT_CHANGED updates draftInputs in state`() = runTest {
        val session = testSession("sid-1", "Chat")
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withInitialState("session", SessionState(
                sessions = mapOf(session.identity.localHandle to session),
                activeSessionLocalHandle = session.identity.localHandle
            ))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("session.ui", Action(
                ActionRegistry.Names.SESSION_INPUT_DRAFT_CHANGED,
                buildJsonObject {
                    put("sessionId", "sid-1")
                    put("draft", "Hello, world")
                }
            ))
            testScheduler.advanceUntilIdle()

            val state = harness.store.state.value.featureStates["session"] as SessionState
            assertEquals("Hello, world", state.draftInputs["sid-1"],
                "draftInputs should reflect the new draft text")
        }
    }

    @Test
    fun `INPUT_DRAFT_CHANGED for an unknown session is ignored`() = runTest {
        val session = testSession("sid-1", "Chat")
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withInitialState("session", SessionState(
                sessions = mapOf(session.identity.localHandle to session)
            ))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("session.ui", Action(
                ActionRegistry.Names.SESSION_INPUT_DRAFT_CHANGED,
                buildJsonObject { put("sessionId", "non-existent"); put("draft", "text") }
            ))
            testScheduler.advanceUntilIdle()

            val state = harness.store.state.value.featureStates["session"] as SessionState
            assertTrue(state.draftInputs.isEmpty(), "Unknown session should not create a draft entry")
        }
    }

    @Test
    fun `INPUT_DRAFT_CHANGED triggers a debounced write of input dot json`() = runTest {
        // Must use THIS (TestScope) as the feature's coroutine scope so that delay(5_000)
        // uses the virtual clock controlled by testScheduler.advanceTimeBy().
        // The class-level sessionFeature uses Dispatchers.Unconfined whose delay() runs
        // on the real clock and is unaffected by testScheduler.
        val featureUnderTest = SessionFeature(platform, this)
        val session = testSession("sid-1", "Chat")
        val harness = TestEnvironment.create()
            .withFeature(featureUnderTest)
            .withFeature(fileSystemFeature)
            .withInitialState("session", SessionState(
                sessions = mapOf(session.identity.localHandle to session),
                activeSessionLocalHandle = session.identity.localHandle
            ))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("session.ui", Action(
                ActionRegistry.Names.SESSION_INPUT_DRAFT_CHANGED,
                buildJsonObject { put("sessionId", "sid-1"); put("draft", "draft text") }
            ))
            // Advance past the 5-second debounce window
            testScheduler.advanceTimeBy(6_000)
            testScheduler.advanceUntilIdle()

            val writeActions = harness.processedActions.filter {
                it.name == ActionRegistry.Names.FILESYSTEM_SYSTEM_WRITE
            }
            val inputWrite = writeActions.find {
                it.payload?.get("subpath")?.jsonPrimitive?.content?.endsWith("/input.json") == true
            }
            assertNotNull(inputWrite,
                "Should have written input.json after debounce. Writes: ${writeActions.map { it.payload?.get("subpath") }}")

            // Verify the path follows the {uuid}/input.json convention
            val subpath = inputWrite.payload?.get("subpath")?.jsonPrimitive?.content!!
            assertTrue(subpath.startsWith(session.identity.uuid!!),
                "input.json path should start with the session UUID, got: $subpath")
        }
    }

    @Test
    fun `multiple rapid INPUT_DRAFT_CHANGED dispatches produce only one debounced write`() = runTest {
        // Same reason as the single-write test: delay() must use the virtual clock.
        val featureUnderTest = SessionFeature(platform, this)
        val session = testSession("sid-1", "Chat")
        val harness = TestEnvironment.create()
            .withFeature(featureUnderTest)
            .withFeature(fileSystemFeature)
            .withInitialState("session", SessionState(
                sessions = mapOf(session.identity.localHandle to session),
                activeSessionLocalHandle = session.identity.localHandle
            ))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // Fire three rapid changes — only the last should trigger a write
            for (text in listOf("H", "He", "Hello")) {
                harness.store.dispatch("session.ui", Action(
                    ActionRegistry.Names.SESSION_INPUT_DRAFT_CHANGED,
                    buildJsonObject { put("sessionId", "sid-1"); put("draft", text) }
                ))
            }
            testScheduler.advanceTimeBy(6_000)
            testScheduler.advanceUntilIdle()

            val inputWrites = harness.processedActions.filter {
                it.name == ActionRegistry.Names.FILESYSTEM_SYSTEM_WRITE &&
                        it.payload?.get("subpath")?.jsonPrimitive?.content?.endsWith("/input.json") == true
            }
            assertEquals(1, inputWrites.size,
                "Rapid changes should collapse into a single debounced write, got ${inputWrites.size}")

            // The persisted content should contain the final draft value
            val content = inputWrites.first().payload?.get("content")?.jsonPrimitive?.content
            assertNotNull(content)
            assertTrue(content.contains("Hello"),
                "Written content should reflect the final draft value, got: $content")
        }
    }

    // ============================================================
    // 2. SESSION_POST (user) — clears draft, saves to history
    // ============================================================

    @Test
    fun `SESSION_POST by user clears the draft for that session`() = runTest {
        val session = testSession("sid-1", "Chat")
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withFeature(fileSystemFeature)
            .withInitialState("session", SessionState(
                sessions = mapOf(session.identity.localHandle to session),
                activeSessionLocalHandle = session.identity.localHandle,
                draftInputs = mapOf("sid-1" to "draft text")
            ))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("session.ui", Action(
                ActionRegistry.Names.SESSION_POST,
                buildJsonObject {
                    put("session", "sid-1")
                    put("senderId", "user")
                    put("message", "draft text")
                }
            ))
            testScheduler.advanceUntilIdle()

            val state = harness.store.state.value.featureStates["session"] as SessionState
            assertTrue(
                state.draftInputs["sid-1"].isNullOrEmpty(),
                "Draft should be cleared after a successful post"
            )
        }
    }

    @Test
    fun `SESSION_POST by user adds the message to history as the newest entry`() = runTest {
        val session = testSession("sid-1", "Chat")
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withFeature(fileSystemFeature)
            .withInitialState("session", SessionState(
                sessions = mapOf(session.identity.localHandle to session)
            ))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("session.ui", Action(
                ActionRegistry.Names.SESSION_POST,
                buildJsonObject { put("session", "sid-1"); put("senderId", "user"); put("message", "First message") }
            ))
            testScheduler.advanceUntilIdle()
            harness.store.dispatch("session.ui", Action(
                ActionRegistry.Names.SESSION_POST,
                buildJsonObject { put("session", "sid-1"); put("senderId", "user"); put("message", "Second message") }
            ))
            testScheduler.advanceUntilIdle()

            val state = harness.store.state.value.featureStates["session"] as SessionState
            val history = state.inputHistories["sid-1"] ?: emptyList()
            assertEquals(2, history.size)
            // Newest-first: Second message is at index 0
            assertEquals("Second message", history[0], "History should be newest-first")
            assertEquals("First message", history[1])
        }
    }

    @Test
    fun `SESSION_POST by user does not add blank messages to history`() = runTest {
        val session = testSession("sid-1", "Chat")
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withInitialState("session", SessionState(
                sessions = mapOf(session.identity.localHandle to session)
            ))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("session.ui", Action(
                ActionRegistry.Names.SESSION_POST,
                buildJsonObject { put("session", "sid-1"); put("senderId", "user"); put("message", "   ") }
            ))
            testScheduler.advanceUntilIdle()

            val state = harness.store.state.value.featureStates["session"] as SessionState
            assertTrue(
                state.inputHistories["sid-1"].isNullOrEmpty(),
                "Blank messages should not be added to history"
            )
        }
    }

    @Test
    fun `SESSION_POST by user does not add a duplicate of the last history entry`() = runTest {
        val session = testSession("sid-1", "Chat")
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withInitialState("session", SessionState(
                sessions = mapOf(session.identity.localHandle to session),
                inputHistories = mapOf("sid-1" to listOf("same message"))
            ))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("session.ui", Action(
                ActionRegistry.Names.SESSION_POST,
                buildJsonObject { put("session", "sid-1"); put("senderId", "user"); put("message", "same message") }
            ))
            testScheduler.advanceUntilIdle()

            val state = harness.store.state.value.featureStates["session"] as SessionState
            val history = state.inputHistories["sid-1"] ?: emptyList()
            assertEquals(1, history.size,
                "Duplicate consecutive entry should not be added to history")
        }
    }

    @Test
    fun `SESSION_POST by user resets the historyNavIndex for that session`() = runTest {
        val session = testSession("sid-1", "Chat")
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withInitialState("session", SessionState(
                sessions = mapOf(session.identity.localHandle to session),
                inputHistories = mapOf("sid-1" to listOf("old message")),
                historyNavIndex = mapOf("sid-1" to 0)
            ))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("session.ui", Action(
                ActionRegistry.Names.SESSION_POST,
                buildJsonObject { put("session", "sid-1"); put("senderId", "user"); put("message", "new message") }
            ))
            testScheduler.advanceUntilIdle()

            val state = harness.store.state.value.featureStates["session"] as SessionState
            val navIndex = state.historyNavIndex["sid-1"] ?: -1
            assertEquals(-1, navIndex, "historyNavIndex should be reset to -1 after a post")
        }
    }

    @Test
    fun `SESSION_POST immediately writes input dot json (no debounce on clear)`() = runTest {
        val session = testSession("sid-1", "Chat")
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withFeature(fileSystemFeature)
            .withInitialState("session", SessionState(
                sessions = mapOf(session.identity.localHandle to session),
                draftInputs = mapOf("sid-1" to "about to send")
            ))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("session.ui", Action(
                ActionRegistry.Names.SESSION_POST,
                buildJsonObject { put("session", "sid-1"); put("senderId", "user"); put("message", "about to send") }
            ))
            // Do NOT advance the debounce timer — post should write immediately
            testScheduler.advanceUntilIdle()

            val inputWrites = harness.processedActions.filter {
                it.name == ActionRegistry.Names.FILESYSTEM_SYSTEM_WRITE &&
                        it.payload?.get("subpath")?.jsonPrimitive?.content?.endsWith("/input.json") == true
            }
            assertTrue(inputWrites.isNotEmpty(),
                "input.json should be written immediately after a post, without waiting for debounce")
        }
    }

    @Test
    fun `SESSION_POST caps history at MAX_HISTORY_SIZE entries`() = runTest {
        val maxHistorySize = 50
        val session = testSession("sid-1", "Chat")
        val fullHistory = (1..maxHistorySize).map { "Entry $it" } // oldest-to-newest order
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withInitialState("session", SessionState(
                sessions = mapOf(session.identity.localHandle to session),
                // History already full, newest-first: Entry 50 at [0], Entry 1 at [49]
                inputHistories = mapOf("sid-1" to fullHistory.reversed())
            ))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("session.ui", Action(
                ActionRegistry.Names.SESSION_POST,
                buildJsonObject { put("session", "sid-1"); put("senderId", "user"); put("message", "New entry") }
            ))
            testScheduler.advanceUntilIdle()

            val state = harness.store.state.value.featureStates["session"] as SessionState
            val history = state.inputHistories["sid-1"] ?: emptyList()
            assertEquals(maxHistorySize, history.size,
                "History should be capped at $maxHistorySize entries")
            assertEquals("New entry", history[0],
                "The newest entry should be at index 0")
            assertEquals("Entry 2", history[maxHistorySize - 1],
                "The oldest entry (Entry 1) should have been evicted")
        }
    }

    // ============================================================
    // 3. HISTORY_NAVIGATE — Up/Down arrow navigation
    // ============================================================

    @Test
    fun `HISTORY_NAVIGATE UP on empty history is a no-op`() = runTest {
        val session = testSession("sid-1", "Chat")
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withInitialState("session", SessionState(
                sessions = mapOf(session.identity.localHandle to session),
                draftInputs = mapOf("sid-1" to "current draft")
            ))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("session.ui", Action(
                ActionRegistry.Names.SESSION_HISTORY_NAVIGATE,
                buildJsonObject { put("sessionId", "sid-1"); put("direction", "UP") }
            ))
            testScheduler.advanceUntilIdle()

            val state = harness.store.state.value.featureStates["session"] as SessionState
            assertEquals("current draft", state.draftInputs["sid-1"],
                "Draft should be unchanged when history is empty")
            assertEquals(-1, state.historyNavIndex["sid-1"] ?: -1,
                "Nav index should remain -1 on empty history")
        }
    }

    @Test
    fun `HISTORY_NAVIGATE UP on first press saves draft as preNavDraft and shows history entry`() = runTest {
        val session = testSession("sid-1", "Chat")
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withInitialState("session", SessionState(
                sessions = mapOf(session.identity.localHandle to session),
                draftInputs = mapOf("sid-1" to "unsent draft"),
                inputHistories = mapOf("sid-1" to listOf("most recent sent", "older sent"))
            ))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("session.ui", Action(
                ActionRegistry.Names.SESSION_HISTORY_NAVIGATE,
                buildJsonObject { put("sessionId", "sid-1"); put("direction", "UP") }
            ))
            testScheduler.advanceUntilIdle()

            val state = harness.store.state.value.featureStates["session"] as SessionState
            assertEquals("most recent sent", state.draftInputs["sid-1"],
                "First UP should populate the draft with the most recent history entry")
            assertEquals(0, state.historyNavIndex["sid-1"],
                "Nav index should be 0 after first UP")
            assertEquals("unsent draft", state.preNavDrafts["sid-1"],
                "The original draft should be saved as preNavDraft")
        }
    }

    @Test
    fun `HISTORY_NAVIGATE UP successive presses walk back through history`() = runTest {
        val session = testSession("sid-1", "Chat")
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withInitialState("session", SessionState(
                sessions = mapOf(session.identity.localHandle to session),
                inputHistories = mapOf("sid-1" to listOf("newest", "middle", "oldest"))
            ))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            fun pressUp() = harness.store.dispatch("session.ui", Action(
                ActionRegistry.Names.SESSION_HISTORY_NAVIGATE,
                buildJsonObject { put("sessionId", "sid-1"); put("direction", "UP") }
            ))

            pressUp()
            testScheduler.advanceUntilIdle()
            assertEquals("newest", (harness.store.state.value.featureStates["session"] as SessionState).draftInputs["sid-1"])

            pressUp()
            testScheduler.advanceUntilIdle()
            assertEquals("middle", (harness.store.state.value.featureStates["session"] as SessionState).draftInputs["sid-1"])

            pressUp()
            testScheduler.advanceUntilIdle()
            assertEquals("oldest", (harness.store.state.value.featureStates["session"] as SessionState).draftInputs["sid-1"])
        }
    }

    @Test
    fun `HISTORY_NAVIGATE UP at oldest entry is clamped and does not wrap`() = runTest {
        val session = testSession("sid-1", "Chat")
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withInitialState("session", SessionState(
                sessions = mapOf(session.identity.localHandle to session),
                inputHistories = mapOf("sid-1" to listOf("only entry")),
                historyNavIndex = mapOf("sid-1" to 0), // already at the oldest
                draftInputs = mapOf("sid-1" to "only entry")
            ))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("session.ui", Action(
                ActionRegistry.Names.SESSION_HISTORY_NAVIGATE,
                buildJsonObject { put("sessionId", "sid-1"); put("direction", "UP") }
            ))
            testScheduler.advanceUntilIdle()

            val state = harness.store.state.value.featureStates["session"] as SessionState
            assertEquals(0, state.historyNavIndex["sid-1"],
                "Nav index should stay clamped at the last history index")
            assertEquals("only entry", state.draftInputs["sid-1"],
                "Draft should remain at the oldest entry when already at the end")
        }
    }

    @Test
    fun `HISTORY_NAVIGATE DOWN at navIndex 0 restores the preNavDraft`() = runTest {
        val session = testSession("sid-1", "Chat")
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withInitialState("session", SessionState(
                sessions = mapOf(session.identity.localHandle to session),
                inputHistories = mapOf("sid-1" to listOf("sent message")),
                historyNavIndex = mapOf("sid-1" to 0),
                draftInputs = mapOf("sid-1" to "sent message"),
                preNavDrafts = mapOf("sid-1" to "original unsent draft")
            ))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("session.ui", Action(
                ActionRegistry.Names.SESSION_HISTORY_NAVIGATE,
                buildJsonObject { put("sessionId", "sid-1"); put("direction", "DOWN") }
            ))
            testScheduler.advanceUntilIdle()

            val state = harness.store.state.value.featureStates["session"] as SessionState
            assertEquals("original unsent draft", state.draftInputs["sid-1"],
                "DOWN from navIndex 0 should restore the pre-navigation draft")
            assertEquals(-1, state.historyNavIndex["sid-1"] ?: -1,
                "Nav index should return to -1 (not navigating)")
            assertNull(state.preNavDrafts["sid-1"],
                "preNavDraft should be cleared after restoration")
        }
    }

    @Test
    fun `HISTORY_NAVIGATE DOWN when not navigating is a no-op`() = runTest {
        val session = testSession("sid-1", "Chat")
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withInitialState("session", SessionState(
                sessions = mapOf(session.identity.localHandle to session),
                draftInputs = mapOf("sid-1" to "current text"),
                inputHistories = mapOf("sid-1" to listOf("a", "b"))
                // historyNavIndex absent → -1
            ))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("session.ui", Action(
                ActionRegistry.Names.SESSION_HISTORY_NAVIGATE,
                buildJsonObject { put("sessionId", "sid-1"); put("direction", "DOWN") }
            ))
            testScheduler.advanceUntilIdle()

            val state = harness.store.state.value.featureStates["session"] as SessionState
            assertEquals("current text", state.draftInputs["sid-1"],
                "DOWN when not navigating should not change the draft")
            assertEquals(-1, state.historyNavIndex["sid-1"] ?: -1,
                "Nav index should remain -1")
        }
    }

    @Test
    fun `HISTORY_NAVIGATE DOWN in mid-history decrements the index and updates draft`() = runTest {
        val session = testSession("sid-1", "Chat")
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withInitialState("session", SessionState(
                sessions = mapOf(session.identity.localHandle to session),
                inputHistories = mapOf("sid-1" to listOf("newest", "middle", "oldest")),
                historyNavIndex = mapOf("sid-1" to 2), // currently showing "oldest"
                draftInputs = mapOf("sid-1" to "oldest"),
                preNavDrafts = mapOf("sid-1" to "")
            ))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("session.ui", Action(
                ActionRegistry.Names.SESSION_HISTORY_NAVIGATE,
                buildJsonObject { put("sessionId", "sid-1"); put("direction", "DOWN") }
            ))
            testScheduler.advanceUntilIdle()

            val state = harness.store.state.value.featureStates["session"] as SessionState
            assertEquals(1, state.historyNavIndex["sid-1"],
                "Index should decrement from 2 to 1")
            assertEquals("middle", state.draftInputs["sid-1"],
                "Draft should show the entry at the new index")
        }
    }

    // ============================================================
    // 4. Per-session isolation
    // ============================================================

    @Test
    fun `drafts are isolated between sessions`() = runTest {
        val session1 = testSession("sid-1", "Chat A")
        val session2 = testSession("sid-2", "Chat B")
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withInitialState("session", SessionState(
                sessions = mapOf(
                    session1.identity.localHandle to session1,
                    session2.identity.localHandle to session2
                )
            ))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("session.ui", Action(
                ActionRegistry.Names.SESSION_INPUT_DRAFT_CHANGED,
                buildJsonObject { put("sessionId", "sid-1"); put("draft", "draft for session 1") }
            ))
            harness.store.dispatch("session.ui", Action(
                ActionRegistry.Names.SESSION_INPUT_DRAFT_CHANGED,
                buildJsonObject { put("sessionId", "sid-2"); put("draft", "draft for session 2") }
            ))
            testScheduler.advanceUntilIdle()

            val state = harness.store.state.value.featureStates["session"] as SessionState
            assertEquals("draft for session 1", state.draftInputs["sid-1"])
            assertEquals("draft for session 2", state.draftInputs["sid-2"])
        }
    }

    @Test
    fun `history navigation is isolated between sessions`() = runTest {
        val session1 = testSession("sid-1", "Chat A")
        val session2 = testSession("sid-2", "Chat B")
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withInitialState("session", SessionState(
                sessions = mapOf(
                    session1.identity.localHandle to session1,
                    session2.identity.localHandle to session2
                ),
                inputHistories = mapOf(
                    "sid-1" to listOf("sent in session 1"),
                    "sid-2" to listOf("sent in session 2")
                )
            ))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // Navigate UP in session 1 only
            harness.store.dispatch("session.ui", Action(
                ActionRegistry.Names.SESSION_HISTORY_NAVIGATE,
                buildJsonObject { put("sessionId", "sid-1"); put("direction", "UP") }
            ))
            testScheduler.advanceUntilIdle()

            val state = harness.store.state.value.featureStates["session"] as SessionState
            assertEquals(0, state.historyNavIndex["sid-1"] ?: -1,
                "Session 1 should be navigating")
            assertEquals(-1, state.historyNavIndex["sid-2"] ?: -1,
                "Session 2 nav index should be unaffected")
            assertEquals("sent in session 1", state.draftInputs["sid-1"])
            assertNull(state.draftInputs["sid-2"],
                "Session 2 draft should be unchanged")
        }
    }

    // ============================================================
    // 5. Startup loading of input.json
    // ============================================================

    @Test
    fun `when FILESYSTEM_RESPONSE_LIST includes an input dot json file it dispatches a read for it`() = runTest {
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // Simulate the listing of a UUID folder that contains both session and input files
            val fileList = buildJsonArray {
                add(buildJsonObject {
                    put("path", "00000000-0000-4000-a000-000000000001/my-chat.json")
                    put("isDirectory", false)
                })
                add(buildJsonObject {
                    put("path", "00000000-0000-4000-a000-000000000001/input.json")
                    put("isDirectory", false)
                })
            }
            harness.store.dispatch("filesystem", Action(
                ActionRegistry.Names.FILESYSTEM_RESPONSE_LIST,
                buildJsonObject { put("listing", fileList) },
                targetRecipient = "session"
            ))
            testScheduler.advanceUntilIdle()

            val readActions = harness.processedActions.filter {
                it.name == ActionRegistry.Names.FILESYSTEM_SYSTEM_READ && it.originator == "session"
            }
            val inputReadAction = readActions.find {
                it.payload?.get("subpath")?.jsonPrimitive?.content?.endsWith("input.json") == true
            }
            assertNotNull(inputReadAction,
                "Should dispatch a SYSTEM_READ for input.json when found in listing. " +
                        "Reads dispatched: ${readActions.map { it.payload?.get("subpath") }}")
        }
    }

    @Test
    fun `when it receives valid input dot json content it loads history into state`() = runTest {
        val session = testSession("sid-loaded", "Loaded Session",
            uuid = "00000000-0000-4000-a000-000000000001")
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withInitialState("session", SessionState(
                sessions = mapOf(session.identity.localHandle to session)
            ))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // SessionInputState stored as { draft, history }
            val inputJson = """{"draft":"my saved draft","history":["last sent","earlier sent"]}"""
            harness.store.dispatch("filesystem", Action(
                ActionRegistry.Names.FILESYSTEM_RESPONSE_READ,
                buildJsonObject {
                    put("subpath", "00000000-0000-4000-a000-000000000001/input.json")
                    put("content", inputJson)
                },
                targetRecipient = "session"
            ))
            testScheduler.advanceUntilIdle()

            val state = harness.store.state.value.featureStates["session"] as SessionState
            assertEquals("my saved draft", state.draftInputs["sid-loaded"],
                "Draft should be restored from input.json")
            val history = state.inputHistories["sid-loaded"] ?: emptyList()
            assertEquals(2, history.size, "History should be loaded from input.json")
            assertEquals("last sent", history[0])
            assertEquals("earlier sent", history[1])
        }
    }

    @Test
    fun `when input dot json content is corrupted it logs a warning and does not crash`() = runTest {
        val session = testSession("sid-1", "Chat", uuid = "00000000-0000-4000-a000-000000000001")
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withInitialState("session", SessionState(
                sessions = mapOf(session.identity.localHandle to session)
            ))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("filesystem", Action(
                ActionRegistry.Names.FILESYSTEM_RESPONSE_READ,
                buildJsonObject {
                    put("subpath", "00000000-0000-4000-a000-000000000001/input.json")
                    put("content", "{ totally: invalid json {{")
                },
                targetRecipient = "session"
            ))
            testScheduler.advanceUntilIdle()

            // Should not crash; state should be unchanged (no history for this session)
            val state = harness.store.state.value.featureStates["session"] as SessionState
            assertTrue(state.inputHistories["sid-1"].isNullOrEmpty(),
                "Corrupted input.json should result in empty history, not a crash")
        }
    }

    // ============================================================
    // 6. Session deletion cleans up input.json
    // ============================================================

    @Test
    fun `SESSION_DELETE does not separately delete input dot json — the UUID folder deletion covers it`() = runTest {
        // The UUID folder deletion (filesystem.SYSTEM_DELETE with subpath = uuid) removes the
        // entire folder including input.json. This test confirms no redundant per-file deletion
        // is dispatched (which would fail if the folder delete ran first).
        val session = testSession("sid-1", "To Delete")
        val harness = TestEnvironment.create()
            .withFeature(sessionFeature)
            .withFeature(fileSystemFeature)
            .withInitialState("session", SessionState(
                sessions = mapOf(session.identity.localHandle to session),
                draftInputs = mapOf("sid-1" to "draft"),
                inputHistories = mapOf("sid-1" to listOf("sent"))
            ))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("ui", Action(
                ActionRegistry.Names.SESSION_DELETE,
                buildJsonObject { put("session", "sid-1") }
            ))
            testScheduler.advanceUntilIdle()

            val deleteActions = harness.processedActions.filter {
                it.name == ActionRegistry.Names.FILESYSTEM_SYSTEM_DELETE
            }
            // There should be exactly one delete: the UUID folder, not a separate input.json delete
            val inputJsonDeletes = deleteActions.filter {
                it.payload?.get("subpath")?.jsonPrimitive?.content?.endsWith("input.json") == true
            }
            assertTrue(inputJsonDeletes.isEmpty(),
                "input.json should NOT be individually deleted — the UUID folder delete covers it")

            val folderDelete = deleteActions.find {
                it.payload?.get("subpath")?.jsonPrimitive?.content == session.identity.uuid
            }
            assertNotNull(folderDelete, "UUID folder should still be deleted to remove all session files")

            // In-memory state should also be cleaned up
            val state = harness.store.state.value.featureStates["session"] as SessionState
            assertNull(state.draftInputs["sid-1"], "In-memory draft should be cleared on session delete")
            assertNull(state.inputHistories["sid-1"], "In-memory history should be cleared on session delete")
        }
    }
}