package app.auf.feature.settings

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.generated.ActionNames
import app.auf.fakes.FakePlatformDependencies
import app.auf.fakes.FakeStore
import app.auf.ui.AppTheme
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tier 1 Component Test for SettingsView.
 *
 * Mandate (P-TEST-001, T1): To test the UI component's rendering and action dispatching
 * in isolation, using a FakeStore to intercept dispatched actions.
 */
class SettingsFeatureT1SettingsViewTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var fakePlatform: FakePlatformDependencies
    private lateinit var fakeStore: FakeStore

    @Before
    fun setUp() {
        fakePlatform = FakePlatformDependencies("test")
        // Use a restricted action registry to ensure the view only dispatches what it's supposed to.
        val validActions = setOf(
            ActionNames.SETTINGS_OPEN_FOLDER,
            ActionNames.SETTINGS_UI_INTERNAL_INPUT_CHANGED,
            ActionNames.SETTINGS_UPDATE
        )
        fakeStore = FakeStore(AppState(), fakePlatform, validActions)
    }

    private fun setViewState(state: SettingsState) {
        val appState = AppState(featureStates = mapOf("settings" to state))
        fakeStore.setState(appState)

        composeTestRule.setContent {
            AppTheme {
                SettingsView(store = fakeStore, onClose = {})
            }
        }
    }

    @Test
    fun `UI controls display the current value from the store`() = runTest {
        val definitions = listOf(
            buildJsonObject {
                put("key", "test.api.key"); put("type", "STRING"); put("label", "API Key")
                put("description", ""); put("section", "API"); put("defaultValue", "")
            }
        )
        val state = SettingsState(definitions = definitions, inputValues = mapOf("test.api.key" to "current_value"))
        setViewState(state)

        composeTestRule.onNodeWithText("current_value").assertIsDisplayed()
    }

    @Test
    fun `editing text field dispatches INPUT_CHANGED`() = runTest {
        val definitions = listOf(
            buildJsonObject {
                put("key", "test.api.key"); put("type", "STRING"); put("label", "API Key")
                put("description", ""); put("section", "API"); put("defaultValue", "old_value")
            }
        )
        setViewState(SettingsState(definitions = definitions, inputValues = mapOf("test.api.key" to "old_value")))

        composeTestRule.onNodeWithText("old_value").performTextReplacement("new_key")

        val action = fakeStore.dispatchedActions.find { it.name == ActionNames.SETTINGS_UI_INTERNAL_INPUT_CHANGED }
        assertNotNull(action)
        assertEquals("settings.ui", action.originator)
        assertEquals("test.api.key", action.payload?.get("key")?.jsonPrimitive?.content)
        assertEquals("new_key", action.payload?.get("value")?.jsonPrimitive?.content)
    }
}