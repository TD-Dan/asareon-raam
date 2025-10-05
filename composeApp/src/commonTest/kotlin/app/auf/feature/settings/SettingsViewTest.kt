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
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testAppVersion = "2.0.0-test"
    private lateinit var fakePlatform: FakePlatformDependencies
    private lateinit var testStore: TestStore
    private lateinit var settingsFeature: SettingsFeature
    private lateinit var coreFeature: CoreFeature

    private class TestStore(
        initialState: AppState,
        features: List<Feature>,
        platformDependencies: PlatformDependencies
    ) : Store(initialState, features, platformDependencies) {
        val dispatchedActions = mutableListOf<Action>()
        override fun dispatch(originator: String, action: Action) {
            dispatchedActions.add(action.copy(originator = originator))
            super.dispatch(originator, action)
        }
    }

    @Before
    fun setUp() {
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
        testStore.dispatch("system.test", Action("system.INITIALIZING"))
        testStore.dispatch("system.test", Action("system.STARTING"))
        testStore.dispatchedActions.clear()
        composeTestRule.setContent {
            SettingsView(store = testStore, onClose = {})
        }
    }

    @Test
    fun `UI controls display the current value from the store`() = runTest {
        composeTestRule.onNodeWithText("1024").assertIsDisplayed()
    }

    @Test
    fun `user typing in text field dispatches INPUT_CHANGED immediately`() = runTest {
        composeTestRule.onNodeWithText("1024").performTextInput("999")

        val inputAction = testStore.dispatchedActions.find { it.name == "settings.INPUT_CHANGED" }
        assertNotNull(inputAction, "Action 'settings.INPUT_CHANGED' should be dispatched immediately.")
        assertEquals("settings.ui", inputAction.originator)
        assertEquals("9991024", inputAction.payload?.get("value")?.jsonPrimitive?.content)

        // Verify that UPDATE has NOT been dispatched yet.
        val updateAction = testStore.dispatchedActions.find { it.name == "settings.UPDATE" }
        assertNull(updateAction, "Action 'settings.UPDATE' should not be dispatched immediately.")
    }

    @Test
    fun `clicking the Open Folder button dispatches the correct action`() = runTest {
        composeTestRule.onNodeWithContentDescription("Open Settings Folder").performClick()

        val dispatchedAction = testStore.dispatchedActions.lastOrNull()
        assertNotNull(dispatchedAction)
        assertEquals("settings.OPEN_FOLDER", dispatchedAction.name)
        assertEquals("settings.ui", dispatchedAction.originator)
    }
}