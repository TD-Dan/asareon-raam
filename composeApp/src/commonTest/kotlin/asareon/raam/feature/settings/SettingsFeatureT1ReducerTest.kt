package asareon.raam.feature.settings

import asareon.raam.core.Action
import asareon.raam.core.AppState
import asareon.raam.core.FeatureState
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.fakes.FakePlatformDependencies
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tier 1 Unit Test for SettingsFeature's reducer.
 *
 * Mandate (P-TEST-001, T1): To test the reducer as a pure function in complete isolation.
 */
class SettingsFeatureT1ReducerTest {

    private val feature = SettingsFeature(FakePlatformDependencies("test"))
    private val featureName = feature.identity.handle

    private fun createAddAction(key: String, defaultValue: String, section: String = "Test"): Action {
        return Action(ActionRegistry.Names.SETTINGS_ADD, buildJsonObject {
            put("key", key)
            put("type", "STRING")
            put("label", "$key Label")
            put("description", "$key Desc")
            put("section", section)
            put("defaultValue", defaultValue)
        })
    }

    @Test
    fun `reducer ADD registers a new setting and applies its default value`() {
        val initialState = SettingsState()
        val action = createAddAction("test.key", "123")

        val newState = feature.reducer(initialState, action) as? SettingsState

        assertNotNull(newState)
        assertEquals(1, newState.definitions.size)
        assertEquals("123", newState.values["test.key"])
    }

    @Test
    fun `reducer UPDATE changes the value for an existing key`() {
        val initialState = SettingsState(values = mapOf("test.key" to "old"))
        val action = Action(ActionRegistry.Names.SETTINGS_UPDATE, buildJsonObject {
            put("key", "test.key")
            put("value", "new")
        })

        val newState = feature.reducer(initialState, action) as? SettingsState

        assertNotNull(newState)
        assertEquals("new", newState.values["test.key"])
    }

    @Test
    fun `reducer INPUT_CHANGED updates transient input value but not persisted value`() {
        val initialState = SettingsState(values = mapOf("key" to "persisted"))
        val action = Action(ActionRegistry.Names.SETTINGS_UI_INPUT_CHANGED, buildJsonObject {
            put("key", "key")
            put("value", "transient")
        })

        val newState = feature.reducer(initialState, action) as? SettingsState

        assertNotNull(newState)
        assertEquals("persisted", newState.values["key"], "Persisted value should not change.")
        assertEquals("transient", newState.inputValues["key"], "Input value should be updated.")
    }

    @Test
    fun `reducer LOADED applies loaded values over defaults`() {
        val addAction = createAddAction("test.key", "default")
        val stateAfterAdd = feature.reducer(SettingsState(), addAction)
        val loadedAction = Action(ActionRegistry.Names.SETTINGS_LOADED, buildJsonObject {
            put("test.key", "loaded")
        })

        val finalState = feature.reducer(stateAfterAdd, loadedAction) as? SettingsState

        assertNotNull(finalState)
        assertEquals("loaded", finalState.values["test.key"])
        assertEquals("loaded", finalState.inputValues["test.key"], "Input values should also be hydrated on load.")
    }
}