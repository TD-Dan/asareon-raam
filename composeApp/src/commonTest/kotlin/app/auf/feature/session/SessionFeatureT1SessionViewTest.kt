package app.auf.feature.session

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.auf.core.AppState
import app.auf.core.generated.ActionNames
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
    private val platform = FakePlatformDependencies("test") // FIX: Instantiate platform dependencies
    private val session1 = Session("sid-1", "Session One", emptyList(), 1L)
    private val session2 = Session("sid-2", "Session Two", emptyList(), 2L)

    @Before
    fun setup() {
        // A minimal registry for actions this view is expected to dispatch.
        val validActions = setOf(
            ActionRegistry.Names.SESSION_CREATE,
            ActionRegistry.Names.SESSION_SET_ACTIVE_TAB,
            ActionRegistry.Names.SESSION_POST
        )
        fakeStore = FakeStore(AppState(), platform, validActions) // FIX: Use the platform dependency
    }

    private fun setViewState(state: SessionState) {
        fakeStore.setState(AppState(featureStates = mapOf("session" to state)))
        composeTestRule.setContent {
            AppTheme {
                SessionView(store = fakeStore, features = emptyList(), platformDependencies = platform) // FIX: Pass the required parameter
            }
        }
    }

    @Test
    fun `renders tabs for each session and shows active session content`() {
        setViewState(SessionState(
            sessions = mapOf(session1.id to session1, session2.id to session2),
            activeSessionId = session1.id
        ))

        composeTestRule.onNodeWithText("Session One").assertIsSelected()
        composeTestRule.onNodeWithText("Session Two").assertIsNotSelected()
        // Check for content specific to the active session view
        composeTestRule.onNodeWithText("Enter message (Ctrl+Enter to send)...").assertIsDisplayed()
    }

    @Test
    fun `clicking a tab dispatches SET_ACTIVE_TAB`() {
        setViewState(SessionState(
            sessions = mapOf(session1.id to session1, session2.id to session2),
            activeSessionId = session1.id
        ))

        composeTestRule.onNodeWithText("Session Two").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_SET_ACTIVE_TAB }
        assertNotNull(action)
        assertEquals("session.ui", action.originator)
        assertEquals(session2.id, action.payload?.get("session").toString().trim('"'))
    }

    @Test
    fun `sending a message dispatches SESSION_POST`() {
        setViewState(SessionState(
            sessions = mapOf(session1.id to session1),
            activeSessionId = session1.id
        ))

        composeTestRule.onNodeWithText("Enter message (Ctrl+Enter to send)...").performTextInput("Hello, world!")
        composeTestRule.onNodeWithContentDescription("Send").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_POST }
        assertNotNull(action)
        assertEquals("session.ui", action.originator)
        assertEquals(session1.id, action.payload?.get("session").toString().trim('"'))
        assertEquals("user", action.payload?.get("senderId").toString().trim('"'))
        assertEquals("Hello, world!", action.payload?.get("message").toString().trim('"'))
    }
}