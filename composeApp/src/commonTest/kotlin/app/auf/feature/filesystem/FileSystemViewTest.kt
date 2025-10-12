package app.auf.feature.filesystem

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.auf.core.Action
import app.auf.core.AppState
import app.auf.fakes.FakePlatformDependencies
import app.auf.fakes.FakeStore
import app.auf.ui.AppTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FileSystemViewTest {

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
        // THE FIX: Provide a fake registry to the FakeStore constructor.
        val validActions = setOf("filesystem.TOGGLE_ITEM_SELECTED")
        fakeStore = FakeStore(AppState(), fakePlatform, validActions)
    }

    private fun setViewState(state: FileSystemState) {
        val appState = AppState(featureStates = mapOf(feature.name to state))
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
    fun `view displays empty message when listing is empty and path is set`() {
        val state = FileSystemState(
            currentPath = "/fake/user/home/empty_dir",
            rootItems = emptyList()
        )
        setViewState(state)

        composeTestRule.onNodeWithText("Directory is empty.").assertIsDisplayed()
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

        val dispatchedAction = fakeStore.dispatchedActions.find { it.name == "filesystem.TOGGLE_ITEM_SELECTED" }

        assertNotNull(dispatchedAction, "A TOGGLE_ITEM_SELECTED action should have been dispatched.")
        assertEquals("filesystem.ui", dispatchedAction.originator)
        assertEquals(
            filePath,
            dispatchedAction.payload?.get("path")?.toString()?.trim('"')
        )
    }
}