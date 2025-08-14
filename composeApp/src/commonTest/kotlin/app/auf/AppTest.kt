package app.auf

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import kotlin.test.Test

/**
 * UI test suite for the root App Composable.
 * This test verifies the top-level routing logic, ensuring the correct
 * view is displayed based on the AppState.
 */
class AppTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // A minimal fake StateManager for controlling the state in tests.
    // We use a MutableStateFlow to update the state and trigger recomposition.
    class FakeStateManager(initialState: AppState) {
        val state = MutableStateFlow(initialState)
        fun setViewMode(viewMode: ViewMode) {
            state.value = state.value.copy(currentViewMode = viewMode)
        }
    }

    @Test
    fun `App shows Loading screen when status is LOADING`() {
        // Arrange
        val initialState = AppState(gatewayStatus = GatewayStatus.LOADING)
        val stateManager = StateManager(
            UserSettings(), Gateway(UserSettings()), GraphLoader(""),
            ImportExportManager("", JsonProvider.appJson), ActionExecutor(""), BackupManager("")
        ).apply { state.value = initialState }


        // Act
        composeTestRule.setContent {
            App(stateManager)
        }

        // Assert
        composeTestRule.onNodeWithText("Loading Knowledge Graph...").assertExists()
    }

    @Test
    fun `App shows Error screen when status is ERROR`() {
        // Arrange
        val initialState = AppState(gatewayStatus = GatewayStatus.ERROR, errorMessage = "Test Error Message")
        val stateManager = StateManager(
            UserSettings(), Gateway(UserSettings()), GraphLoader(""),
            ImportExportManager("", JsonProvider.appJson), ActionExecutor(""), BackupManager("")
        ).apply { state.value = initialState }

        // Act
        composeTestRule.setContent {
            App(stateManager)
        }

        // Assert
        composeTestRule.onNodeWithText("Test Error Message").assertExists()
    }

    @Test
    fun `App shows ChatView when view mode is CHAT`() {
        // Arrange
        val initialState = AppState(gatewayStatus = GatewayStatus.OK, currentViewMode = ViewMode.CHAT)
        val stateManager = StateManager(
            UserSettings(), Gateway(UserSettings()), GraphLoader(""),
            ImportExportManager("", JsonProvider.appJson), ActionExecutor(""), BackupManager("")
        ).apply { state.value = initialState }

        // Act
        composeTestRule.setContent {
            App(stateManager)
        }

        // Assert - We look for a unique element of the ChatView, like the "Send" button.
        composeTestRule.onNodeWithText("Send").assertExists()
    }

    @Test
    fun `App shows ExportView when view mode is EXPORT`() {
        // Arrange
        val initialState = AppState(gatewayStatus = GatewayStatus.OK, currentViewMode = ViewMode.EXPORT)
        val stateManager = StateManager(
            UserSettings(), Gateway(UserSettings()), GraphLoader(""),
            ImportExportManager("", JsonProvider.appJson), ActionExecutor(""), BackupManager("")
        ).apply { state.value = initialState }

        // Act
        composeTestRule.setContent {
            App(stateManager)
        }

        // Assert - We look for a unique element of the ExportView.
        composeTestRule.onNodeWithText("Export Holons").assertExists()
    }

    @Test
    fun `App shows ImportView when view mode is IMPORT and state exists`() {
        // Arrange
        val initialState = AppState(
            gatewayStatus = GatewayStatus.OK,
            currentViewMode = ViewMode.IMPORT,
            importState = ImportState(sourcePath = "") // The state must not be null
        )
        val stateManager = StateManager(
            UserSettings(), Gateway(UserSettings()), GraphLoader(""),
            ImportExportManager("", JsonProvider.appJson), ActionExecutor(""), BackupManager("")
        ).apply { state.value = initialState }

        // Act
        composeTestRule.setContent {
            App(stateManager)
        }

        // Assert - We look for a unique element of the ImportView.
        composeTestRule.onNodeWithText("Import Workbench").assertExists()
    }
}