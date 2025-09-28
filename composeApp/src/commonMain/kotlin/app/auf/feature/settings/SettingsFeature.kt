package app.auf.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import app.auf.core.*
import app.auf.util.PlatformDependencies
import app.auf.util.BasePath
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * A feature that provides the UI and persistence logic for all application settings.
 * It discovers settings by listening for 'settings.ADD' actions and persists the
 * current values to disk.
 */
@Serializable
data class SettingsState(
    // The canonical list of all setting definitions, stored as the raw JSON received.
    val definitions: List<JsonObject> = emptyList(),
    // The current values for all settings, keyed by their unique key.
    val values: Map<String, String> = emptyMap()
) : FeatureState

class SettingsFeature(
    private val platformDependencies: PlatformDependencies
) : Feature {
    override val name: String = "SettingsFeature"
    override val composableProvider: Feature.ComposableProvider = SettingsComposableProvider()

    // Internal, encapsulated persistence logic. Not visible outside this class.
    private lateinit var persistence: SettingsPersistence

    override fun init(store: Store) {
        persistence = SettingsPersistence(platformDependencies)
    }

    override fun onAction(action: Action, store: Store) {
        when (action.name) {
            // After the app has started, dispatch a command to ourselves to load settings.
            "app.STARTING" -> {
                store.dispatch(Action("settings.LOAD"))
            }

            // The command to load from disk.
            "settings.LOAD" -> {
                val loadedValues = persistence.loadSettings()
                val payload = buildJsonObject {
                    for ((key, value) in loadedValues) {
                        put(key, JsonPrimitive(value))
                    }
                }
                store.dispatch(Action("settings.LOADED", payload))
            }

            // The command to update a value.
            "settings.UPDATE" -> {
                // The reducer has already updated the state in memory.
                // Our side effect is to persist the *entire*, new state to disk.
                val latestSettingsState = store.state.value.featureStates[name] as? SettingsState
                latestSettingsState?.let {
                    persistence.saveSettings(it.values)
                }
            }
        }
    }

    override fun reducer(state: AppState, action: Action): AppState {
        val settingsState = state.featureStates[name] as? SettingsState ?: SettingsState()
        var newSettingsState = settingsState

        when (action.name) {
            "settings.ADD" -> {
                action.payload?.let { definitionJson ->
                    val key = definitionJson["key"]?.jsonPrimitive?.content
                    // FIX: Also extract the defaultValue to add to the values map.
                    val defaultValue = definitionJson["defaultValue"]?.jsonPrimitive?.content
                    if (key != null && defaultValue != null && settingsState.definitions.none { it["key"]?.jsonPrimitive?.content == key }) {
                        newSettingsState = settingsState.copy(
                            definitions = settingsState.definitions + definitionJson,
                            // FIX: Atomically add the default value to the values map.
                            values = settingsState.values + (key to defaultValue)
                        )
                    }
                }
            }
            "settings.LOADED" -> {
                // When settings are loaded, merge them with the current values.
                // The file on disk is the source of truth for values.
                // We also apply the default values for any setting that was not in the file.
                val loadedValues = action.payload?.let {
                    it.mapValues { entry -> entry.value.jsonPrimitive.content }
                } ?: emptyMap()

                val valuesWithDefaults = settingsState.definitions.associate { def ->
                    val key = def["key"]!!.jsonPrimitive.content
                    val defaultValue = def["defaultValue"]!!.jsonPrimitive.content
                    key to (loadedValues[key] ?: defaultValue)
                }

                newSettingsState = settingsState.copy(values = valuesWithDefaults)
            }
            "settings.UPDATE" -> {
                // Immediately update the value in the state for instant UI feedback.
                action.payload?.let {
                    val key = it["key"]?.jsonPrimitive?.content
                    val value = it["value"]?.jsonPrimitive?.content
                    if (key != null && value != null) {
                        newSettingsState = settingsState.copy(
                            values = settingsState.values + (key to value)
                        )
                    }
                }
            }
            "settings.OPEN_FOLDER" -> {
                val settingsPath = platformDependencies.getBasePathFor(BasePath.SETTINGS)
                platformDependencies.openFolderInExplorer(settingsPath)
            }
        }

        return if (newSettingsState != settingsState) {
            state.copy(featureStates = state.featureStates + (name to newSettingsState))
        } else {
            state
        }
    }

    inner class SettingsComposableProvider : Feature.ComposableProvider {
        override val viewKey: String = "feature.settings.main"

        @Composable
        override fun RibbonButton(store: Store, isActive: Boolean) {
            val payload = buildJsonObject { put("key", viewKey) }
            IconButton(onClick = { store.dispatch(Action("core.SET_ACTIVE_VIEW", payload)) }) {
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
                onClose = { store.dispatch(Action("core.NAVIGATE_TO_DEFAULT_VIEW")) }
            )
        }
    }
}

/**
 * A private, internal class responsible for all file I/O operations for the SettingsFeature.
 * It is NOT a shared service. Its existence is an implementation detail of this feature.
 * This encapsulation ensures that no other part of the application can access the settings
 * file directly, enforcing the "API via Actions" principle.
 */
internal class SettingsPersistence(
    private val platformDependencies: PlatformDependencies
) {
    private val settingsFilePath: String by lazy {
        val basePath = platformDependencies.getBasePathFor(BasePath.SETTINGS)
        platformDependencies.createDirectories(basePath)
        "$basePath${platformDependencies.pathSeparator}settings.json"
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * Loads the settings map from the canonical settings.json file.
     * If the file doesn't exist, it returns an empty map.
     */
    fun loadSettings(): Map<String, String> {
        return if (platformDependencies.fileExists(settingsFilePath)) {
            try {
                val content = platformDependencies.readFileContent(settingsFilePath)
                json.decodeFromString<Map<String, String>>(content)
            } catch (e: Exception) {
                println("ERROR: Failed to load or parse settings.json: ${e.message}")
                emptyMap()
            }
        } else {
            emptyMap()
        }
    }

    /**
     * Saves the entire settings map to the canonical settings.json file.
     * This overwrites the previous file, ensuring the disk is always in sync
     * with the latest application state.
     */
    fun saveSettings(values: Map<String, String>) {
        try {
            val content = json.encodeToString(values)
            platformDependencies.writeFileContent(settingsFilePath, content)
        } catch (e: Exception) {
            println("ERROR: Failed to save settings.json: ${e.message}")
        }
    }
}