package app.auf.feature.core

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.auf.core.*
import app.auf.util.PlatformDependencies
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * The explicit lifecycle state of the application.
 * Managed exclusively by the CoreFeature in response to Actions from the main host.
 */
enum class AppLifecycle {
    BOOTING,                // The initial state before any actions are dispatched.
    INITIALIZING,           // The stage for registering settings and loading from disk.
    RUNNING,                // The application is fully hydrated and operational.
    CLOSING                 // The application is shutting down.
}

@Serializable
data class CoreState(
    val toastMessage: String? = null,
    val activeViewKey: String = "feature.session.main",
    val defaultViewKey: String = "feature.session.main",
    val lifecycle: AppLifecycle = AppLifecycle.BOOTING,
    // Add window dimensions to the state with sensible defaults.
    val windowWidth: Int = 1200,
    val windowHeight: Int = 800
) : FeatureState

/**
 * A headless feature that acts as the recorder for core application state,
 * including the application lifecycle, UI navigation, and window size.
 * It also demonstrates how a feature can register its own settings.
 *
 */
class CoreFeature(
    private val platformDependencies: PlatformDependencies
) : Feature {
    override val name: String = "core"
    override val composableProvider: Feature.ComposableProvider = CoreComposableProvider()

    // Payloads for serialization
    @Serializable private data class SetActiveViewPayload(val key: String)
    @Serializable private data class ShowToastPayload(val message: String)
    @Serializable private data class CopyToClipboardPayload(val text: String)
    @Serializable private data class UpdateWindowSizePayload(val width: Int, val height: Int)

    // Define keys for settings to avoid magic strings
    private val settingKeyWidth = "core.window.width"
    private val settingKeyHeight = "core.window.height"

    override fun onAction(action: Action, store: Store) {
        when (action.name) {
            "system.INITIALIZING" -> {
                // Register our window settings with the SettingsFeature
                store.dispatch(
                    this.name,
                    Action("settings.ADD", buildJsonObject {
                        put("key", settingKeyWidth)
                        put("type", "NUMERIC_LONG") // SettingsView will render a text field
                        put("label", "Window Width")
                        put("description", "The width of the application window in pixels.")
                        put("section", "Appearance")
                        put("defaultValue", "1200")
                    })
                )
                store.dispatch(
                    this.name,
                    Action("settings.ADD", buildJsonObject {
                        put("key", settingKeyHeight)
                        put("type", "NUMERIC_LONG")
                        put("label", "Window Height")
                        put("description", "The height of the application window in pixels.")
                        put("section", "Appearance")
                        put("defaultValue", "800")
                    })
                )
            }
            "core.UPDATE_WINDOW_SIZE" -> {
                // AFTER our reducer has updated our state, dispatch actions to the
                // SettingsFeature to persist the new values.
                val coreState = store.state.value.featureStates[name] as? CoreState
                coreState?.let {
                    store.dispatch(this.name, Action("settings.UPDATE", buildJsonObject {
                        put("key", settingKeyWidth)
                        put("value", it.windowWidth.toString())
                    }))
                    store.dispatch(this.name, Action("settings.UPDATE", buildJsonObject {
                        put("key", settingKeyHeight)
                        put("value", it.windowHeight.toString())
                    }))
                }
            }
            "core.OPEN_LOGS_FOLDER" -> {
                // CORRECTED: We no longer know the path. We ask the FileSystemFeature to open it.
                store.dispatch(this.name, Action("filesystem.OPEN_APP_SUBFOLDER", buildJsonObject {
                    put("folder", "logs")
                }))
            }
            "core.COPY_TO_CLIPBOARD" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<CopyToClipboardPayload>(it) }
                payload?.let {
                    platformDependencies.copyToClipboard(it.text)
                    val toastPayload = buildJsonObject { put("message", "Copied to clipboard.") }
                    store.dispatch(this.name, Action("core.SHOW_TOAST", toastPayload))
                }
            }
        }
    }


    override fun reducer(state: AppState, action: Action): AppState {
        val coreState = state.featureStates[name] as? CoreState ?: CoreState()
        var newCoreState = coreState

        when (action.name) {
            // Lifecycle Actions
            "system.INITIALIZING" -> {
                newCoreState = coreState.copy(lifecycle = AppLifecycle.INITIALIZING)
            }
            "system.STARTING" -> {
                newCoreState = coreState.copy(lifecycle = AppLifecycle.RUNNING)
            }
            "system.CLOSING" -> {
                newCoreState = coreState.copy(lifecycle = AppLifecycle.CLOSING)
            }

            // UI Actions
            "core.SET_ACTIVE_VIEW" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<SetActiveViewPayload>(it) }
                newCoreState = payload?.let { coreState.copy(activeViewKey = it.key) } ?: coreState
            }
            "core.SHOW_DEFAULT_VIEW" -> {
                newCoreState = coreState.copy(activeViewKey = coreState.defaultViewKey)
            }
            "core.UPDATE_WINDOW_SIZE" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<UpdateWindowSizePayload>(it) }
                newCoreState = payload?.let {
                    coreState.copy(windowWidth = it.width, windowHeight = it.height)
                } ?: coreState
            }
            "core.SHOW_TOAST" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<ShowToastPayload>(it) }
                newCoreState = payload?.let { coreState.copy(toastMessage = it.message) } ?: coreState
            }
            "core.CLEAR_TOAST" -> newCoreState = coreState.copy(toastMessage = null)

            // Cross-Feature Reaction
            "settings.LOADED" -> {
                // When settings are loaded, check if our keys are present and hydrate our state.
                val loadedValues = action.payload
                val width = loadedValues?.get(settingKeyWidth)?.jsonPrimitive?.content?.toIntOrNull()
                val height = loadedValues?.get(settingKeyHeight)?.jsonPrimitive?.content?.toIntOrNull()

                newCoreState = coreState.copy(
                    windowWidth = width ?: coreState.windowWidth,
                    windowHeight = height ?: coreState.windowHeight
                )
            }
            "settings.VALUE_CHANGED" -> {
                // When a setting is changed via the UI, listen for the broadcast and update our state.
                val payload = action.payload ?: return state
                val key = payload["key"]?.jsonPrimitive?.content
                val value = payload["value"]?.jsonPrimitive?.content

                when (key) {
                    settingKeyWidth -> {
                        value?.toIntOrNull()?.let { newWidth ->
                            if (newWidth != coreState.windowWidth) {
                                newCoreState = coreState.copy(windowWidth = newWidth)
                            }
                        }
                    }
                    settingKeyHeight -> {
                        value?.toIntOrNull()?.let { newHeight ->
                            if (newHeight != coreState.windowHeight) {
                                newCoreState = coreState.copy(windowHeight = newHeight)
                            }
                        }
                    }
                }
            }
        }

        return if (newCoreState != coreState) {
            state.copy(featureStates = state.featureStates + (name to newCoreState))
        } else {
            state
        }
    }

    inner class CoreComposableProvider : Feature.ComposableProvider {
        // CORRECTED: Use the new `stageViews` map.
        override val stageViews: Map<String, @Composable (Store) -> Unit> = mapOf(
            "feature.core.about" to { store -> AboutView(store) }
        )

        @Composable
        override fun MenuContent(store: Store, onDismiss: () -> Unit) {
            DropdownMenuItem(
                text = { Text("About") },
                onClick = {
                    val payload = buildJsonObject { put("key", "feature.core.about") }
                    store.dispatch("core.ui", Action("core.SET_ACTIVE_VIEW", payload))
                    onDismiss()
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "About Application"
                    )
                }
            )
        }
    }
}