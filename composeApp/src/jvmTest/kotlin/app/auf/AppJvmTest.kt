package app.auf

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * UI test suite for the root App Composable.
 *
 * ---
 * ## Mandate
 * This test suite has been refactored to use proper Dependency Injection.
 * We now test the real `StateManager` but inject it with "Fake" versions
 * of its dependencies to prevent network calls and file I/O.
 *
 * ---
 * ## Dependencies
 * - `app.auf.App`
 * - `app.auf.StateManager`
 * - All `Fake` manager implementations.
 *
 * @version 2.1
 * @since 2025-08-14
 */
class AppJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // --- FIX: Use a specific test dispatcher for better control ---
    private val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
    private val testScope = CoroutineScope(testDispatcher)


    // Helper function to create a StateManager with fake dependencies for testing.
    private fun createTestStateManager(): StateManager {
        // --- FIX: Use the corrected FakeImportExportManager ---
        val fakeImportExportManager = FakeImportExportManager()
        val importExportViewModel = ImportExportViewModel(
            importExportManager = fakeImportExportManager,
            coroutineScope = testScope
        )
        // Set the callback after instantiation, just like in main.kt
        importExportViewModel.onImportComplete = { }

        // --- NOTE: This test setup is still inconsistent. GraphLoader and ActionExecutor are real.
        // This is acceptable for now but should be addressed in a future hardening task.
        return StateManager(
            gatewayManager = FakeGatewayManager(),
            backupManager = FakeBackupManager(),
            graphLoader = GraphLoader("holons", JsonProvider.appJson), // REAL
            actionExecutor = ActionExecutor(JsonProvider.appJson),     // REAL
            importExportViewModel = importExportViewModel,
            initialSettings = UserSettings(),
            coroutineScope = testScope
        )
    }

    @Test
    fun `App shows Loading screen when status is LOADING`() = runTest {
        // Arrange
        val stateManager = createTestStateManager()

        // Set the specific state needed for this test
        stateManager.updateStateForTesting(AppState(gatewayStatus = GatewayStatus.LOADING))

        // Act
        composeTestRule.setContent {
            App(stateManager)
        }

        // Assert
        composeTestRule.onNodeWithText("Loading Knowledge Graph...").assertExists()
    }

    @Test
    fun `App shows Error screen when status is ERROR`() = runTest {
        // Arrange
        val stateManager = createTestStateManager()

        // Set the specific state needed for this test
        stateManager.updateStateForTesting(
            AppState(
                gatewayStatus = GatewayStatus.ERROR,
                errorMessage = "Test Error Message"
            )
        )

        // Act
        composeTestRule.setContent {
            App(stateManager)
        }

        // Assert
        composeTestRule.onNodeWithText("Test Error Message").assertExists()
    }
}