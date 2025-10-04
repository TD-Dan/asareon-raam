package app.auf.feature.filesystem

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.auf.core.Action
import app.auf.core.AppState
import app.auf.fakes.FakePlatformDependencies
import app.auf.fakes.FakeStore
import app.auf.ui.AppTheme
import app.auf.util.FileEntry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        // Initialize the store with an empty state
        fakeStore = FakeStore(AppState(), fakePlatform)
    }

    private fun setViewState(state: FileSystemState) {
        val appState = AppState(featureStates = mapOf(feature.name to state))
        fakeStore.setState(appState)

        // Set the content for the test rule inside the theme
        composeTestRule.setContent {
            AppTheme {
                FileSystemView(store = fakeStore)
            }
        }
    }

    @Test
    fun `view displays current path correctly`() {
        // Arrange
        val state = FileSystemState(currentPath = "/fake/user/home/documents")
        setViewState(state)

        // Assert
        composeTestRule.onNodeWithText("/fake/user/home/documents").assertIsDisplayed()
    }

    @Test
    fun `view displays empty message when listing is empty and path is set`() {
        // Arrange
        val state = FileSystemState(
            currentPath = "/fake/user/home/empty_dir",
            currentDirectoryListing = emptyList()
        )
        setViewState(state)

        // Assert
        composeTestRule.onNodeWithText("Directory is empty or inaccessible.").assertIsDisplayed()
    }

    @Test
    fun `view renders file and directory entries from state`() {
        // Arrange
        val state = FileSystemState(
            currentPath = "/fake/user/home",
            currentDirectoryListing = listOf(
                FileEntry("/fake/user/home/Documents", true),
                FileEntry("/fake/user/home/notes.txt", false)
            )
        )
        setViewState(state)

        // Assert
        composeTestRule.onNodeWithText("Documents").assertIsDisplayed()
        composeTestRule.onNodeWithText("notes.txt").assertIsDisplayed()
    }

    @Test
    fun `clicking a directory dispatches a NAVIGATE action with the correct path`() {
        // Arrange
        val directoryEntry = FileEntry("/fake/user/home/Documents", true)
        val state = FileSystemState(
            currentPath = "/fake/user/home",
            currentDirectoryListing = listOf(directoryEntry)
        )
        setViewState(state)

        // Act
        composeTestRule.onNodeWithText("Documents").performClick()

        // Assert
        val dispatchedAction = fakeStore.dispatchedActions.last()
        assertEquals("filesystem.NAVIGATE", dispatchedAction.name)
        assertEquals(
            "/fake/user/home/Documents",
            dispatchedAction.payload?.get("path")?.toString()?.trim('"')
        )
    }

    @Test
    fun `clicking a file does NOT dispatch a NAVIGATE action`() {
        // Arrange
        val fileEntry = FileEntry("/fake/user/home/notes.txt", false)
        val state = FileSystemState(
            currentPath = "/fake/user/home",
            currentDirectoryListing = listOf(fileEntry)
        )
        setViewState(state)
        fakeStore.dispatchedActions.clear() // Clear any initial actions

        // Act
        composeTestRule.onNodeWithText("notes.txt").performClick()

        // Assert
        assertTrue(
            fakeStore.dispatchedActions.none { it.name == "filesystem.NAVIGATE" },
            "Clicking a file should not dispatch a NAVIGATE action."
        )
    }
}