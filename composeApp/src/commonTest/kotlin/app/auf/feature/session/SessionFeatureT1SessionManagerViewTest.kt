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
    private val session1 = Session("sid-1", "My Session", emptyList(), 1L)

    @Before
    fun setup() {
        val validActions = setOf(
            ActionRegistry.Names.SESSION_CREATE,
            ActionRegistry.Names.SESSION_DELETE,
            ActionRegistry.Names.SESSION_SET_EDITING_SESSION_NAME
        )
        fakePlatform = FakePlatformDependencies("test")
        fakeStore = FakeStore(AppState(), fakePlatform, validActions)
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
        setViewState(SessionState(sessions = mapOf(session1.id to session1)))

        composeTestRule.onNodeWithText("My Session").assertIsDisplayed()
        composeTestRule.onNodeWithText("ID: sid-1").assertIsDisplayed()
    }

    @Test
    fun `clicking delete button dispatches SESSION_DELETE`() {
        setViewState(SessionState(sessions = mapOf(session1.id to session1)))

        composeTestRule.onNodeWithContentDescription("Delete Session").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_DELETE }
        assertNotNull(action)
        assertEquals("session.ui", action.originator)
        assertEquals(session1.id, action.payload?.get("session").toString().trim('"'))
    }

    @Test
    fun `clicking new session button dispatches SESSION_CREATE`() {
        setViewState(SessionState())

        composeTestRule.onNodeWithText("New Session").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_CREATE }
        assertNotNull(action)
        assertEquals("session.ui", action.originator)
    }
}