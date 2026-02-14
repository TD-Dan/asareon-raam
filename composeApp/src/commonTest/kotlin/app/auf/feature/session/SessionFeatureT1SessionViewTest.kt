package app.auf.feature.session

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

/**
 * Tier 1 Component Test for SessionView.
 *
 * Mandate (P-TEST-001, T1): To test the UI component's rendering and action dispatching
 * in isolation, using a FakeStore to intercept dispatched actions.
 */
class SessionFeatureT1SessionViewTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var fakeStore: FakeStore
    private val platform = FakePlatformDependencies("test")
    private val session1Identity = Identity(uuid = "test-uuid-1", localHandle = "sid-1", handle = "session.sid-1", name = "Session One", parentHandle = "session")
    private val session2Identity = Identity(uuid = "test-uuid-2", localHandle = "sid-2", handle = "session.sid-2", name = "Session Two", parentHandle = "session")
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
        composeTestRule.onNodeWithText("Enter message (Ctrl+Enter to send)...").assertIsDisplayed()
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
        assertEquals("session.ui", action.originator)
        assertEquals(session2.identity.localHandle, action.payload?.get("session").toString().trim('"'))
    }

    @Test
    fun `sending a message dispatches SESSION_POST`() {
        setViewState(SessionState(
            sessions = mapOf(session1.identity.localHandle to session1),
            activeSessionLocalHandle = session1.identity.localHandle
        ))

        composeTestRule.onNodeWithText("Enter message (Ctrl+Enter to send)...").performTextInput("Hello, world!")
        composeTestRule.onNodeWithContentDescription("Send").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_POST }
        assertNotNull(action)
        assertEquals("session.ui", action.originator)
        assertEquals(session1.identity.localHandle, action.payload?.get("session").toString().trim('"'))
        assertEquals("user", action.payload?.get("senderId").toString().trim('"'))
        assertEquals("Hello, world!", action.payload?.get("message").toString().trim('"'))
    }
}