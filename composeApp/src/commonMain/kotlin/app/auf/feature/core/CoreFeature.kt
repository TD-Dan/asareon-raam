package app.auf.feature.core

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.auf.core.*
import app.auf.core.generated.ActionNames
import app.auf.util.PlatformDependencies
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
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
    val windowHeight: Int = 800,
    // NEW: User Identity Management State
    val userIdentities: List<Identity> = emptyList(),
    val activeUserId: String? = null
) : FeatureState

class CoreFeature(
    private val platformDependencies: PlatformDependencies
) : Feature {
    override val name: String = "core"
    override val composableProvider: Feature.ComposableProvider = CoreComposableProvider()

    // --- Private, serializable data classes for decoding action payloads safely. ---
    @Serializable private data class SetActiveViewPayload(val key: String)
    @Serializable private data class ShowToastPayload(val message: String)
    @Serializable private data class CopyToClipboardPayload(val text: String)
    @Serializable private data class UpdateWindowSizePayload(val width: Int, val height: Int)
    @Serializable private data class AddUserIdentityPayload(val name: String)
    @Serializable private data class IdentityIdPayload(val id: String)
    @Serializable private data class IdentitiesLoadedPayload(val identities: List<Identity>, val activeId: String? = null)

    private val settingKeyWidth = "core.window.width"
    private val settingKeyHeight = "core.window.height"
    private val identitiesFileName = "identities.json"

    override fun onPrivateData(envelope: PrivateDataEnvelope, store: Store) {
        when (envelope.type) {
            "filesystem.response.read" -> {
                if (envelope.payload["subpath"]?.jsonPrimitive?.content == identitiesFileName) {
                    val content = envelope.payload["content"]?.jsonPrimitive?.contentOrNull
                    if (content != null) {
                        try {
                            val loaded = Json.decodeFromString<IdentitiesLoadedPayload>(content)
                            store.dispatch(this.name, Action(ActionNames.CORE_INTERNAL_IDENTITIES_LOADED, Json.encodeToJsonElement(loaded) as JsonObject))
                        } catch (e: Exception) {
                            platformDependencies.log(app.auf.util.LogLevel.ERROR, name, "Failed to parse identities.json: ${e.message}")
                        }
                    } else {
                        store.dispatch(this.name, Action(ActionNames.CORE_INTERNAL_IDENTITIES_LOADED, buildJsonObject {
                            put("identities", buildJsonArray { })
                        }))
                    }
                }
            }
        }
    }

    override fun onAction(action: Action, store: Store, previousState: AppState) {
        when (action.name) {
            ActionNames.SYSTEM_PUBLISH_INITIALIZING -> {
                store.dispatch(this.name, Action(ActionNames.SETTINGS_ADD, buildJsonObject {
                    put("key", settingKeyWidth); put("type", "NUMERIC_LONG"); put("label", "Window Width")
                    put("description", "The width of the application window in pixels.")
                    put("section", "Appearance"); put("defaultValue", "1200")
                }))
                store.dispatch(this.name, Action(ActionNames.SETTINGS_ADD, buildJsonObject {
                    put("key", settingKeyHeight); put("type", "NUMERIC_LONG"); put("label", "Window Height")
                    put("description", "The height of the application window in pixels.")
                    put("section", "Appearance"); put("defaultValue", "800")
                }))
            }
            ActionNames.SYSTEM_PUBLISH_STARTING -> {
                store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_READ, buildJsonObject { put("subpath", identitiesFileName)}))
            }
            ActionNames.CORE_UPDATE_WINDOW_SIZE -> {
                val coreState = store.state.value.featureStates[name] as? CoreState
                coreState?.let {
                    store.dispatch(this.name, Action(ActionNames.SETTINGS_UPDATE, buildJsonObject {
                        put("key", settingKeyWidth); put("value", it.windowWidth.toString())
                    }))
                    store.dispatch(this.name, Action(ActionNames.SETTINGS_UPDATE, buildJsonObject {
                        put("key", settingKeyHeight); put("value", it.windowHeight.toString())
                    }))
                }
            }
            ActionNames.CORE_OPEN_LOGS_FOLDER -> {
                store.dispatch(this.name, Action(ActionNames.FILESYSTEM_OPEN_APP_SUBFOLDER, buildJsonObject {
                    put("folder", "logs")
                }))
            }
            ActionNames.CORE_COPY_TO_CLIPBOARD -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<CopyToClipboardPayload>(it) }
                payload?.let {
                    platformDependencies.copyToClipboard(it.text)
                    store.dispatch(this.name, Action(ActionNames.CORE_SHOW_TOAST, buildJsonObject { put("message", "Copied to clipboard.") }))
                }
            }
            ActionNames.CORE_ADD_USER_IDENTITY,
            ActionNames.CORE_REMOVE_USER_IDENTITY,
            ActionNames.CORE_SET_ACTIVE_USER_IDENTITY,
            ActionNames.CORE_INTERNAL_IDENTITIES_LOADED -> {
                val latestState = store.state.value.featureStates[name] as? CoreState ?: return
                persistAndBroadcastIdentities(latestState, store)
            }
        }
    }

    private fun persistAndBroadcastIdentities(state: CoreState, store: Store) {
        val persistencePayload = IdentitiesLoadedPayload(state.userIdentities, state.activeUserId)
        store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
            put("subpath", identitiesFileName)
            put("content", Json.encodeToString(persistencePayload))
            put("encrypt", true)
        }))

        store.dispatch(this.name, Action(ActionNames.CORE_PUBLISH_IDENTITIES_UPDATED, buildJsonObject {
            put("identities", Json.encodeToJsonElement(state.userIdentities))
            state.activeUserId?.let { put("activeId", it) }
        }))
    }

    override fun reducer(state: AppState, action: Action): AppState {
        val coreState = state.featureStates[name] as? CoreState ?: CoreState()
        var newCoreState = coreState

        when (action.name) {
            ActionNames.SYSTEM_PUBLISH_INITIALIZING -> newCoreState = coreState.copy(lifecycle = AppLifecycle.INITIALIZING)
            ActionNames.SYSTEM_PUBLISH_STARTING -> newCoreState = coreState.copy(lifecycle = AppLifecycle.RUNNING)
            ActionNames.SYSTEM_PUBLISH_CLOSING -> newCoreState = coreState.copy(lifecycle = AppLifecycle.CLOSING)
            ActionNames.CORE_SET_ACTIVE_VIEW -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<SetActiveViewPayload>(it) }
                newCoreState = payload?.let { coreState.copy(activeViewKey = it.key) } ?: coreState
            }
            ActionNames.CORE_SHOW_DEFAULT_VIEW -> newCoreState = coreState.copy(activeViewKey = coreState.defaultViewKey)
            ActionNames.CORE_UPDATE_WINDOW_SIZE -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<UpdateWindowSizePayload>(it) }
                newCoreState = payload?.let { coreState.copy(windowWidth = it.width, windowHeight = it.height) } ?: coreState
            }
            ActionNames.CORE_SHOW_TOAST -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<ShowToastPayload>(it) }
                newCoreState = payload?.let { coreState.copy(toastMessage = it.message) } ?: coreState
            }
            ActionNames.CORE_CLEAR_TOAST -> newCoreState = coreState.copy(toastMessage = null)
            ActionNames.SETTINGS_PUBLISH_LOADED -> {
                val loadedValues = action.payload
                val width = loadedValues?.get(settingKeyWidth)?.jsonPrimitive?.content?.toIntOrNull()
                val height = loadedValues?.get(settingKeyHeight)?.jsonPrimitive?.content?.toIntOrNull()
                newCoreState = coreState.copy(windowWidth = width ?: coreState.windowWidth, windowHeight = height ?: coreState.windowHeight)
            }
            ActionNames.SETTINGS_PUBLISH_VALUE_CHANGED -> {
                val payload = action.payload ?: return state
                val key = payload["key"]?.jsonPrimitive?.content
                val value = payload["value"]?.jsonPrimitive?.content
                when (key) {
                    settingKeyWidth -> value?.toIntOrNull()?.let { if (it != coreState.windowWidth) newCoreState = coreState.copy(windowWidth = it) }
                    settingKeyHeight -> value?.toIntOrNull()?.let { if (it != coreState.windowHeight) newCoreState = coreState.copy(windowHeight = it) }
                }
            }
            ActionNames.CORE_INTERNAL_IDENTITIES_LOADED -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<IdentitiesLoadedPayload>(it) } ?: return state
                if (payload.identities.isEmpty()) {
                    val defaultUser = Identity(platformDependencies.generateUUID(), "User")
                    newCoreState = coreState.copy(
                        userIdentities = listOf(defaultUser),
                        activeUserId = defaultUser.id
                    )
                } else {
                    val activeId = if (payload.activeId in payload.identities.map { it.id }) payload.activeId else payload.identities.first().id
                    newCoreState = coreState.copy(
                        userIdentities = payload.identities,
                        activeUserId = activeId
                    )
                }
            }
            ActionNames.CORE_ADD_USER_IDENTITY -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<AddUserIdentityPayload>(it) } ?: return state
                val newUser = Identity(platformDependencies.generateUUID(), payload.name)
                newCoreState = coreState.copy(userIdentities = coreState.userIdentities + newUser)
            }
            ActionNames.CORE_REMOVE_USER_IDENTITY -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<IdentityIdPayload>(it) } ?: return state
                val updatedIdentities = coreState.userIdentities.filterNot { it.id == payload.id }
                val updatedActiveId = if (coreState.activeUserId == payload.id) updatedIdentities.firstOrNull()?.id else coreState.activeUserId
                newCoreState = coreState.copy(userIdentities = updatedIdentities, activeUserId = updatedActiveId)
            }
            ActionNames.CORE_SET_ACTIVE_USER_IDENTITY -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<IdentityIdPayload>(it) } ?: return state
                if (coreState.userIdentities.any { it.id == payload.id }) {
                    newCoreState = coreState.copy(activeUserId = payload.id)
                }
            }
        }
        return if (newCoreState != coreState) state.copy(featureStates = state.featureStates + (name to newCoreState)) else state
    }

    inner class CoreComposableProvider : Feature.ComposableProvider {
        private val viewKeyAbout = "feature.core.about"
        private val viewKeyIdentities = "feature.core.identities"

        override val stageViews: Map<String, @Composable (Store, List<Feature>) -> Unit> = mapOf(
            viewKeyAbout to { store, _ -> AboutView(store) },
            viewKeyIdentities to { store, _ -> IdentityManagerView(store) }
        )

        @Composable
        override fun RibbonContent(store: Store, activeViewKey: String?) {
            // NEW: Add a ribbon button for the Identity Manager
            IconButton(onClick = { store.dispatch("core.ui", Action(ActionNames.CORE_SET_ACTIVE_VIEW, buildJsonObject { put("key", viewKeyIdentities) })) }) {
                Icon(Icons.Default.Person, "Identity Manager", tint = if (activeViewKey == viewKeyIdentities) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        @Composable
        override fun MenuContent(store: Store, onDismiss: () -> Unit) {
            DropdownMenuItem(
                text = { Text("About") },
                onClick = {
                    store.dispatch("core.ui", Action(ActionNames.CORE_SET_ACTIVE_VIEW, buildJsonObject { put("key", viewKeyAbout) }))
                    onDismiss()
                },
                leadingIcon = { Icon(Icons.Default.Info, "About Application") }
            )
        }
    }
}