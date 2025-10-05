package app.auf.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import app.auf.core.*
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
    override val name: String = "settings"
    override val composableProvider: Feature.ComposableProvider = SettingsComposableProvider()

    private val featureScope = CoroutineScope(Dispatchers.Default)
    private val debounceJobs = mutableMapOf<String, Job>()
    private val settingsFileName = "settings.json"

    // This feature no longer needs an init() block.

    override fun onPrivateData(data: Any, store: Store) {
        // This is the secure callback for the result of our filesystem.SYSTEM_READ request.
        val payload = data as? JsonObject ?: return
        val subpath = payload["subpath"]?.jsonPrimitive?.content
        val content = payload["content"]?.jsonPrimitive?.contentOrNull

        if (subpath == settingsFileName) {
            val loadedValues = if (content != null) {
                try {
                    Json.decodeFromString<Map<String, String>>(content)
                } catch (e: Exception) {
                    emptyMap()
                }
            } else {
                emptyMap()
            }

            // Create a payload for our internal LOADED action.
            val loadedPayload = buildJsonObject {
                for ((key, value) in loadedValues) {
                    put(key, JsonPrimitive(value))
                }
            }
            // Dispatch the internal action to hydrate the reducer.
            store.dispatch(this.name, Action("settings.LOADED", loadedPayload))
        }
    }


    override fun onAction(action: Action, store: Store) {
        when (action.name) {
            "system.INITIALIZING" -> {
                // Instead of loading directly, we securely request our settings file
                // from the FileSystemFeature's sandbox.
                store.dispatch(this.name, Action("filesystem.SYSTEM_READ", buildJsonObject {
                    put("subpath", settingsFileName)
                }))
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
                    store.dispatch(name, Action("settings.UPDATE", updatePayload))
                }
            }

            "settings.UPDATE" -> {
                val latestSettingsState = store.state.value.featureStates[name] as? SettingsState ?: return
                // The values have already been updated in the state by the reducer.
                // Now, we securely request the FileSystemFeature to persist them.
                val contentToSave = Json.encodeToString(latestSettingsState.values)
                store.dispatch(this.name, Action("filesystem.SYSTEM_WRITE", buildJsonObject {
                    put("subpath", settingsFileName)
                    put("content", contentToSave)
                }))

                // We must still broadcast the public VALUE_CHANGED event for other features.
                action.payload?.let { payload ->
                    store.dispatch(this.name, Action("settings.VALUE_CHANGED", payload))
                }
            }

            "settings.OPEN_FOLDER" -> {
                // We no longer know the path. We just ask the FileSystemFeature to open our sandbox.
                store.dispatch(this.name, Action("filesystem.OPEN_SYSTEM_FOLDER"))
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
        private val viewKey = "feature.settings.main"

        // CORRECTED: Use the new `stageViews` map.
        override val stageViews: Map<String, @Composable (Store) -> Unit> = mapOf(
            viewKey to { store ->
                SettingsView(
                    store = store,
                    onClose = { store.dispatch("settings.ui", Action("core.SHOW_DEFAULT_VIEW")) }
                )
            }
        )

        // CORRECTED: Use the new `RibbonContent` slot.
        @Composable
        override fun RibbonContent(store: Store, activeViewKey: String?) {
            val isActive = activeViewKey == viewKey
            val payload = buildJsonObject { put("key", viewKey) }
            IconButton(onClick = { store.dispatch("settings.ui", Action("core.SET_ACTIVE_VIEW", payload)) }) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}