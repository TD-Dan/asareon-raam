package asareon.raam.feature.session

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import asareon.raam.core.AppState
import asareon.raam.core.Identity
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.fakes.FakePlatformDependencies
import asareon.raam.fakes.FakeStore
import asareon.raam.ui.AppTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tier 1 Component Test for SessionsManagerView.
 *
 * Mandate (P-TEST-001, T1): To test the UI component's rendering and action dispatching
 * in isolation, using a FakeStore to intercept dispatched actions.
 */
class SessionFeatureT1SessionManagerViewTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var fakePlatform: FakePlatformDependencies
    private lateinit var fakeStore: FakeStore
    private val session1Identity = Identity(uuid = "00000000-0000-4000-a000-000000000001", localHandle = "sid-1", handle = "session.sid-1", name = "My Session", parentHandle = "session")
    private val session1 = Session(identity = session1Identity, ledger = emptyList(), createdAt = 1L)

    @Before
    fun setup() {
        fakePlatform = FakePlatformDependencies("test")
        fakeStore = FakeStore(AppState(), fakePlatform)
    }

    private fun setViewState(state: SessionState) {
        fakeStore.setState(AppState(featureStates = mapOf("session" to state)))
        composeTestRule.setContent {
            AppTheme {
                SessionsManagerView(store = fakeStore, fakePlatform)
            }
        }
    }

    @Test
    fun `renders a card for each session`() {
        setViewState(SessionState(sessions = mapOf(session1.identity.localHandle to session1)))

        composeTestRule.onNodeWithText("My Session").assertIsDisplayed()
        composeTestRule.onNodeWithText("Handle: sid-1").assertIsDisplayed()
    }

    @Test
    fun `clicking new session button dispatches SESSION_CREATE`() {
        setViewState(SessionState())

        composeTestRule.onNodeWithText("New Session").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_CREATE }
        assertNotNull(action)
        assertEquals("session", action.originator)
    }

    // ─────────────────────────────────────────────────────────────
    // Multiple sessions and metadata display
    // ─────────────────────────────────────────────────────────────

    private val session2Identity = Identity(
        uuid = "00000000-0000-4000-a000-000000000002",
        localHandle = "sid-2", handle = "session.sid-2",
        name = "Second Session", parentHandle = "session"
    )
    private val session2 = Session(
        identity = session2Identity,
        ledger = listOf(
            LedgerEntry("msg-1", 1L, "user", "Hello"),
            LedgerEntry("msg-2", 2L, "agent", "World")
        ),
        createdAt = 2L
    )

    @Test
    fun `renders cards for multiple sessions`() {
        setViewState(SessionState(sessions = mapOf(
            session1.identity.localHandle to session1,
            session2.identity.localHandle to session2
        )))

        composeTestRule.onNodeWithText("My Session").assertIsDisplayed()
        composeTestRule.onNodeWithText("Second Session").assertIsDisplayed()
    }

    @Test
    fun `displays message count for sessions`() {
        setViewState(SessionState(sessions = mapOf(session2.identity.localHandle to session2)))

        composeTestRule.onNodeWithText("Messages: 2").assertIsDisplayed()
    }

    // ─────────────────────────────────────────────────────────────
    // Visibility toggle
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `clicking Hide Session button dispatches TOGGLE_SESSION_HIDDEN`() {
        setViewState(SessionState(sessions = mapOf(session1.identity.localHandle to session1)))

        composeTestRule.onNodeWithContentDescription("Hide Session").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_TOGGLE_SESSION_HIDDEN }
        assertNotNull(action, "Should dispatch TOGGLE_SESSION_HIDDEN")
        assertEquals(session1.identity.localHandle, action.payload?.get("session").toString().trim('"'))
    }

    @Test
    fun `hidden session shows Unhide Session button`() {
        val hiddenSession = session1.copy(isHidden = true)
        setViewState(SessionState(
            sessions = mapOf(hiddenSession.identity.localHandle to hiddenSession),
            hideHiddenInManager = false // Show hidden sessions so the card renders
        ))

        composeTestRule.onNodeWithContentDescription("Unhide Session").assertIsDisplayed()
    }

    // ─────────────────────────────────────────────────────────────
    // Edit button
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `clicking edit button dispatches SET_EDITING_SESSION_NAME`() {
        setViewState(SessionState(sessions = mapOf(session1.identity.localHandle to session1)))

        composeTestRule.onNodeWithContentDescription("Edit Session Name").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_SET_EDITING_SESSION_NAME }
        assertNotNull(action, "Should dispatch SET_EDITING_SESSION_NAME")
        assertEquals(session1.identity.localHandle, action.payload?.get("sessionId").toString().trim('"'))
    }

    // ─────────────────────────────────────────────────────────────
    // Clone from kebab menu
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `clicking Clone from menu dispatches SESSION_CLONE`() {
        setViewState(SessionState(sessions = mapOf(session1.identity.localHandle to session1)))

        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Clone").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_CLONE }
        assertNotNull(action, "Should dispatch SESSION_CLONE")
        assertEquals(session1.identity.localHandle, action.payload?.get("session").toString().trim('"'))
    }

    // ─────────────────────────────────────────────────────────────
    // Locked message count display
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `displays locked message count when session has locked entries`() {
        val sessionWithLocked = session2.copy(
            ledger = listOf(
                LedgerEntry("msg-1", 1L, "user", "Normal"),
                LedgerEntry("msg-2", 2L, "user", "Locked", isLocked = true),
                LedgerEntry("msg-3", 3L, "agent", "Also Locked", isLocked = true)
            )
        )
        setViewState(SessionState(sessions = mapOf(sessionWithLocked.identity.localHandle to sessionWithLocked)))

        composeTestRule.onNodeWithText("Locked: 2").assertIsDisplayed()
    }
}