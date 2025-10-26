
package app.auf.feature.session

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.generated.ActionNames
import app.auf.fakes.FakePlatformDependencies
import app.auf.fakes.FakeStore
import app.auf.ui.AppTheme
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tier 1 Component Test for LedgerEntryCard.
 *
 * Mandate (P-TEST-001, T1): To test this complex UI component's rendering logic and
 * action dispatching in isolation, using a FakeStore.
 */
class SessionFeatureT1LedgerEntryCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var fakeStore: FakeStore
    private val platform = FakePlatformDependencies("test") // FIX: Instantiate platform dependencies for the test
    private val session = Session("sid-1", "Test", emptyList(), 1L)
    private val userEntry = LedgerEntry("msg-1", 1L, "user", "Hello World")

    @Before
    fun setup() {
        val validActions = setOf(
            ActionNames.SESSION_DELETE_MESSAGE,
            ActionNames.SESSION_SET_EDITING_MESSAGE,
            ActionNames.CORE_COPY_TO_CLIPBOARD
        )
        fakeStore = FakeStore(AppState(), platform, validActions) // FIX: Use the platform dependency
    }

    private fun setViewState(entry: LedgerEntry, isEditing: Boolean = false, editingContent: String? = null) {
        val sessionState = SessionState(
            sessions = mapOf(session.id to session.copy(ledger = listOf(entry))),
            editingMessageId = if (isEditing) entry.id else null,
            editingMessageContent = editingContent
        )
        fakeStore.setState(AppState(featureStates = mapOf("session" to sessionState)))

        composeTestRule.setContent {
            AppTheme {
                // THE FIX: Align the component call with the new, refactored signature.
                LedgerEntryCard(
                    store = fakeStore,
                    session = session,
                    entry = entry,
                    senderName = "User", // Pass the universal sender name
                    isCurrentUserMessage = true, // Pass the new boolean flag
                    isEditingThisMessage = isEditing,
                    editingContent = editingContent,
                    platformDependencies = platform // FIX: Pass the required parameter
                )
            }
        }
    }

    @Test
    fun `renders sender name and content`() {
        setViewState(userEntry)

        composeTestRule.onNodeWithText("User").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hello World").assertIsDisplayed()
    }

    @Test
    fun `clicking copy button dispatches CORE_COPY_TO_CLIPBOARD`() {
        setViewState(userEntry)

        composeTestRule.onNodeWithContentDescription("Copy Message Content").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionNames.CORE_COPY_TO_CLIPBOARD }
        assertNotNull(action)
        assertEquals("Hello World", action.payload?.get("text").toString().trim('"'))
    }

    @Test
    fun `selecting delete from menu dispatches SESSION_DELETE_MESSAGE`() {
        setViewState(userEntry)

        composeTestRule.onNodeWithContentDescription("More options").performClick()
        // Wait for menu to appear
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Delete").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionNames.SESSION_DELETE_MESSAGE }
        assertNotNull(action)
        assertEquals(session.id, action.payload?.get("session").toString().trim('"'))
        assertEquals(userEntry.id, action.payload?.get("messageId").toString().trim('"'))
    }
}