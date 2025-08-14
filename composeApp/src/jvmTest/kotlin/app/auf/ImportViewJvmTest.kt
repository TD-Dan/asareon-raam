package app.auf

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UI test suite for the ImportView Composable.
 * As per the System Hardening Protocol, this test verifies that the UI
 * correctly renders the application state and that user interactions are
 * correctly propagated to the state management layer.
 */
class ImportViewTest {

    // createComposeRule is the standard tool for testing Compose UIs.
    @get:Rule
    val composeTestRule = createComposeRule()

    // A fake implementation of StateManager to test interactions in isolation.
    class FakeStateManager : StateManager(
        // We can use dummy/default values since we are not testing the StateManager's logic itself.
        userSettings = UserSettings(),
        gateway = Gateway(UserSettings()),
        graphLoader = GraphLoader(""),
        importExportManager = ImportExportManager("", JsonProvider.appJson),
        actionExecutor = ActionExecutor(""),
        backupManager = BackupManager("")
    ) {
        var analyzeImportFolderCalledWith: String? = null
        var executeImportCalled = false
        var selectImportActionCalledWith: Pair<String, ImportAction>? = null

        override fun analyzeImportFolder(path: String) {
            analyzeImportFolderCalledWith = path
        }

        override fun executeImport() {
            executeImportCalled = true
        }

        override fun selectImportAction(itemPath: String, action: ImportAction) {
            selectImportActionCalledWith = itemPath to action
        }
    }

    @Test
    fun `when importState is empty, it displays the initial prompt`() {
        // Arrange
        val fakeStateManager = FakeStateManager()
        val emptyImportState = ImportState(sourcePath = "C:/test")

        // Act
        composeTestRule.setContent {
            ImportView(importState = emptyImportState, stateManager = fakeStateManager, onClose = {})
        }

        // Assert
        composeTestRule.onNodeWithText("No importable files found. Please analyze a folder.").assertExists()
    }

    @Test
    fun `when items exist, they are displayed with correctly parsed filenames`() {
        // Arrange
        val fakeStateManager = FakeStateManager()
        val importStateWithItems = ImportState(
            sourcePath = "C:/test",
            items = listOf(
                ImportItem("C:/test/holon-a.json", Ignore()),
                ImportItem("/linux/path/holon-b.json", Ignore()) // Test different path separator
            )
        )

        // Act
        composeTestRule.setContent {
            ImportView(importState = importStateWithItems, stateManager = fakeStateManager, onClose = {})
        }

        // Assert
        // This directly verifies our bug fix: the view correctly finds and displays the filename.
        composeTestRule.onNodeWithText("holon-a.json").assertExists()
        composeTestRule.onNodeWithText("holon-b.json").assertExists()
    }

    @Test
    fun `clicking Analyze Folder button calls StateManager`() {
        // Arrange
        val fakeStateManager = FakeStateManager()
        val emptyImportState = ImportState(sourcePath = "C:/some/path")

        composeTestRule.setContent {
            ImportView(importState = emptyImportState, stateManager = fakeStateManager, onClose = {})
        }

        // Act
        composeTestRule.onNodeWithText("Analyze Folder").performClick()

        // Assert
        assertEquals("C:/some/path", fakeStateManager.analyzeImportFolderCalledWith)
    }

    @Test
    fun `clicking Execute Import button calls StateManager`() {
        // Arrange
        val fakeStateManager = FakeStateManager()
        val importStateWithItems = ImportState(
            sourcePath = "C:/test",
            items = listOf(ImportItem("C:/test/holon-a.json", Ignore()))
        )

        composeTestRule.setContent {
            ImportView(importState = importStateWithItems, stateManager = fakeStateManager, onClose = {})
        }

        // Act
        composeTestRule.onNodeWithText("Execute Import").performClick()

        // Assert
        assertTrue(fakeStateManager.executeImportCalled)
    }

    @Test
    fun `selecting an action from dropdown calls StateManager`() {
        // Arrange
        val fakeStateManager = FakeStateManager()
        val itemPath = "C:/test/holon-a.json"
        val importStateWithItems = ImportState(
            sourcePath = "C:/test",
            items = listOf(ImportItem(itemPath, Update("holon-a")))
        )

        composeTestRule.setContent {
            ImportView(importState = importStateWithItems, stateManager = fakeStateManager, onClose = {})
        }

        // Act
        // 1. Find the dropdown (which is an OutlinedTextField) associated with the "Update" action and click it.
        composeTestRule.onNodeWithText("Update existing holon.").performClick()
        // 2. Find the "Ignore" option in the now-visible menu and click it.
        composeTestRule.onNodeWithText("Ignore").performClick()

        // Assert
        assertEquals(itemPath, fakeStateManager.selectImportActionCalledWith?.first)
        assertTrue(fakeStateManager.selectImportActionCalledWith?.second is Ignore)
    }
}