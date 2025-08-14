package app.auf

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UI test suite for the ImportView Composable.
 * ---
 * ARCHITECTURAL NOTE: This test has been updated to reflect the refactoring
 * of the import logic into a dedicated ImportExportViewModel. It now tests
 * the view against a fake ViewModel, ensuring proper isolation and adherence
 * to our hardened architecture.
 */
class ImportViewJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // A fake implementation of the ViewModel to control state and verify interactions.
    class FakeImportExportViewModel(initialState: ImportState?) : ImportExportViewModel(
        // --- FIX: The constructor no longer requires a callback ---
        importExportManager = FakeImportExportManager()
    ) {
        // Expose the state for the test to control.
        private val _testState = MutableStateFlow(initialState)
        override val importState = _testState.asStateFlow()

        // Spy properties to track if methods were called.
        var analyzeFolderCalled = false
        var executeImportCalled = false
        var updateImportActionCalledWith: Pair<String, ImportAction>? = null
        var cancelImportCalled = false


        override fun analyzeFolder(sourcePath: String, currentGraph: List<HolonHeader>) {
            analyzeFolderCalled = true
        }

        override fun executeImport(currentGraph: List<HolonHeader>, personaId: String, holonsBasePath: String) {
            executeImportCalled = true
        }

        override fun updateImportAction(sourceFilePath: String, newAction: ImportAction) {
            updateImportActionCalledWith = sourceFilePath to newAction
        }

        override fun cancelImport() {
            cancelImportCalled = true
        }
    }


    @Test
    fun `when importState is null, the view is not displayed`() {
        // Arrange
        val fakeViewModel = FakeImportExportViewModel(null)

        // Act
        composeTestRule.setContent {
            ImportView(
                viewModel = fakeViewModel,
                currentGraph = emptyList(),
                personaId = "test-persona",
                holonsBasePath = "/test/path"
            )
        }

        // Assert
        // We check that some prominent UI element is *not* present.
        composeTestRule.onNodeWithText("Analyze Folder").assertDoesNotExist()
    }


    @Test
    fun `when items exist, they are displayed with correctly parsed filenames`() {
        // Arrange
        val importStateWithItems = ImportState(
            sourcePath = "C:/test",
            items = listOf(
                ImportItem("C:/test/holon-a.json", Ignore()),
                ImportItem("/linux/path/holon-b.json", Ignore())
            )
        )
        val fakeViewModel = FakeImportExportViewModel(importStateWithItems)

        // Act
        composeTestRule.setContent {
            ImportView(
                viewModel = fakeViewModel,
                currentGraph = emptyList(),
                personaId = "test-persona",
                holonsBasePath = "/test/path"
            )
        }

        // Assert
        composeTestRule.onNodeWithText("holon-a.json").assertExists()
        composeTestRule.onNodeWithText("holon-b.json").assertExists()
    }

    @Test
    fun `clicking Analyze Folder button calls ViewModel`() {
        // Arrange
        val fakeViewModel = FakeImportExportViewModel(ImportState("C:/some/path"))

        composeTestRule.setContent {
            ImportView(
                viewModel = fakeViewModel,
                currentGraph = emptyList(),
                personaId = "test-persona",
                holonsBasePath = "/test/path"
            )
        }

        // Act
        composeTestRule.onNodeWithText("Analyze Folder").performClick()

        // Assert
        assertTrue(fakeViewModel.analyzeFolderCalled)
    }

    @Test
    fun `clicking Execute Import button calls ViewModel`() {
        // Arrange
        val importStateWithItems = ImportState(
            sourcePath = "C:/test",
            items = listOf(ImportItem("C:/test/holon-a.json", Ignore()))
        )
        val fakeViewModel = FakeImportExportViewModel(importStateWithItems)

        composeTestRule.setContent {
            ImportView(
                viewModel = fakeViewModel,
                currentGraph = emptyList(),
                personaId = "test-persona",
                holonsBasePath = "/test/path"
            )
        }

        // Act
        composeTestRule.onNodeWithText("Execute Import").performClick()

        // Assert
        assertTrue(fakeViewModel.executeImportCalled)
    }

    @Test
    fun `selecting an action from dropdown calls ViewModel`() {
        // Arrange
        val itemPath = "C:/test/holon-a.json"
        val importStateWithItems = ImportState(
            sourcePath = "C:/test",
            items = listOf(ImportItem(itemPath, Update("holon-a")))
        )
        val fakeViewModel = FakeImportExportViewModel(importStateWithItems)


        composeTestRule.setContent {
            ImportView(
                viewModel = fakeViewModel,
                currentGraph = emptyList(),
                personaId = "test-persona",
                holonsBasePath = "/test/path"
            )
        }

        // Act
        // FIX: Click the dedicated, testable button by its content description.
        composeTestRule.onNodeWithContentDescription("Select Action").performClick()
        // Now that the menu is reliably open, click the desired item.
        composeTestRule.onNodeWithText("Do not import.").performClick()

        // Assert
        assertEquals(itemPath, fakeViewModel.updateImportActionCalledWith?.first)
        assertTrue(fakeViewModel.updateImportActionCalledWith?.second is Ignore)
    }

    @Test
    fun `clicking the close button calls ViewModel's cancelImport`() {
        // Arrange
        val fakeViewModel = FakeImportExportViewModel(ImportState("C:/some/path"))

        composeTestRule.setContent {
            ImportView(
                viewModel = fakeViewModel,
                currentGraph = emptyList(),
                personaId = "test-persona",
                holonsBasePath = "/test/path"
            )
        }

        // Act
        // The previous fix for this test was correct. We find by content description.
        composeTestRule.onNodeWithContentDescription("Close").performClick()

        // Assert
        assertTrue(fakeViewModel.cancelImportCalled)
    }
}