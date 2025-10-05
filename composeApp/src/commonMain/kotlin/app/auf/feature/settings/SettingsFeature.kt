package app.auf.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import app.auf.core.*
import app.auf.util.BasePath
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
data class SettingsState(
    val definitions: List<JsonObject> = emptyList(),
    // The canonical, persisted values for all settings.
    val values: Map<String, String> = emptyMap(),
    // A transient map of user input that has not yet been debounced and persisted.
    val inputValues: Map<String, String> = emptyMap()
) : FeatureState

class SettingsFeature(
    private val platformDependencies: PlatformDependencies
) : Feature {
    override val name: String = "SettingsFeature"
    override val composableProvider: Feature.ComposableProvider = SettingsComposableProvider()

    private lateinit var persistence: SettingsPersistence
    private val featureScope = CoroutineScope(Dispatchers.Default)
    private val debounceJobs = mutableMapOf<String, Job>()

    override fun init(store: Store) {
        persistence = SettingsPersistence(platformDependencies)
    }

    override fun onAction(action: Action, store: Store) {
        when (action.name) {
            "app.INITIALIZING" -> {
                store.dispatch(Action("settings.LOAD", null, "settings"))
            }

            "settings.LOAD" -> {
                val loadedValues = persistence.loadSettings()
                val payload = buildJsonObject {
                    for ((key, value) in loadedValues) {
                        put(key, JsonPrimitive(value))
                    }
                }
                store.dispatch(Action("settings.LOADED", payload, "settings"))
            }

            "settings.INPUT_CHANGED" -> {
                val key = action.payload?.get("key")?.jsonPrimitive?.content ?: return
                val value = action.payload.get("value")?.jsonPrimitive?.content ?: return

                debounceJobs[key]?.cancel()
                debounceJobs[key] = featureScope.launch {
                    delay(750L) // Debounce delay
                    val updatePayload = buildJsonObject {
                        put("key", key)
                        put("value", value)
                    }
                    store.dispatch(Action("settings.UPDATE", updatePayload, "settings"))
                }
            }

            "settings.UPDATE" -> {
                val latestSettingsState = store.state.value.featureStates[name] as? SettingsState
                latestSettingsState?.let {
                    persistence.saveSettings(it.values)
                }
                action.payload?.let { payload ->
                    store.dispatch(Action("settings.VALUE_CHANGED", payload, "settings"))
                }
            }

            "settings.OPEN_FOLDER" -> {
                val settingsPath = platformDependencies.getBasePathFor(BasePath.SETTINGS)
                platformDependencies.openFolderInExplorer(settingsPath)
            }
        }
    }

    override fun reducer(state: AppState, action: Action): AppState {
        val currentFeatureState = state.featureStates[name] as? SettingsState ?: SettingsState()

        return when (action.name) {
            "settings.ADD" -> {
                val definitionJson = action.payload ?: return state
                val key = definitionJson["key"]?.jsonPrimitive?.content
                val defaultValue = definitionJson["defaultValue"]?.jsonPrimitive?.content

                if (key != null && defaultValue != null && currentFeatureState.definitions.none { it["key"]?.jsonPrimitive?.content == key }) {
                    val newDefinitions = currentFeatureState.definitions + definitionJson
                    val newValues = if (currentFeatureState.values.containsKey(key)) {
                        currentFeatureState.values
                    } else {
                        currentFeatureState.values + (key to defaultValue)
                    }
                    val newFeatureState = currentFeatureState.copy(definitions = newDefinitions, values = newValues)
                    state.copy(featureStates = state.featureStates + (name to newFeatureState))
                } else {
                    state
                }
            }

            "settings.LOADED" -> {
                val loadedValues = action.payload?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                val valuesWithDefaults = currentFeatureState.definitions.associate { def ->
                    val key = def["key"]!!.jsonPrimitive.content
                    val defaultValue = def["defaultValue"]!!.jsonPrimitive.content
                    key to (loadedValues[key] ?: defaultValue)
                }
                val newFeatureState = currentFeatureState.copy(values = valuesWithDefaults, inputValues = valuesWithDefaults)
                state.copy(featureStates = state.featureStates + (name to newFeatureState))
            }

            "settings.INPUT_CHANGED" -> {
                val payload = action.payload ?: return state
                val key = payload["key"]?.jsonPrimitive?.content ?: return state
                val value = payload["value"]?.jsonPrimitive?.content ?: return state

                val newInputs = currentFeatureState.inputValues + (key to value)
                val newFeatureState = currentFeatureState.copy(inputValues = newInputs)
                state.copy(featureStates = state.featureStates + (name to newFeatureState))
            }

            "settings.UPDATE" -> {
                val payload = action.payload ?: return state
                val key = payload["key"]?.jsonPrimitive?.content ?: return state
                val value = payload["value"]?.jsonPrimitive?.content ?: return state

                // Persist the final debounced value to both maps.
                val newValues = currentFeatureState.values + (key to value)
                val newInputs = currentFeatureState.inputValues + (key to value)
                val newFeatureState = currentFeatureState.copy(values = newValues, inputValues = newInputs)
                state.copy(featureStates = state.featureStates + (name to newFeatureState))
            }

            else -> state
        }
    }

    inner class SettingsComposableProvider : Feature.ComposableProvider {
        override val viewKey: String = "feature.settings.main"

        @Composable
        override fun RibbonButton(store: Store, isActive: Boolean) {
            val payload = buildJsonObject { put("key", viewKey) }
            IconButton(onClick = { store.dispatch(Action("core.SET_ACTIVE_VIEW", payload, "settings")) }) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        @Composable
        override fun StageContent(store: Store) {
            SettingsView(
                store = store,
                onClose = { store.dispatch(Action("core.SHOW_DEFAULT_VIEW", null, "settings")) }
            )
        }
    }
}

internal class SettingsPersistence(
    private val platformDependencies: PlatformDependencies
) {
    private val settingsFilePath: String by lazy {
        val basePath = platformDependencies.getBasePathFor(BasePath.SETTINGS)
        platformDependencies.createDirectories(basePath)
        "$basePath${platformDependencies.pathSeparator}settings.json"
    }

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun loadSettings(): Map<String, String> {
        return if (platformDependencies.fileExists(settingsFilePath)) {
            try {
                val content = platformDependencies.readFileContent(settingsFilePath)
                json.decodeFromString<Map<String, String>>(content)
            } catch (e: Exception) {
                platformDependencies.log(LogLevel.ERROR, "SettingsPersistence", "Failed to load or parse settings.json: ${e.message}")
                emptyMap()
            }
        } else {
            emptyMap()
        }
    }

    fun saveSettings(values: Map<String, String>) {
        try {
            val content = json.encodeToString(values)
            platformDependencies.writeFileContent(settingsFilePath, content)
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.ERROR, "SettingsPersistence", "Failed to save settings.json: ${e.message}")
        }
    }
}