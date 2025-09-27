package app.auf.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.Feature
import app.auf.core.FeatureState
import app.auf.core.Store
import app.auf.util.PlatformDependencies
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put


/**
 * A feature that provides the UI and persistence logic for all application settings.
 * It discovers settings by listening for 'settings.ADD_DEFINITION'
 * actions and stores the raw JSON payload of the setting definition.
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

    override fun reducer(state: AppState, action: Action): AppState {
        val settingsState = state.featureStates[name] as? SettingsState ?: SettingsState()
        var newSettingsState = settingsState

        when (action.name) {
            "settings.ADD_DEFINITION" -> {
                action.payload?.let { definitionJson ->
                    val key = definitionJson["key"]?.jsonPrimitive?.content
                    if (key != null && settingsState.definitions.none { it["key"]?.jsonPrimitive?.content == key }) {
                        newSettingsState = settingsState.copy(
                            definitions = settingsState.definitions + definitionJson
                        )
                    }
                }
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
            val coreState = store.state.value.featureStates["CoreFeature"] as? app.auf.feature.core.CoreState
            val defaultViewKey = coreState?.defaultViewKey ?: "feature.session.main"
            val closeActionPayload = buildJsonObject { put("key", defaultViewKey) }

            SettingsView(
                store = store,
                onClose = { store.dispatch(Action("core.SET_ACTIVE_VIEW", closeActionPayload)) }
            )
        }
    }
}