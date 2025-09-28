package app.auf.feature.settings

import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.Store
import app.auf.fakes.FakePlatformDependencies
import app.auf.util.BasePath
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SettingsFeatureTest {

    private val testAppVersion = "2.0.0-test"

    /**
     * A lightweight, controllable fake of the Store for testing `onAction` side effects.
     * It now accepts a PlatformDependencies instance to satisfy the base class constructor.
     */
    private class FakeStore(
        initialState: AppState,
        platformDependencies: PlatformDependencies
    ) : Store(initialState, emptyList(), platformDependencies) {
        val dispatchedActions = mutableListOf<Action>()
        private val _fakeState = MutableStateFlow(initialState)
        override val state = _fakeState.asStateFlow()

        override fun dispatch(action: Action) {
            dispatchedActions.add(action)
        }

        fun setState(newState: AppState) {
            _fakeState.value = newState
        }
    }

    private fun createAddAction(key: String, defaultValue: String): Action {
        return Action("settings.ADD", buildJsonObject {
            put("key", key)
            put("type", "NUMERIC_LONG")
            put("label", "$key Label")
            put("description", "$key Desc")
            put("section", "Test Section")
            put("defaultValue", defaultValue)
        })
    }

    // --- REDUCER TESTS (Purity & Correctness) ---

    @Test
    fun `reducer ADD registers a new setting and applies its default value`() {
        // Arrange
        val platform = FakePlatformDependencies(testAppVersion)
        val feature = SettingsFeature(platform)
        val initialState = AppState(featureStates = mapOf(feature.name to SettingsState()))
        val action = createAddAction("test.key", "123")

        // Act
        val newState = feature.reducer(initialState, action)
        val newSettingsState = newState.featureStates[feature.name] as? SettingsState

        // Assert
        assertNotNull(newSettingsState)
        assertEquals(1, newSettingsState.definitions.size, "A new definition should be added.")
        assertEquals("123", newSettingsState.values["test.key"], "The default value should be applied.")
    }

    @Test
    fun `reducer ADD ignores a definition if the key already exists`() {
        // Arrange
        val platform = FakePlatformDependencies(testAppVersion)
        val feature = SettingsFeature(platform)
        val addAction = createAddAction("test.key", "123")
        // Start with a state that already has the key registered
        val initialState = feature.reducer(AppState(), addAction)
        val initialSettingsState = initialState.featureStates[feature.name] as SettingsState
        assertEquals(1, initialSettingsState.definitions.size, "Precondition failed: Initial state is incorrect.")

        // Act: Try to add the same key again
        val newState = feature.reducer(initialState, addAction)

        // Assert
        assertEquals(initialState, newState, "State should not change when adding a duplicate key.")
    }

    @Test
    fun `reducer UPDATE changes the value for an existing key`() {
        // Arrange
        val platform = FakePlatformDependencies(testAppVersion)
        val feature = SettingsFeature(platform)
        val initialState = AppState(featureStates = mapOf(feature.name to SettingsState(values = mapOf("test.key" to "old_value"))))
        val action = Action("settings.UPDATE", buildJsonObject {
            put("key", "test.key")
            put("value", "new_value")
        })

        // Act
        val newState = feature.reducer(initialState, action)
        val newSettingsState = newState.featureStates[feature.name] as? SettingsState

        // Assert
        assertNotNull(newSettingsState)
        assertEquals("new_value", newSettingsState.values["test.key"])
    }

    @Test
    fun `reducer LOADED replaces all values and applies defaults`() {
        // Arrange
        val platform = FakePlatformDependencies(testAppVersion)
        val feature = SettingsFeature(platform)
        // State has two definitions, but only one current value
        val initialState = AppState(featureStates = mapOf(feature.name to SettingsState(
            definitions = listOf(
                createAddAction("persisted.key", "default1").payload!!,
                createAddAction("missing.key", "default2").payload!!
            ),
            values = mapOf("persisted.key" to "old_value")
        )))
        // Payload from file only contains one of the keys
        val payload = buildJsonObject { put("persisted.key", "from_file") }
        val action = Action("settings.LOADED", payload)

        // Act
        val newState = feature.reducer(initialState, action)
        val newSettingsState = newState.featureStates[feature.name] as? SettingsState

        // Assert
        assertNotNull(newSettingsState)
        assertEquals("from_file", newSettingsState.values["persisted.key"], "Should use value from file when present.")
        assertEquals("default2", newSettingsState.values["missing.key"], "Should apply default value for keys not in file.")
    }

    @Test
    fun `reducer ignores unknown actions`() {
        // Arrange
        val platform = FakePlatformDependencies(testAppVersion)
        val feature = SettingsFeature(platform)
        val initialState = AppState(featureStates = mapOf(feature.name to SettingsState()))
        val action = Action("some.other.feature.ACTION")

        // Act
        val newState = feature.reducer(initialState, action)

        // Assert
        assertEquals(initialState, newState, "State should be unchanged for an unknown action.")
    }

    // --- onAction TESTS (Side Effects & State Immutability) ---

    @Test
    fun `onAction for app STARTING dispatches settings LOAD`() {
        // Arrange
        val platform = FakePlatformDependencies(testAppVersion)
        val feature = SettingsFeature(platform)
        val fakeStore = FakeStore(AppState(), platform)
        feature.init(fakeStore)
        val action = Action("app.STARTING")

        // Act
        feature.onAction(action, fakeStore)

        // Assert
        assertEquals(1, fakeStore.dispatchedActions.size)
        assertEquals("settings.LOAD", fakeStore.dispatchedActions.first().name)
    }

    @Test
    fun `onAction for settings LOAD reads file and dispatches LOADED`() {
        // Arrange
        val platform = FakePlatformDependencies(testAppVersion)
        val settingsPath = platform.getBasePathFor(BasePath.SETTINGS) + platform.pathSeparator + "settings.json"
        platform.writeFileContent(settingsPath, """{ "file.key": "file.value" }""")

        val feature = SettingsFeature(platform)
        val fakeStore = FakeStore(AppState(), platform)
        feature.init(fakeStore)
        val action = Action("settings.LOAD")

        // Act
        feature.onAction(action, fakeStore)

        // Assert
        assertEquals(1, fakeStore.dispatchedActions.size)
        val dispatched = fakeStore.dispatchedActions.first()
        assertEquals("settings.LOADED", dispatched.name)
        assertEquals("file.value", dispatched.payload?.get("file.key")?.toString()?.trim('"'))
    }

    @Test
    fun `onAction for settings UPDATE saves the latest values to disk`() {
        // Arrange
        val platform = FakePlatformDependencies(testAppVersion)
        val feature = SettingsFeature(platform)
        // The store's state reflects the new value AFTER the reducer has run
        val stateAfterReducer = AppState(featureStates = mapOf(feature.name to SettingsState(
            values = mapOf("key1" to "value1", "key2" to "value2")
        )))
        val fakeStore = FakeStore(AppState(), platform)
        fakeStore.setState(stateAfterReducer) // Manually set the store's state
        feature.init(fakeStore)
        val action = Action("settings.UPDATE")

        // Act
        feature.onAction(action, fakeStore)

        // Assert
        val settingsPath = platform.getBasePathFor(BasePath.SETTINGS) + platform.pathSeparator + "settings.json"
        assertTrue(platform.fileExists(settingsPath), "Settings file should have been created.")
        val fileContent = platform.readFileContent(settingsPath)
        assertTrue(fileContent.contains(""""key1": "value1""""), "File content is incorrect.")
        assertTrue(fileContent.contains(""""key2": "value2""""), "File content is incorrect.")
    }

    @Test
    fun `onAction does NOT modify state`() {
        // Arrange
        val platform = FakePlatformDependencies(testAppVersion)
        val feature = SettingsFeature(platform)
        val initialState = AppState(featureStates = mapOf(feature.name to SettingsState(values = mapOf("a" to "b"))))
        val fakeStore = FakeStore(initialState, platform)
        feature.init(fakeStore)
        val action = Action("settings.UPDATE") // An action that triggers a side-effect

        // Act
        // We set the state *before* the action to simulate the full dispatch cycle
        fakeStore.setState(initialState)
        feature.onAction(action, fakeStore) // Trigger the side effect

        // Assert
        assertEquals(initialState, fakeStore.state.value, "onAction must not change the application state.")
    }
}