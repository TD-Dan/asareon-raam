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
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreFeature
import app.auf.feature.core.CoreState
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
import kotlin.test.assertTrue

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

        // 1. Setup initial BOOTING state with all necessary features present.
        val initialState = AppState(featureStates = mapOf(
            coreFeature.name to CoreState(lifecycle = AppLifecycle.BOOTING),
            settingsFeature.name to SettingsState()
        ))
        testStore = TestStore(initialState, features, fakePlatform)
        features.forEach { it.init(testStore) } // Run init() for all features

        // 2. Orchestrate Startup Lifecycle via the action bus.
        // This causes CoreFeature to dispatch settings.ADD and SettingsFeature to dispatch filesystem.SYSTEM_READ.
        testStore.dispatch("system.test", Action("system.INITIALIZING"))

        // 3. Manually dispatch the result of the filesystem load (SettingsFeature.reducer handles this).
        // This is a pragmatic shortcut to skip mocking the entire filesystem chain.
        val loadedSettingsPayload = buildJsonObject {
            put("core.window.width", "1024") // This key is registered by CoreFeature in step 2.
        }
        testStore.dispatch(settingsFeature.name, Action("settings.LOADED", loadedSettingsPayload))

        // 4. Advance to RUNNING state.
        testStore.dispatch("system.test", Action("system.STARTING"))

        // Clear actions dispatched during setup to focus test assertions on UI interactions.
        testStore.dispatchedActions.clear()

        composeTestRule.setContent {
            SettingsView(store = testStore, onClose = {})
        }
    }

    @Test
    fun `UI controls display the current value from the store`() = runTest {
        // Assert on the manually set "1024" which should be in the state after step 3.
        composeTestRule.onNodeWithText("1024").assertIsDisplayed()
    }

    @Test
    fun `user typing in text field dispatches INPUT_CHANGED immediately`() = runTest {
        // Assert on the correct initial value first
        val inputNode = composeTestRule.onNodeWithText("1024")
        inputNode.assertIsDisplayed()

        // Perform text input, appending "999"
        inputNode.performTextInput("999")

        // The input field now reads "1024999"
        // Corrected action name assertion to match SettingsView.kt implementation.
        val inputAction = testStore.dispatchedActions.find { it.name == "settings.INPUT_CHANGED" }
        assertNotNull(inputAction, "Action 'settings.INPUT_CHANGED' should be dispatched immediately.")
        assertEquals("settings.ui", inputAction.originator)
        assertEquals("core.window.width", inputAction.payload?.get("key")?.jsonPrimitive?.content)
        assertEquals("9991024", inputAction.payload?.get("value")?.jsonPrimitive?.content)

        // Verify that UPDATE has NOT been dispatched yet.
        val updateAction = testStore.dispatchedActions.find { it.name == "settings.UPDATE" }
        assertNull(updateAction, "Action 'settings.UPDATE' should not be dispatched immediately.")
    }

    @Test
    fun `clicking the Open Folder button dispatches the correct action`() = runTest {
        composeTestRule.onNodeWithContentDescription("Open Settings Folder").performClick()

        // --- THE FIX ---
        // The UI's job is to dispatch 'settings.OPEN_FOLDER'. The feature then handles
        // this and dispatches a new 'filesystem' action as a side-effect. We must
        // test for the action the UI itself dispatched.
        val dispatchedAction = testStore.dispatchedActions.find { it.name == "settings.OPEN_FOLDER" }

        assertNotNull(dispatchedAction, "The UI should have dispatched 'settings.OPEN_FOLDER'.")
        assertEquals("settings.ui", dispatchedAction.originator)
    }
}