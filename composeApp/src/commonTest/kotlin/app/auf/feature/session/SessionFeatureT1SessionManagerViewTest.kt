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
        assertEquals("session.ui", action.originator)
    }
}