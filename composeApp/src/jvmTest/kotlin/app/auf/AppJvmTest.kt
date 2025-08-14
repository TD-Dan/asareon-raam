package app.auf

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Rule
import kotlin.test.Test

/**
 * UI test suite for the root App Composable.
 * This test belongs in a platform-specific source set (jvmTest) because it
 * needs to render a real UI using createComposeRule. It verifies the top-level
 * routing logic.
 */
class AppJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // A minimal fake StateManager for controlling the state in tests.
    // We use a MutableStateFlow to update the state and trigger recomposition.
    class FakeStateManager(initialState: AppState) : StateManager("", UserSettings()) {
        // Override the state to control it directly in tests
        private val _testState = MutableStateFlow(initialState)
        override val state: StateFlow<AppState> = _testState.asStateFlow()

        fun setState(newState: AppState) {
            _testState.value = newState
        }
    }

    @Test
    fun `App shows Loading screen when status is LOADING`() {
        // Arrange
        val initialState = AppState(gatewayStatus = GatewayStatus.LOADING)
        val stateManager = FakeStateManager(initialState)

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
        val stateManager = FakeStateManager(initialState)


        // Act
        composeTestRule.setContent {
            App(stateManager)
        }

        // Assert
        composeTestRule.onNodeWithText("Test Error Message").assertExists()
    }
}