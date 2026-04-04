package asareon.raam.feature.filesystem

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import asareon.raam.core.Action
import asareon.raam.core.AppState
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
 * Tier 1 Component Test for FileSystemView.
 *
 * Mandate (P-TEST-001, T1): To test the UI component's rendering and action dispatching
 * in isolation, using a FakeStore to intercept dispatched actions.
 *
 * Phase 2.1 FIX: FakeStore constructor no longer accepts validActionNames: Set<String>.
 * The Store now validates actions against AppState.actionDescriptors, which is
 * pre-populated from ActionRegistry.byActionName by default.
 */
class FileSystemFeatureT1ViewComponentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testAppVersion = "2.0.0-test"
    private lateinit var fakePlatform: FakePlatformDependencies
    private lateinit var fakeStore: FakeStore
    private lateinit var feature: FileSystemFeature

    @Before
    fun setUp() {
        fakePlatform = FakePlatformDependencies(testAppVersion)
        feature = FileSystemFeature(fakePlatform)
        // Phase 2.1 FIX: FakeStore's 3rd parameter is now List<Feature>, not Set<String>.
        // Action validation is handled via AppState.actionDescriptors (defaults to ActionRegistry.byActionName).
        // FakeStore overrides dispatch() to capture without calling super, so no validation occurs anyway.
        fakeStore = FakeStore(AppState(), fakePlatform)
    }

    private fun setViewState(state: FileSystemState) {
        val appState = AppState(featureStates = mapOf(feature.identity.handle to state))
        fakeStore.setState(appState)

        composeTestRule.setContent {
            AppTheme {
                FileSystemView(
                    store = fakeStore,
                    platformDependencies = fakePlatform
                )
            }
        }
    }

    @Test
    fun `view displays current path correctly`() {
        val state = FileSystemState(currentPath = "/fake/user/home/documents")
        setViewState(state)

        composeTestRule.onNodeWithText("/fake/user/home/documents").assertIsDisplayed()
    }

    @Test
    fun `view renders file and directory entries from state`() {
        val state = FileSystemState(
            currentPath = "/fake/user/home",
            rootItems = listOf(
                FileSystemItem("/fake/user/home/Documents", "Documents", true),
                FileSystemItem("/fake/user/home/notes.txt", "notes.txt", false)
            )
        )
        setViewState(state)

        composeTestRule.onNodeWithText("Documents").assertIsDisplayed()
        composeTestRule.onNodeWithText("notes.txt").assertIsDisplayed()
    }

    @Test
    fun `clicking checkbox dispatches TOGGLE_ITEM_SELECTED action`() {
        val filePath = "/fake/home/file.txt"
        val state = FileSystemState(
            currentPath = "/fake/home",
            rootItems = listOf(FileSystemItem(filePath, "file.txt", false))
        )
        setViewState(state)
        fakeStore.dispatchedActions.clear()

        composeTestRule
            .onNodeWithTag("checkbox-$filePath")
            .performClick()

        val dispatchedAction = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_TOGGLE_ITEM_SELECTED }

        assertNotNull(dispatchedAction, "A TOGGLE_ITEM_SELECTED action should have been dispatched.")
        assertEquals("filesystem", dispatchedAction.originator)
        assertEquals(
            filePath,
            dispatchedAction.payload?.get("path")?.toString()?.trim('"')
        )
    }
}