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
    val values: Map<String, String> = emptyMap(),
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

    override fun onPrivateData(data: Any, store: Store) {
        val payload = data as? JsonObject ?: return
        if (payload["subpath"]?.jsonPrimitive?.content == settingsFileName) {
            val loadedValues = payload["content"]?.jsonPrimitive?.contentOrNull?.let {
                try { Json.decodeFromString<Map<String, String>>(it) } catch (e: Exception) { emptyMap() }
            } ?: emptyMap()
            store.dispatch(this.name, Action("settings.publish.LOADED", buildJsonObject {
                loadedValues.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
            }))
        }
    }

    override fun onAction(action: Action, store: Store) {
        when (action.name) {
            "system.INITIALIZING" -> store.dispatch(this.name, Action("filesystem.SYSTEM_READ", buildJsonObject { put("subpath", settingsFileName) }))
            "settings.INPUT_CHANGED" -> {
                val key = action.payload?.get("key")?.jsonPrimitive?.content ?: return
                val value = action.payload.get("value")?.jsonPrimitive?.content ?: return
                debounceJobs[key]?.cancel()
                debounceJobs[key] = featureScope.launch {
                    delay(750L)
                    store.dispatch(name, Action("settings.UPDATE", buildJsonObject { put("key", key); put("value", value) }))
                }
            }
            "settings.UPDATE" -> {
                val latestSettingsState = store.state.value.featureStates[name] as? SettingsState ?: return
                store.dispatch(this.name, Action("filesystem.SYSTEM_WRITE", buildJsonObject {
                    put("subpath", settingsFileName)
                    put("content", Json.encodeToString(latestSettingsState.values))
                    put("encrypt", true)
                }))
                action.payload?.let { store.dispatch(this.name, Action("settings.publish.VALUE_CHANGED", it)) }
            }
            "settings.OPEN_FOLDER" -> store.dispatch(this.name, Action("filesystem.OPEN_SYSTEM_FOLDER"))
        }
    }

    override fun reducer(state: AppState, action: Action): AppState {
        val currentFeatureState = state.featureStates[name] as? SettingsState ?: SettingsState()
        var newFeatureState: SettingsState? = null
        val payload = action.payload
        when (action.name) {
            "settings.ADD" -> {
                val key = payload?.get("key")?.jsonPrimitive?.content ?: return state
                val defaultValue = payload["defaultValue"]?.jsonPrimitive?.content ?: return state
                if (currentFeatureState.definitions.none { it["key"]?.jsonPrimitive?.content == key }) {
                    val newValues = if (currentFeatureState.values.containsKey(key)) currentFeatureState.values else currentFeatureState.values + (key to defaultValue)
                    newFeatureState = currentFeatureState.copy(definitions = currentFeatureState.definitions + payload, values = newValues)
                }
            }
            "settings.publish.LOADED" -> {
                val loadedValues = payload?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                val allDefaults = currentFeatureState.definitions.associate {
                    it["key"]!!.jsonPrimitive.content to it["defaultValue"]!!.jsonPrimitive.content
                }
                val finalValues = allDefaults + loadedValues
                newFeatureState = currentFeatureState.copy(values = finalValues, inputValues = finalValues)
            }
            "settings.INPUT_CHANGED" -> {
                val key = payload?.get("key")?.jsonPrimitive?.content ?: return state
                val value = payload["value"]?.jsonPrimitive?.content ?: return state
                newFeatureState = currentFeatureState.copy(inputValues = currentFeatureState.inputValues + (key to value))
            }
            "settings.UPDATE" -> {
                val key = payload?.get("key")?.jsonPrimitive?.content ?: return state
                val value = payload["value"]?.jsonPrimitive?.content ?: return state
                newFeatureState = currentFeatureState.copy(
                    values = currentFeatureState.values + (key to value),
                    inputValues = currentFeatureState.inputValues + (key to value)
                )
            }
        }
        return newFeatureState?.let { if (it != currentFeatureState) state.copy(featureStates = state.featureStates + (name to it)) else state } ?: state
    }

    inner class SettingsComposableProvider : Feature.ComposableProvider {
        private val viewKey = "feature.settings.main"
        override val stageViews: Map<String, @Composable (Store, List<Feature>) -> Unit> = mapOf(
            viewKey to { store, _ ->
                SettingsView(store = store, onClose = { store.dispatch("settings.ui", Action("core.SHOW_DEFAULT_VIEW")) })
            }
        )
        @Composable override fun RibbonContent(store: Store, activeViewKey: String?) {
            val isActive = activeViewKey == viewKey
            IconButton(onClick = { store.dispatch("settings.ui", Action("core.SET_ACTIVE_VIEW", buildJsonObject { put("key", viewKey) })) }) {
                Icon(Icons.Default.Settings, "Settings", tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}