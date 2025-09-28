package app.auf.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.auf.core.Action
import app.auf.core.AppState
import app.auf.fakes.FakePlatformDependencies
import app.auf.fakes.FakeStore
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SettingsViewTest {

    // The JUnit 4 Rule that provides the environment to test Compose UIs.
    @get:Rule
    val composeTestRule = createComposeRule()

    private val testAppVersion = "2.0.0-test"
    private lateinit var fakePlatform: FakePlatformDependencies
    private lateinit var fakeStore: FakeStore
    private lateinit var settingsFeature: SettingsFeature

    @Before
    fun setUp() {
        // --- ARRANGE ---
        // 1. Create dependencies
        fakePlatform = FakePlatformDependencies(testAppVersion)
        settingsFeature = SettingsFeature(fakePlatform)

        // 2. Simulate other features registering their settings by applying the reducer
        val addAction1 = Action("settings.ADD", buildJsonObject {
            put("key", "core.window.width")
            put("type", "NUMERIC_LONG")
            put("label", "Window Width")
            put("description", "The width of the application window.")
            put("section", "Appearance")
            put("defaultValue", "1200")
        })
        val addAction2 = Action("settings.ADD", buildJsonObject {
            put("key", "agent.enable.ai")
            put("type", "BOOLEAN")
            put("label", "Enable AI Agent")
            put("description", "Allow the AI agent to run.")
            put("section", "Agent")
            put("defaultValue", "true")
        })
        val stateAfterAdding = settingsFeature.reducer(settingsFeature.reducer(AppState(), addAction1), addAction2)

        // 3. Simulate loading a previously saved value that overrides a default
        val stateAfterLoading = settingsFeature.reducer(
            stateAfterAdding,
            Action("settings.LOADED", buildJsonObject {
                put("core.window.width", "1024") // Override default
                // 'agent.enable.ai' is not present, so its default 'true' will be used
            })
        )

        // 4. Initialize the FakeStore with this realistic, pre-populated state
        fakeStore = FakeStore(stateAfterLoading, fakePlatform)

        // 5. Set the content for the test rule
        composeTestRule.setContent {
            SettingsView(
                store = fakeStore,
                onClose = {}
            )
        }
    }

    @Test
    fun `UI correctly displays section headers, labels, and descriptions`() {
        // Assert that the sections and setting details are rendered from state
        composeTestRule.onNodeWithText("Appearance").assertIsDisplayed()
        composeTestRule.onNodeWithText("Window Width").assertIsDisplayed()
        composeTestRule.onNodeWithText("The width of the application window.").assertIsDisplayed()

        composeTestRule.onNodeWithText("Agent").assertIsDisplayed()
        composeTestRule.onNodeWithText("Enable AI Agent").assertIsDisplayed()
    }

    @Test
    fun `UI controls display the current value from the store, not the default`() {
        // Assert that the text field displays the value loaded from the file (1024),
        // not the default value (1200).
        composeTestRule.onNodeWithText("Window Width")
            .assertIsDisplayed() // Find the label first...
            .let {
                // ...then find the text field associated with it to check its content.
                // NOTE: This is a simplified lookup. A real-world test would use semantics or test tags.
                composeTestRule.onNodeWithText("1024").assertIsDisplayed()
            }
    }

    @Test
    fun `user interaction dispatches a settings UPDATE action with the correct payload`() {
        // --- ACT ---
        // Find the text field for "Window Width" and change its value
        // This is a brittle selector, but good enough for this contract test. A better
        // way would be to use `onNode(hasTestTag("settings.textfield.core.window.width"))`
        composeTestRule.onNodeWithText("1024").performTextInput("999")

        // --- ASSERT ---
        // Verify that the correct action was dispatched to the store
        val dispatchedAction = fakeStore.dispatchedActions.lastOrNull()
        assertNotNull(dispatchedAction)
        assertEquals("settings.UPDATE", dispatchedAction.name)
        assertEquals("core.window.width", dispatchedAction.payload?.get("key")?.toString()?.trim('"'))
        assertEquals("1024999", dispatchedAction.payload?.get("value")?.toString()?.trim('"'))
    }

    @Test
    fun `clicking the Open Folder button dispatches the correct action`() {
        // --- ACT ---
        composeTestRule.onNodeWithText("Open Settings Folder").performClick()

        // --- ASSERT ---
        val dispatchedAction = fakeStore.dispatchedActions.lastOrNull()
        assertNotNull(dispatchedAction)
        assertEquals("settings.OPEN_FOLDER", dispatchedAction.name)
    }
}