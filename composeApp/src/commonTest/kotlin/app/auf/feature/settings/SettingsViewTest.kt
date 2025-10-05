package app.auf.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.Feature
import app.auf.core.Store
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.core.CoreFeature
import app.auf.feature.core.CoreState
import app.auf.util.BasePath
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SettingsViewTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testAppVersion = "2.0.0-test"
    private lateinit var fakePlatform: FakePlatformDependencies
    private lateinit var testStore: TestStore
    private lateinit var settingsFeature: SettingsFeature
    private lateinit var coreFeature: CoreFeature

    /**
     * An instrumented, high-fidelity Test Store.
     * It logs every dispatched action before passing it to the real Store logic.
     */
    private class TestStore(
        initialState: AppState,
        features: List<Feature>,
        val platformDependencies: PlatformDependencies // Expose for logging
    ) : Store(initialState, features, platformDependencies) {
        val dispatchedActions = mutableListOf<Action>()
        override fun dispatch(action: Action) {
            // --- INSTRUMENTATION ---
            platformDependencies.log(LogLevel.INFO, "TestStore", "ACTION DISPATCHED: $action")
            dispatchedActions.add(action)
            super.dispatch(action) // Call the real logic
        }
    }

    @Before
    fun setUp() {
        // --- ARRANGE ---
        fakePlatform = FakePlatformDependencies(testAppVersion)
        settingsFeature = SettingsFeature(fakePlatform)
        coreFeature = CoreFeature(fakePlatform)
        val features = listOf(coreFeature, settingsFeature)
        val settingsPath = fakePlatform.getBasePathFor(BasePath.SETTINGS) + fakePlatform.pathSeparator + "settings.json"
        fakePlatform.writeFileContent(settingsPath, """{ "core.window.width": "1024" }""")
        val addAction1 = Action("settings.ADD", buildJsonObject {
            put("key", "core.window.width"); put("type", "NUMERIC_LONG"); put("label", "Window Width")
            put("description", "Desc"); put("section", "Appearance"); put("defaultValue", "1200")
        })
        var initialState = AppState(featureStates = mapOf(coreFeature.name to CoreState(), settingsFeature.name to SettingsState()))
        initialState = settingsFeature.reducer(initialState, addAction1)
        testStore = TestStore(initialState, features, fakePlatform)
        features.forEach { it.init(testStore) }
        testStore.dispatch(Action("app.INITIALIZING"))
        testStore.dispatch(Action("app.STARTING"))
        testStore.dispatchedActions.clear()
        composeTestRule.setContent {
            SettingsView(store = testStore, onClose = {})
        }
        composeTestRule.mainClock.autoAdvance = false
    }

    @Test
    fun `UI controls display the current value from the store, not the default`() {
        composeTestRule.onNodeWithText("1024").assertIsDisplayed()
    }

    @Test
    fun `user interaction dispatches a settings UPDATE action with the correct payload`() {
        try {
            // --- ACT ---
            composeTestRule.onNodeWithText("1024").performTextInput("999")
            composeTestRule.mainClock.advanceTimeBy(1001L)

            // --- ASSERT ---
            composeTestRule.runOnIdle {
                val dispatchedAction = testStore.dispatchedActions.find { it.name == "settings.UPDATE" }
                assertNotNull(dispatchedAction, "Action 'settings.UPDATE' should have been dispatched.")
                assertEquals("core.window.width", dispatchedAction.payload?.get("key")?.jsonPrimitive?.content)
                assertEquals("999", dispatchedAction.payload?.get("value")?.jsonPrimitive?.content)
            }
        } finally {
            // --- INSTRUMENTATION DUMP ---
            // This block will execute even if the test fails, printing the captured data.
            println("\n--- INSTRUMENTATION DUMP ---")
            println("CAPTURED ACTIONS (${testStore.dispatchedActions.size}):")
            if (testStore.dispatchedActions.isEmpty()) {
                println("  <none>")
            } else {
                testStore.dispatchedActions.forEach { println("  - $it") }
            }
            println("\nCAPTURED LOGS (${fakePlatform.capturedLogs.size}):")
            if (fakePlatform.capturedLogs.isEmpty()) {
                println("  <none>")
            } else {
                fakePlatform.capturedLogs.forEach { println("  - [${it.level}] ${it.tag}: ${it.message}") }
            }
            println("--- END DUMP ---\n")
        }
    }
}