package app.auf.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import app.auf.core.*
import app.auf.core.generated.ActionRegistry
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
    override val identity: Identity = Identity(uuid = null, handle = "settings", localHandle = "settings", name="Settings")
    override val composableProvider: Feature.ComposableProvider = SettingsComposableProvider()

    private val featureScope = CoroutineScope(Dispatchers.Default)
    private val debounceJobs = mutableMapOf<String, Job>()
    private val settingsFileName = "settings.json"

    override fun handleSideEffects(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {
        when (action.name) {
            // Phase 3: Targeted response from FilesystemFeature — migrated from onPrivateData.
            ActionRegistry.Names.FILESYSTEM_RETURN_READ -> {
                val payload = action.payload ?: return
                if (payload["path"]?.jsonPrimitive?.content == settingsFileName) {
                    val loadedValues = payload["content"]?.jsonPrimitive?.contentOrNull?.let {
                        try { Json.decodeFromString<Map<String, String>>(it) } catch (e: Exception) { emptyMap() }
                    } ?: emptyMap()
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SETTINGS_LOADED, buildJsonObject {
                        loadedValues.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                    }))
                }
            }
            ActionRegistry.Names.SYSTEM_INITIALIZING -> store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_READ, buildJsonObject { put("path", settingsFileName) }))
            ActionRegistry.Names.SETTINGS_UI_INPUT_CHANGED -> {
                val key = action.payload?.get("key")?.jsonPrimitive?.content ?: return
                val value = action.payload.get("value")?.jsonPrimitive?.content ?: return
                debounceJobs[key]?.cancel()
                debounceJobs[key] = featureScope.launch {
                    delay(750L)
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SETTINGS_UPDATE, buildJsonObject { put("key", key); put("value", value) }))
                }
            }
            ActionRegistry.Names.SETTINGS_UPDATE -> {
                val latestSettingsState = newState as? SettingsState ?: return
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_WRITE, buildJsonObject {
                    put("path", settingsFileName)
                    put("content", Json.encodeToString(latestSettingsState.values))
                    put("encrypt", true)
                }))
                action.payload?.let { store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SETTINGS_VALUE_CHANGED, it)) }
            }
            ActionRegistry.Names.SETTINGS_OPEN_FOLDER -> store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_OPEN_WORKSPACE_FOLDER))
        }
    }

    override fun reducer(state: FeatureState?, action: Action): FeatureState? {
        val currentFeatureState = state as? SettingsState ?: SettingsState()
        val payload = action.payload

        when (action.name) {
            ActionRegistry.Names.SETTINGS_ADD -> {
                val key = payload?.get("key")?.jsonPrimitive?.content ?: return currentFeatureState
                val defaultValue = payload["defaultValue"]?.jsonPrimitive?.content ?: return currentFeatureState
                if (currentFeatureState.definitions.none { it["key"]?.jsonPrimitive?.content == key }) {
                    val newValues = if (currentFeatureState.values.containsKey(key)) currentFeatureState.values else currentFeatureState.values + (key to defaultValue)
                    return currentFeatureState.copy(definitions = currentFeatureState.definitions + payload, values = newValues)
                }
            }
            ActionRegistry.Names.SETTINGS_LOADED -> {
                val loadedValues = payload?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                val allDefaults = currentFeatureState.definitions.associate {
                    it["key"]!!.jsonPrimitive.content to it["defaultValue"]!!.jsonPrimitive.content
                }
                val finalValues = allDefaults + loadedValues
                return currentFeatureState.copy(values = finalValues, inputValues = finalValues)
            }
            ActionRegistry.Names.SETTINGS_UI_INPUT_CHANGED -> {
                val key = payload?.get("key")?.jsonPrimitive?.content ?: return currentFeatureState
                val value = payload["value"]?.jsonPrimitive?.content ?: return currentFeatureState
                return currentFeatureState.copy(inputValues = currentFeatureState.inputValues + (key to value))
            }
            ActionRegistry.Names.SETTINGS_UPDATE -> {
                val key = payload?.get("key")?.jsonPrimitive?.content ?: return currentFeatureState
                val value = payload["value"]?.jsonPrimitive?.content ?: return currentFeatureState
                return currentFeatureState.copy(
                    values = currentFeatureState.values + (key to value),
                    inputValues = currentFeatureState.inputValues + (key to value)
                )
            }
        }
        return currentFeatureState
    }

    inner class SettingsComposableProvider : Feature.ComposableProvider {
        private val viewKey = "feature.settings.main"
        override val stageViews: Map<String, @Composable (Store, List<Feature>) -> Unit> = mapOf(
            viewKey to { store, _ ->
                SettingsView(store = store, onClose = { store.dispatch("settings", Action(ActionRegistry.Names.CORE_SHOW_DEFAULT_VIEW)) })
            }
        )
        @Composable override fun RibbonContent(store: Store, activeViewKey: String?) {
            val isActive = activeViewKey == viewKey
            IconButton(onClick = { store.dispatch("settings", Action(ActionRegistry.Names.CORE_SET_ACTIVE_VIEW, buildJsonObject { put("key", viewKey) })) }) {
                Icon(Icons.Default.Settings, "Settings", tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}