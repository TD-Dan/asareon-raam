package app.auf.feature.settings

import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.Feature
import app.auf.core.Store
import app.auf.fakes.FakePlatformDependencies
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsFeatureTest {

    private val testAppVersion = "2.0.0-test"

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

    /**
     * A high-fidelity test Store that allows capturing dispatched actions
     * and manually invoking the onPrivateData callback for testing.
     */
    private class TestStore(
        initialState: AppState,
        private val features: List<Feature>,
        platformDependencies: FakePlatformDependencies
    ) : Store(initialState, features, platformDependencies) {
        val dispatchedActions = mutableListOf<Action>()

        override fun dispatch(originator: String, action: Action) {
            val stampedAction = action.copy(originator = originator)
            dispatchedActions.add(stampedAction)
            // Run the real reducer logic to keep state consistent.
            super.dispatch(originator, action)
        }

        // Expose deliverPrivateData for test setup
        public override fun deliverPrivateData(originator: String, recipient: String, data: Any) {
            super.deliverPrivateData(originator, recipient, data)
        }
    }


    // --- REDUCER TESTS (Purity & Correctness - Largely Unchanged) ---

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

    // --- onAction & onPrivateData TESTS (Side Effects & Integration) ---

    @Test
    fun `onAction for app INITIALIZING dispatches filesystem SYSTEM_READ`() {
        // Arrange
        val platform = FakePlatformDependencies(testAppVersion)
        val feature = SettingsFeature(platform)
        val store = TestStore(AppState(), listOf(feature), platform)
        val action = Action("system.INITIALIZING")

        // Act
        feature.onAction(action, store)

        // Assert
        assertEquals(1, store.dispatchedActions.size)
        val dispatched = store.dispatchedActions.first()
        assertEquals("filesystem.SYSTEM_READ", dispatched.name)
        assertEquals(feature.name, dispatched.originator)
        assertEquals("settings.json", dispatched.payload?.get("subpath")?.jsonPrimitive?.content)
    }

    @Test
    fun `onPrivateData with file content dispatches settings LOADED`() {
        // Arrange
        val platform = FakePlatformDependencies(testAppVersion)
        val feature = SettingsFeature(platform)
        val store = TestStore(AppState(), listOf(feature), platform)
        val privateData = buildJsonObject {
            put("subpath", "settings.json")
            put("content", """{ "file.key": "file.value" }""")
        }

        // Act
        // Manually trigger the private data channel, simulating a response from FileSystemFeature
        store.deliverPrivateData("filesystem", feature.name, privateData)

        // Assert
        assertEquals(1, store.dispatchedActions.size)
        val dispatched = store.dispatchedActions.first()
        assertEquals("settings.publish.LOADED", dispatched.name)
        assertEquals(feature.name, dispatched.originator)
        assertEquals("file.value", dispatched.payload?.get("file.key")?.jsonPrimitive?.content)
    }

    @Test
    fun `onAction for settings UPDATE dispatches filesystem SYSTEM_WRITE`() {
        // Arrange
        val platform = FakePlatformDependencies(testAppVersion)
        val feature = SettingsFeature(platform)
        // Set up a state where a value has already been changed
        val initialState = AppState(featureStates = mapOf(
            feature.name to SettingsState(values = mapOf("key1" to "new_value"))
        ))
        val store = TestStore(initialState, listOf(feature), platform)
        val action = Action("settings.UPDATE", buildJsonObject {
            put("key", "key1")
            put("value", "new_value")
        })

        // Act
        feature.onAction(action, store)

        // Assert
        // Expect two actions: the SYSTEM_WRITE and the public VALUE_CHANGED broadcast
        assertEquals(2, store.dispatchedActions.size)
        val writeAction = store.dispatchedActions.find { it.name == "filesystem.SYSTEM_WRITE" }
        assertNotNull(writeAction)
        assertEquals(feature.name, writeAction.originator)
        assertEquals("settings.json", writeAction.payload?.get("subpath")?.jsonPrimitive?.content)
        assertEquals("""{"key1":"new_value"}""", writeAction.payload?.get("content")?.jsonPrimitive?.content)

        // --- CORRECTED ASSERTION ---
        // Verify that the encryption flag is ALWAYS present and true, as per the security mandate.
        val encryptFlag = writeAction.payload?.get("encrypt")?.jsonPrimitive?.booleanOrNull
        assertNotNull(encryptFlag, "The 'encrypt' flag must always be present on settings writes.")
        assertTrue(encryptFlag, "The 'encrypt' flag must always be true to enforce at-rest encryption.")


        val changedAction = store.dispatchedActions.find { it.name == "settings.publish.VALUE_CHANGED" }
        assertNotNull(changedAction)
    }

    @Test
    fun `onAction for OPEN_FOLDER dispatches OPEN_SYSTEM_FOLDER`() {
        // Arrange
        val platform = FakePlatformDependencies(testAppVersion)
        val feature = SettingsFeature(platform)
        val store = TestStore(AppState(), listOf(feature), platform)
        val action = Action("settings.OPEN_FOLDER")

        // Act
        feature.onAction(action, store)

        // Assert
        val dispatched = store.dispatchedActions.lastOrNull()
        assertNotNull(dispatched)
        assertEquals("filesystem.OPEN_SYSTEM_FOLDER", dispatched.name)
        assertEquals(feature.name, dispatched.originator)
        assertNull(dispatched.payload)
    }
}