package app.auf.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
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

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testAppVersion = "2.0.0-test"
    private lateinit var fakePlatform: FakePlatformDependencies
    private lateinit var testStore: TestStore
    private lateinit var settingsFeature: SettingsFeature
    private lateinit var coreFeature: CoreFeature

    // THE FIX: Define a minimal set of valid actions required for this specific test class.
    private val testActionRegistry = setOf(
        "system.INITIALIZING", "system.STARTING",
        "settings.OPEN_FOLDER", "settings.INPUT_CHANGED", "settings.publish.LOADED"
    )

    private class TestStore(
        initialState: AppState,
        features: List<Feature>,
        platformDependencies: PlatformDependencies,
        validActionNames: Set<String>
    ) : Store(initialState, features, platformDependencies, validActionNames) {
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

        val initialDefinitions = listOf(
            buildJsonObject {
                put("key", "core.window.width")
                put("type", "NUMERIC_LONG")
                put("label", "Window Width")
                put("description", "Initial window width in pixels.")
                put("section", "Appearance")
                put("defaultValue", "1280")
            },
            buildJsonObject {
                put("key", "test.api.key")
                put("type", "STRING")
                put("label", "API Key")
                put("description", "A test API key.")
                put("section", "Testing")
                put("defaultValue", "DEFAULT_API_KEY")
            },
            buildJsonObject {
                put("key", "test.paths")
                put("type", "STRING_SET")
                put("label", "Test Paths")
                put("description", "A set of test paths.")
                put("section", "Testing")
                put("defaultValue", "/path/one,/path/two")
            }
        )

        val initialState = AppState(featureStates = mapOf(
            coreFeature.name to CoreState(lifecycle = AppLifecycle.BOOTING),
            settingsFeature.name to SettingsState(definitions = initialDefinitions)
        ))
        testStore = TestStore(initialState, features, fakePlatform, testActionRegistry)
        features.forEach { it.init(testStore) }

        testStore.dispatch("system.test", Action("system.INITIALIZING"))

        val loadedSettingsPayload = buildJsonObject {
            put("core.window.width", "1024")
        }
        testStore.dispatch(settingsFeature.name, Action("settings.publish.LOADED", loadedSettingsPayload))

        testStore.dispatch("system.test", Action("system.STARTING"))

        testStore.dispatchedActions.clear()

        composeTestRule.setContent {
            SettingsView(store = testStore, onClose = {})
        }
    }

    @Test
    fun `UI controls display the current value from the store`() = runTest {
        composeTestRule.onNodeWithText("1024").assertIsDisplayed()
        composeTestRule.onNodeWithText("DEFAULT_API_KEY").assertIsDisplayed()
        composeTestRule.onNodeWithText("/path/one\n/path/two").assertIsDisplayed()
    }

    @Test
    fun `clicking the Open Folder button dispatches the correct action`() = runTest {
        composeTestRule.onNodeWithContentDescription("Open Settings Folder").performClick()
        val dispatchedAction = testStore.dispatchedActions.find { it.name == "settings.OPEN_FOLDER" }
        assertNotNull(dispatchedAction, "The UI should have dispatched 'settings.OPEN_FOLDER'.")
        assertEquals("settings.ui", dispatchedAction.originator)
    }

    @Test
    fun `string and string_set fields are editable and dispatch INPUT_CHANGED`() = runTest {
        val stringNode = composeTestRule.onNodeWithText("DEFAULT_API_KEY")
        stringNode.assertIsDisplayed()
        stringNode.performTextReplacement("new_key")

        val stringAction = testStore.dispatchedActions.find { it.payload?.get("key")?.jsonPrimitive?.content == "test.api.key" }
        assertNotNull(stringAction)
        assertEquals("settings.INPUT_CHANGED", stringAction.name)
        assertEquals("new_key", stringAction.payload?.get("value")?.jsonPrimitive?.content)

        val stringSetNode = composeTestRule.onNodeWithText("/path/one\n/path/two")
        stringSetNode.assertIsDisplayed()
        stringSetNode.performTextReplacement("/path/one\n/path/two\n/path/three")

        val stringSetAction = testStore.dispatchedActions.last()
        assertEquals("settings.INPUT_CHANGED", stringSetAction.name)
        assertEquals("test.paths", stringSetAction.payload?.get("key")?.jsonPrimitive?.content)
        assertEquals("/path/one,/path/two,/path/three", stringSetAction.payload?.get("value")?.jsonPrimitive?.content)
    }
}