package app.auf

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Rule
import org.junit.Test

/**
 * UI test suite for the root App Composable.
 * This test suite has been refactored to use proper Dependency Injection.
 * We now test the real `StateManager` but inject it with "Fake" versions
 * of its dependencies to prevent network calls and file I/O.
 */
class AppJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testScope = TestCoroutineScope()

    @Test
    fun `App shows Loading screen when status is LOADING`() = testScope.runBlockingTest {
        // Arrange: Create a real StateManager, but with fake dependencies.
        val stateManager = StateManager(
            gatewayManager = FakeGatewayManager(),
            backupManager = FakeBackupManager,
            graphLoader = GraphLoader("holons", JsonProvider.appJson), // This is safe, no side effects
            actionExecutor = ActionExecutor(JsonProvider.appJson), // This is also safe
            // --- FIX: Instantiate the ViewModel correctly without the trailing lambda ---
            importExportViewModel = ImportExportViewModel(FakeImportExportManager(), testScope),
            initialSettings = UserSettings(),
            coroutineScope = testScope
        )

        // Set the specific state needed for this test
        stateManager.updateStateForTesting(AppState(gatewayStatus = GatewayStatus.LOADING))

        // Act
        composeTestRule.setContent {
            AppScreen(stateManager)
        }

        // Assert
        composeTestRule.onNodeWithText("Loading Knowledge Graph...").assertExists()
    }

    @Test
    fun `App shows Error screen when status is ERROR`() = testScope.runBlockingTest {
        // Arrange
        val stateManager = StateManager(
            gatewayManager = FakeGatewayManager(),
            backupManager = FakeBackupManager,
            graphLoader = GraphLoader("holons", JsonProvider.appJson),
            actionExecutor = ActionExecutor(JsonProvider.appJson),
            // --- FIX: Instantiate the ViewModel correctly without the trailing lambda ---
            importExportViewModel = ImportExportViewModel(FakeImportExportManager(), testScope),
            initialSettings = UserSettings(),
            coroutineScope = testScope
        )

        // Set the specific state needed for this test
        stateManager.updateStateForTesting(
            AppState(
                gatewayStatus = GatewayStatus.ERROR,
                errorMessage = "Test Error Message"
            )
        )

        // Act
        composeTestRule.setContent {
            AppScreen(stateManager)
        }

        // Assert
        composeTestRule.onNodeWithText("Test Error Message").assertExists()
    }
}