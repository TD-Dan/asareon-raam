package app.auf.feature.session

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.Identity
import app.auf.core.generated.ActionRegistry
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
    private val platform = FakePlatformDependencies("test")
    private val testIdentity = Identity(uuid = "00000000-0000-4000-a000-000000000001", localHandle = "sid-1", handle = "session.sid-1", name = "Test", parentHandle = "session")
    private val session = Session(identity = testIdentity, ledger = emptyList(), createdAt = 1L)
    private val userEntry = LedgerEntry("msg-1", 1L, "user", "Hello World")

    @Before
    fun setup() {
        fakeStore = FakeStore(AppState(), platform)
    }

    private fun setViewState(entry: LedgerEntry, isEditing: Boolean = false, editingContent: String? = null) {
        val sessionState = SessionState(
            sessions = mapOf(session.identity.localHandle to session.copy(ledger = listOf(entry))),
            editingMessageId = if (isEditing) entry.id else null,
            editingMessageContent = editingContent
        )
        fakeStore.setState(AppState(featureStates = mapOf("session" to sessionState)))

        composeTestRule.setContent {
            AppTheme {
                LedgerEntryCard(
                    store = fakeStore,
                    session = session,
                    entry = entry,
                    senderName = "User",
                    isCurrentUserMessage = true,
                    isEditingThisMessage = isEditing,
                    editingContent = editingContent,
                    platformDependencies = platform
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

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.CORE_COPY_TO_CLIPBOARD }
        assertNotNull(action)
        assertEquals("Hello World", action.payload?.get("text").toString().trim('"'))
    }

    @Test
    fun `selecting delete from menu dispatches SESSION_DELETE_MESSAGE`() {
        setViewState(userEntry)

        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Delete").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_DELETE_MESSAGE }
        assertNotNull(action)
        assertEquals(session.identity.localHandle, action.payload?.get("session").toString().trim('"'))
        assertEquals(userEntry.id, action.payload?.get("messageId").toString().trim('"'))
    }
}