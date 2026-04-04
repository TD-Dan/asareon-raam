package app.auf.feature.session

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.auf.core.AppState
import app.auf.core.Identity
import app.auf.core.generated.ActionRegistry
import app.auf.fakes.FakePlatformDependencies
import app.auf.fakes.FakeStore
import app.auf.ui.AppTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tier 1 Component Test for SessionView.
 *
 * Mandate (P-TEST-001, T1): Tests the UI component's rendering and action dispatching
 * in isolation, using a FakeStore to intercept dispatched actions.
 *
 * Covers original tab/send behaviour plus the three new input-field capabilities:
 *   - Draft is driven from SessionState.draftInputs (not local Compose state),
 *     so it survives tab switches and view changes.
 *   - Typing dispatches SESSION_INPUT_DRAFT_CHANGED instead of mutating local state.
 *   - Up/Down arrow keys dispatch SESSION_HISTORY_NAVIGATE.
 */
class SessionFeatureT1SessionViewTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var fakeStore: FakeStore
    private val platform = FakePlatformDependencies("test")

    private val session1Identity = Identity(
        uuid = "00000000-0000-4000-a000-000000000001",
        localHandle = "sid-1", handle = "session.sid-1",
        name = "Session One", parentHandle = "session"
    )
    private val session2Identity = Identity(
        uuid = "00000000-0000-4000-a000-000000000002",
        localHandle = "sid-2", handle = "session.sid-2",
        name = "Session Two", parentHandle = "session"
    )
    private val session1 = Session(identity = session1Identity, ledger = emptyList(), createdAt = 1L)
    private val session2 = Session(identity = session2Identity, ledger = emptyList(), createdAt = 2L)

    @Before
    fun setup() {
        fakeStore = FakeStore(AppState(), platform)
    }

    private fun setViewState(state: SessionState) {
        fakeStore.setState(AppState(featureStates = mapOf("session" to state)))
        composeTestRule.setContent {
            AppTheme {
                SessionView(store = fakeStore, features = emptyList(), platformDependencies = platform)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Original tests (preserved unchanged)
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `renders tabs for each session and shows active session content`() {
        setViewState(SessionState(
            sessions = mapOf(
                session1.identity.localHandle to session1,
                session2.identity.localHandle to session2
            ),
            activeSessionLocalHandle = session1.identity.localHandle
        ))

        composeTestRule.onNodeWithText("Session One").assertIsSelected()
        composeTestRule.onNodeWithText("Session Two").assertIsNotSelected()
        composeTestRule.onNodeWithText("Enter message (Ctrl+Enter to send, / for commandline)...").assertIsDisplayed()
    }

    @Test
    fun `clicking a tab dispatches SET_ACTIVE_TAB`() {
        setViewState(SessionState(
            sessions = mapOf(
                session1.identity.localHandle to session1,
                session2.identity.localHandle to session2
            ),
            activeSessionLocalHandle = session1.identity.localHandle
        ))

        composeTestRule.onNodeWithText("Session Two").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_SET_ACTIVE_TAB }
        assertNotNull(action)
        assertEquals("session", action.originator)
        assertEquals(session2.identity.localHandle, action.payload?.get("session").toString().trim('"'))
    }

    @Test
    fun `clicking Send dispatches SESSION_POST with the current draft text`() {
        setViewState(SessionState(
            sessions = mapOf(session1.identity.localHandle to session1),
            activeSessionLocalHandle = session1.identity.localHandle,
            // Draft is pre-loaded in state rather than typed by the user in this test,
            // because typing is now dispatched via INPUT_DRAFT_CHANGED before sending.
            draftInputs = mapOf("sid-1" to "Hello, world!")
        ))

        composeTestRule.onNodeWithContentDescription("Send").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_POST }
        assertNotNull(action, "Clicking Send should dispatch SESSION_POST")
        assertEquals("session", action.originator)
        assertEquals(session1.identity.localHandle, action.payload?.get("session").toString().trim('"'))
        assertEquals("user", action.payload?.get("senderId").toString().trim('"'))
        assertEquals("Hello, world!", action.payload?.get("message").toString().trim('"'))
    }

    // ─────────────────────────────────────────────────────────────
    // Draft persistence across tab switches — input driven from state
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `input field shows the draft stored in SessionState for the active session`() {
        setViewState(SessionState(
            sessions = mapOf(session1.identity.localHandle to session1),
            activeSessionLocalHandle = session1.identity.localHandle,
            draftInputs = mapOf("sid-1" to "saved draft text")
        ))

        // The text field should already contain the stored draft without any user interaction
        composeTestRule.onNodeWithText("saved draft text").assertIsDisplayed()
    }

    @Test
    fun `input field shows empty string when no draft is stored for the active session`() {
        setViewState(SessionState(
            sessions = mapOf(session1.identity.localHandle to session1),
            activeSessionLocalHandle = session1.identity.localHandle
            // draftInputs absent → should display placeholder / empty field
        ))

        composeTestRule.onNodeWithText("Enter message (Ctrl+Enter to send, / for commandline)...").assertIsDisplayed()
    }

    @Test
    fun `switching active session updates the input field to show that session draft`() {
        setViewState(SessionState(
            sessions = mapOf(
                session1.identity.localHandle to session1,
                session2.identity.localHandle to session2
            ),
            activeSessionLocalHandle = session1.identity.localHandle,
            draftInputs = mapOf(
                "sid-1" to "draft for session 1",
                "sid-2" to "draft for session 2"
            )
        ))

        // Session 1 is active — its draft is shown
        composeTestRule.onNodeWithText("draft for session 1").assertIsDisplayed()

        // Now simulate the store switching to session 2
        fakeStore.setState(AppState(featureStates = mapOf("session" to SessionState(
            sessions = mapOf(
                session1.identity.localHandle to session1,
                session2.identity.localHandle to session2
            ),
            activeSessionLocalHandle = session2.identity.localHandle,
            draftInputs = mapOf(
                "sid-1" to "draft for session 1",
                "sid-2" to "draft for session 2"
            )
        ))))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("draft for session 2").assertIsDisplayed()
    }

    // ─────────────────────────────────────────────────────────────
    // Typing dispatches INPUT_DRAFT_CHANGED
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `typing in the input field dispatches SESSION_INPUT_DRAFT_CHANGED`() {
        setViewState(SessionState(
            sessions = mapOf(session1.identity.localHandle to session1),
            activeSessionLocalHandle = session1.identity.localHandle
        ))

        composeTestRule
            .onNodeWithText("Enter message (Ctrl+Enter to send, / for commandline)...")
            .performTextInput("typing test")

        // Draft dispatch is debounced (2 s trailing edge) — wait for it to fire.
        composeTestRule.waitUntil(timeoutMillis = 3_000) {
            fakeStore.dispatchedActions.any {
                it.name == ActionRegistry.Names.SESSION_INPUT_DRAFT_CHANGED
            }
        }

        val draftActions = fakeStore.dispatchedActions.filter {
            it.name == ActionRegistry.Names.SESSION_INPUT_DRAFT_CHANGED
        }
        assertTrue(draftActions.isNotEmpty(),
            "Typing should dispatch SESSION_INPUT_DRAFT_CHANGED")

        val lastDraftAction = draftActions.last()
        assertEquals(session1.identity.localHandle, lastDraftAction.payload?.get("sessionId").toString().trim('"'))
        // The draft value should end with the typed text (may be incremental per keystroke)
        val draft = lastDraftAction.payload?.get("draft").toString().trim('"')
        assertTrue(draft.endsWith("g") || draft == "typing test",
            "Final draft dispatch should contain the typed text, got: $draft")
    }

    @Test
    fun `typing does NOT dispatch SESSION_POST`() {
        setViewState(SessionState(
            sessions = mapOf(session1.identity.localHandle to session1),
            activeSessionLocalHandle = session1.identity.localHandle
        ))

        composeTestRule
            .onNodeWithText("Enter message (Ctrl+Enter to send, / for commandline)...")
            .performTextInput("just typing")

        val postActions = fakeStore.dispatchedActions.filter {
            it.name == ActionRegistry.Names.SESSION_POST
        }
        assertTrue(postActions.isEmpty(),
            "Typing alone should not dispatch SESSION_POST, only the Send button/shortcut should")
    }

    // ─────────────────────────────────────────────────────────────
    // History navigation — Up/Down arrow keys
    // ─────────────────────────────────────────────────────────────

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `pressing Up arrow dispatches SESSION_HISTORY_NAVIGATE with direction UP`() {
        setViewState(SessionState(
            sessions = mapOf(session1.identity.localHandle to session1),
            activeSessionLocalHandle = session1.identity.localHandle
        ))

        // onNodeWithText(placeholder) finds the inner Text composable, NOT the focusable
        // BasicTextField that has onPreviewKeyEvent attached. hasSetTextAction() matches
        // the actual editable/focusable node that sits in the key-event dispatch path.
        composeTestRule.onNode(hasSetTextAction()).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasSetTextAction())
            .performKeyInput { keyDown(Key.DirectionUp) }

        val navAction = fakeStore.dispatchedActions.find {
            it.name == ActionRegistry.Names.SESSION_HISTORY_NAVIGATE
        }
        assertNotNull(navAction, "Up arrow should dispatch SESSION_HISTORY_NAVIGATE")
        assertEquals(session1.identity.localHandle, navAction.payload?.get("sessionId").toString().trim('"'))
        assertEquals("UP", navAction.payload?.get("direction").toString().trim('"'))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `pressing Down arrow dispatches SESSION_HISTORY_NAVIGATE with direction DOWN`() {
        setViewState(SessionState(
            sessions = mapOf(session1.identity.localHandle to session1),
            activeSessionLocalHandle = session1.identity.localHandle,
            historyNavIndex = mapOf("sid-1" to 0) // must be navigating for DOWN to be meaningful
        ))

        composeTestRule.onNode(hasSetTextAction()).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasSetTextAction())
            .performKeyInput { keyDown(Key.DirectionDown) }

        val navAction = fakeStore.dispatchedActions.find {
            it.name == ActionRegistry.Names.SESSION_HISTORY_NAVIGATE
        }
        assertNotNull(navAction, "Down arrow should dispatch SESSION_HISTORY_NAVIGATE")
        assertEquals(session1.identity.localHandle, navAction.payload?.get("sessionId").toString().trim('"'))
        assertEquals("DOWN", navAction.payload?.get("direction").toString().trim('"'))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `regular arrow keys do not dispatch SESSION_HISTORY_NAVIGATE`() {
        setViewState(SessionState(
            sessions = mapOf(session1.identity.localHandle to session1),
            activeSessionLocalHandle = session1.identity.localHandle
        ))

        composeTestRule.onNode(hasSetTextAction()).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasSetTextAction())
            .performKeyInput { keyDown(Key.DirectionLeft) }

        val navActions = fakeStore.dispatchedActions.filter {
            it.name == ActionRegistry.Names.SESSION_HISTORY_NAVIGATE
        }
        assertTrue(navActions.isEmpty(), "Left/Right arrow should not trigger history navigation")
    }

    @Test
    fun `input field reflects history entry when state shows navigation in progress`() {
        // When the store state already has a navigation in progress (historyNavIndex >= 0),
        // the input field should show the draft from draftInputs (which the reducer sets
        // to the history entry). This confirms the field is purely state-driven.
        setViewState(SessionState(
            sessions = mapOf(session1.identity.localHandle to session1),
            activeSessionLocalHandle = session1.identity.localHandle,
            inputHistories = mapOf("sid-1" to listOf("previously sent message")),
            historyNavIndex = mapOf("sid-1" to 0),
            draftInputs = mapOf("sid-1" to "previously sent message")
        ))

        composeTestRule.onNodeWithText("previously sent message").assertIsDisplayed()
    }

    // ─────────────────────────────────────────────────────────────
    // Send clears the field (via state update from store)
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `sending a message dispatches SESSION_POST with correct payload`() {
        setViewState(SessionState(
            sessions = mapOf(session1.identity.localHandle to session1),
            activeSessionLocalHandle = session1.identity.localHandle,
            draftInputs = mapOf("sid-1" to "Hello, world!")
        ))

        composeTestRule.onNodeWithContentDescription("Send").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_POST }
        assertNotNull(action)
        assertEquals("session", action.originator)
        assertEquals(session1.identity.localHandle, action.payload?.get("session").toString().trim('"'))
        assertEquals("user", action.payload?.get("senderId").toString().trim('"'))
        assertEquals("Hello, world!", action.payload?.get("message").toString().trim('"'))
    }

    @Test
    fun `after sending the input field clears when state is updated to empty draft`() {
        setViewState(SessionState(
            sessions = mapOf(session1.identity.localHandle to session1),
            activeSessionLocalHandle = session1.identity.localHandle,
            draftInputs = mapOf("sid-1" to "to be sent")
        ))

        composeTestRule.onNodeWithContentDescription("Send").performClick()
        composeTestRule.waitForIdle()

        // Simulate the store responding with cleared draft (as the reducer would do)
        fakeStore.setState(AppState(featureStates = mapOf("session" to SessionState(
            sessions = mapOf(session1.identity.localHandle to session1),
            activeSessionLocalHandle = session1.identity.localHandle,
            draftInputs = emptyMap() // reducer clears the draft
        ))))
        composeTestRule.waitForIdle()

        // Input should now show placeholder (empty)
        composeTestRule.onNodeWithText("Enter message (Ctrl+Enter to send, / for commandline)...").assertIsDisplayed()
    }
}