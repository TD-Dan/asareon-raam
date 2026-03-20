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

    /**
     * Hovers the card to make the ChromeOverlay visible.
     *
     * LedgerEntryCard gates its action-icon overlay behind:
     *   val showChrome = isHovered || isMenuOpen || isEditingThisMessage
     * In a test environment none of those are true by default, so the Copy,
     * Toggle Raw, and More Options buttons are absent from the tree.
     * Performing a mouse-enter on the Card's hoverable surface triggers
     * collectIsHoveredAsState() → recomposition with showChrome = true.
     */
    private fun hoverCard() {
        composeTestRule.onNodeWithText("User").performMouseInput { enter() }
        composeTestRule.waitForIdle()
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
        hoverCard()

        composeTestRule.onNodeWithContentDescription("Copy Message Content").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.CORE_COPY_TO_CLIPBOARD }
        assertNotNull(action)
        assertEquals("Hello World", action.payload?.get("text").toString().trim('"'))
    }

    @Test
    fun `selecting delete from menu dispatches SESSION_DELETE_MESSAGE`() {
        setViewState(userEntry)
        hoverCard()

        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Delete").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_DELETE_MESSAGE }
        assertNotNull(action)
        assertEquals(session.identity.localHandle, action.payload?.get("session").toString().trim('"'))
        assertEquals(userEntry.id, action.payload?.get("messageId").toString().trim('"'))
    }

    // ─────────────────────────────────────────────────────────────
    // Lock indicator and lock/unlock menu
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `locked message displays lock icon`() {
        val lockedEntry = LedgerEntry("msg-locked", 1L, "user", "Locked content", isLocked = true)
        setViewState(lockedEntry)

        composeTestRule.onNodeWithContentDescription("Locked").assertIsDisplayed()
    }

    @Test
    fun `unlocked message does not display lock icon`() {
        setViewState(userEntry) // isLocked = false by default

        composeTestRule.onNodeWithContentDescription("Locked").assertDoesNotExist()
    }

    @Test
    fun `selecting Lock from menu dispatches TOGGLE_MESSAGE_LOCKED`() {
        setViewState(userEntry)
        hoverCard()

        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Lock").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_TOGGLE_MESSAGE_LOCKED }
        assertNotNull(action)
        assertEquals(session.identity.localHandle, action.payload?.get("sessionId").toString().trim('"'))
        assertEquals(userEntry.id, action.payload?.get("messageId").toString().trim('"'))
    }

    @Test
    fun `locked message shows Unlock menu item`() {
        val lockedEntry = LedgerEntry("msg-locked", 1L, "user", "Locked", isLocked = true)
        setViewState(lockedEntry)
        hoverCard()

        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Unlock").assertIsDisplayed()
    }

    // ─────────────────────────────────────────────────────────────
    // Edit menu — dispatching and lock guard
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `selecting Edit from menu dispatches SET_EDITING_MESSAGE`() {
        setViewState(userEntry)
        hoverCard()

        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Edit").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_SET_EDITING_MESSAGE }
        assertNotNull(action)
        assertEquals(userEntry.id, action.payload?.get("messageId").toString().trim('"'))
    }

    @Test
    fun `Edit menu item is disabled when message is locked`() {
        val lockedEntry = LedgerEntry("msg-locked", 1L, "user", "Locked", isLocked = true)
        setViewState(lockedEntry)
        hoverCard()

        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Edit").assertIsNotEnabled()
    }

    @Test
    fun `Delete menu item is disabled when message is locked`() {
        val lockedEntry = LedgerEntry("msg-locked", 1L, "user", "Locked", isLocked = true)
        setViewState(lockedEntry)
        hoverCard()

        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Delete").assertIsNotEnabled()
    }

    // ─────────────────────────────────────────────────────────────
    // Edit mode rendering
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `in edit mode shows editor with Save and Cancel buttons`() {
        setViewState(userEntry, isEditing = true, editingContent = "Hello World")

        composeTestRule.onNodeWithText("Save").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun `clicking Save in edit mode dispatches SESSION_UPDATE_MESSAGE`() {
        setViewState(userEntry, isEditing = true, editingContent = "Hello World")

        composeTestRule.onNodeWithText("Save").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_UPDATE_MESSAGE }
        assertNotNull(action)
        assertEquals(session.identity.localHandle, action.payload?.get("session").toString().trim('"'))
        assertEquals(userEntry.id, action.payload?.get("messageId").toString().trim('"'))
    }

    @Test
    fun `clicking Cancel in edit mode dispatches SET_EDITING_MESSAGE with null`() {
        setViewState(userEntry, isEditing = true, editingContent = "Hello World")

        composeTestRule.onNodeWithText("Cancel").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_SET_EDITING_MESSAGE }
        assertNotNull(action)
        // messageId should be null to cancel editing
        assertEquals("null", action.payload?.get("messageId").toString())
    }

    // ─────────────────────────────────────────────────────────────
    // Raw view toggle
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `clicking Toggle Raw Content dispatches TOGGLE_MESSAGE_RAW_VIEW`() {
        setViewState(userEntry)
        hoverCard()

        composeTestRule.onNodeWithContentDescription("Toggle Raw Content").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_TOGGLE_MESSAGE_RAW_VIEW }
        assertNotNull(action)
        assertEquals(session.identity.localHandle, action.payload?.get("sessionId").toString().trim('"'))
        assertEquals(userEntry.id, action.payload?.get("messageId").toString().trim('"'))
    }

    // ─────────────────────────────────────────────────────────────
    // Metadata-only entries (null rawContent)
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `metadata-only entry with null rawContent disables copy button`() {
        val metadataEntry = LedgerEntry(
            "msg-meta", 1L, "agent",
            rawContent = null,
            metadata = buildJsonObject { put("type", "avatar_card") }
        )
        setViewState(metadataEntry)
        hoverCard()

        composeTestRule.onNodeWithContentDescription("Copy Message Content").assertIsNotEnabled()
    }

    @Test
    fun `metadata-only entry does not show Edit in menu`() {
        val metadataEntry = LedgerEntry(
            "msg-meta", 1L, "agent",
            rawContent = null,
            metadata = buildJsonObject { put("type", "avatar_card") }
        )
        setViewState(metadataEntry)
        hoverCard()

        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.waitForIdle()
        // Edit should not appear when rawContent is null
        composeTestRule.onNodeWithText("Edit").assertDoesNotExist()
    }
}