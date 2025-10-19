package app.auf.feature.settings

import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.generated.ActionNames
import app.auf.fakes.FakePlatformDependencies
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
    private val featureName = feature.name

    private fun createAddAction(key: String, defaultValue: String, section: String = "Test"): Action {
        return Action(ActionNames.SETTINGS_ADD, buildJsonObject {
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
        val initialState = AppState(featureStates = mapOf(featureName to SettingsState()))
        val action = createAddAction("test.key", "123")

        val newState = feature.reducer(initialState, action)
        val newSettingsState = newState.featureStates[featureName] as? SettingsState

        assertNotNull(newSettingsState)
        assertEquals(1, newSettingsState.definitions.size)
        assertEquals("123", newSettingsState.values["test.key"])
    }

    @Test
    fun `reducer UPDATE changes the value for an existing key`() {
        val initialState = AppState(featureStates = mapOf(featureName to SettingsState(values = mapOf("test.key" to "old"))))
        val action = Action(ActionNames.SETTINGS_UPDATE, buildJsonObject {
            put("key", "test.key")
            put("value", "new")
        })

        val newState = feature.reducer(initialState, action)
        val newSettingsState = newState.featureStates[featureName] as? SettingsState

        assertNotNull(newSettingsState)
        assertEquals("new", newSettingsState.values["test.key"])
    }

    @Test
    fun `reducer INPUT_CHANGED updates transient input value but not persisted value`() {
        val initialState = AppState(featureStates = mapOf(featureName to SettingsState(values = mapOf("key" to "persisted"))))
        val action = Action(ActionNames.SETTINGS_UI_INTERNAL_INPUT_CHANGED, buildJsonObject {
            put("key", "key")
            put("value", "transient")
        })

        val newState = feature.reducer(initialState, action)
        val newSettingsState = newState.featureStates[featureName] as? SettingsState

        assertNotNull(newSettingsState)
        assertEquals("persisted", newSettingsState.values["key"], "Persisted value should not change.")
        assertEquals("transient", newSettingsState.inputValues["key"], "Input value should be updated.")
    }

    @Test
    fun `reducer LOADED applies loaded values over defaults`() {
        val addAction = createAddAction("test.key", "default")
        val stateAfterAdd = feature.reducer(AppState(featureStates = mapOf(featureName to SettingsState())), addAction)
        val loadedAction = Action(ActionNames.SETTINGS_PUBLISH_LOADED, buildJsonObject {
            put("test.key", "loaded")
        })

        val finalState = feature.reducer(stateAfterAdd, loadedAction)
        val finalSettingsState = finalState.featureStates[featureName] as? SettingsState

        assertNotNull(finalSettingsState)
        assertEquals("loaded", finalSettingsState.values["test.key"])
        assertEquals("loaded", finalSettingsState.inputValues["test.key"], "Input values should also be hydrated on load.")
    }
}