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
import app.auf.core.generated.ActionRegistry
import app.auf.core.generated.ActionNames
import app.auf.util.PlatformDependencies
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class CoreFeature(
    private val platformDependencies: PlatformDependencies
) : Feature {
    override val identity = Identity(uuid = null, localHandle = "core", handle = "core", name = "Core")
    override val composableProvider: Feature.ComposableProvider = CoreComposableProvider()

    // --- Private, serializable data classes for decoding action payloads safely. ---
    @Serializable private data class SetActiveViewPayload(val key: String)
    @Serializable private data class ShowToastPayload(val message: String)
    @Serializable private data class CopyToClipboardPayload(val text: String)
    @Serializable private data class UpdateWindowSizePayload(val width: Int, val height: Int)
    @Serializable private data class AddUserIdentityPayload(val name: String)
    @Serializable private data class IdentityIdPayload(val id: String)
    @Serializable private data class IdentitiesLoadedPayload(val identities: List<Identity>, val activeId: String? = null)
    @Serializable private data class DismissConfirmationPayload(val confirmed: Boolean)

    // --- Identity registration payload classes ---
    // Note: no parentHandle field — the originator IS the parent (enforced by design).
    @Serializable private data class RegisterIdentityPayload(
        val localHandle: String,
        val name: String
    )
    @Serializable private data class UnregisterIdentityPayload(val handle: String)

    private val settingKeyWidth = "core.window.width"
    private val settingKeyHeight = "core.window.height"
    private val identitiesFileName = "identities.json"

    companion object {
        /**
         * Validates that a localHandle matches [a-z][a-z0-9-]* — must start with a letter,
         * then only lowercase letters, digits, and hyphens. No dots (the dot is the
         * hierarchy separator in full handles).
         */
        private val LOCAL_HANDLE_REGEX = Regex("^[a-z][a-z0-9-]*$")

        fun isValidLocalHandle(localHandle: String): Boolean =
            LOCAL_HANDLE_REGEX.matches(localHandle)
    }

    /**
     * Constructs the full bus address from parent handle and local handle.
     * Root identities (parentHandle == null) use localHandle directly.
     */
    private fun constructHandle(parentHandle: String?, localHandle: String): String =
        if (parentHandle != null) "$parentHandle.$localHandle" else localHandle

    /**
     * Deduplicates a localHandle among siblings (identities sharing the same parentHandle).
     * Appends -2, -3, etc. if the resulting full handle is already taken.
     * Returns the (possibly modified) localHandle.
     */
    private fun deduplicateLocalHandle(
        localHandle: String,
        parentHandle: String?,
        existingRegistry: Map<String, Identity>
    ): String {
        val candidate = constructHandle(parentHandle, localHandle)
        if (candidate !in existingRegistry) return localHandle
        var counter = 2
        while (constructHandle(parentHandle, "$localHandle-$counter") in existingRegistry) {
            counter++
        }
        return "$localHandle-$counter"
    }

    override fun onPrivateData(envelope: PrivateDataEnvelope, store: Store) {
        when (envelope.type) {
            ActionNames.Envelopes.FILESYSTEM_RESPONSE_READ -> {
                if (envelope.payload["subpath"]?.jsonPrimitive?.content == identitiesFileName) {
                    val content = envelope.payload["content"]?.jsonPrimitive?.contentOrNull
                    if (content != null) {
                        try {
                            val loaded = Json.decodeFromString<IdentitiesLoadedPayload>(content)
                            store.deferredDispatch(identity.handle, Action(ActionNames.CORE_INTERNAL_IDENTITIES_LOADED, Json.encodeToJsonElement(loaded) as JsonObject))
                        } catch (e: Exception) {
                            platformDependencies.log(app.auf.util.LogLevel.ERROR, identity.handle, "Failed to parse identities.json: ${e.message}")
                        }
                    } else {
                        store.deferredDispatch(identity.handle, Action(ActionNames.CORE_INTERNAL_IDENTITIES_LOADED, buildJsonObject {
                            put("identities", buildJsonArray { })
                        }))
                    }
                }
            }
        }
    }

    override fun onAction(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {
        val latestCoreState = newState as? CoreState
        val prevCoreState = previousState as? CoreState

        when (action.name) {
            ActionNames.SYSTEM_PUBLISH_INITIALIZING -> {
                store.deferredDispatch(identity.handle, Action(ActionNames.SETTINGS_ADD, buildJsonObject {
                    put("key", settingKeyWidth); put("type", "NUMERIC_LONG"); put("label", "Window Width")
                    put("description", "The width of the application window in pixels.")
                    put("section", "Appearance"); put("defaultValue", "1200")
                }))
                store.deferredDispatch(identity.handle, Action(ActionNames.SETTINGS_ADD, buildJsonObject {
                    put("key", settingKeyHeight); put("type", "NUMERIC_LONG"); put("label", "Window Height")
                    put("description", "The height of the application window in pixels.")
                    put("section", "Appearance"); put("defaultValue", "800")
                }))
            }
            ActionNames.SYSTEM_PUBLISH_STARTING -> {
                store.deferredDispatch(identity.handle, Action(ActionNames.FILESYSTEM_SYSTEM_READ, buildJsonObject { put("subpath", identitiesFileName)}))
            }
            ActionNames.CORE_DISMISS_CONFIRMATION_DIALOG -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<DismissConfirmationPayload>(it) } ?: return
                val request = prevCoreState?.confirmationRequest ?: return

                val responsePayload = buildJsonObject {
                    put("requestId", request.requestId)
                    put("confirmed", payload.confirmed)
                }
                val envelope = PrivateDataEnvelope(ActionNames.Envelopes.CORE_RESPONSE_CONFIRMATION, responsePayload)
                store.deliverPrivateData(identity.handle, request.originator, envelope)
            }
            ActionNames.CORE_UPDATE_WINDOW_SIZE -> {
                latestCoreState?.let {
                    store.deferredDispatch(identity.handle, Action(ActionNames.SETTINGS_UPDATE, buildJsonObject {
                        put("key", settingKeyWidth); put("value", it.windowWidth.toString())
                    }))
                    store.deferredDispatch(identity.handle, Action(ActionNames.SETTINGS_UPDATE, buildJsonObject {
                        put("key", settingKeyHeight); put("value", it.windowHeight.toString())
                    }))
                }
            }
            ActionNames.CORE_OPEN_LOGS_FOLDER -> {
                store.deferredDispatch(identity.handle, Action(ActionNames.FILESYSTEM_OPEN_APP_SUBFOLDER, buildJsonObject {
                    put("folder", "logs")
                }))
            }
            ActionNames.CORE_COPY_TO_CLIPBOARD -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<CopyToClipboardPayload>(it) }
                payload?.let {
                    platformDependencies.copyToClipboard(it.text)
                    store.deferredDispatch(identity.handle, Action(ActionNames.CORE_SHOW_TOAST, buildJsonObject { put("message", "Copied to clipboard.") }))
                }
            }
            ActionNames.CORE_ADD_USER_IDENTITY,
            ActionNames.CORE_REMOVE_USER_IDENTITY,
            ActionNames.CORE_SET_ACTIVE_USER_IDENTITY,
            ActionNames.CORE_INTERNAL_IDENTITIES_LOADED -> {
                if (latestCoreState != null) {
                    persistAndBroadcastIdentities(latestCoreState, store)
                }
            }

            // --- Identity Registry Management ---
            ActionRegistry.Names.CORE_REGISTER_IDENTITY -> {
                // The reducer already handled validation, dedup, and storage.
                // Now we need to:
                // 1. Send a targeted response to the originator with the result
                // 2. Broadcast that the registry changed

                val originator = action.originator ?: return
                val requestedLocalHandle = action.payload?.get("localHandle")?.jsonPrimitive?.contentOrNull

                // Determine result by comparing previous and current registry
                val prevRegistry = prevCoreState?.identityRegistry ?: emptyMap()
                val newRegistry = latestCoreState?.identityRegistry ?: emptyMap()
                val addedEntries = newRegistry.keys - prevRegistry.keys

                if (addedEntries.isNotEmpty()) {
                    // Success — the new identity was added
                    val addedHandle = addedEntries.first()
                    val addedIdentity = newRegistry[addedHandle]!!

                    // Targeted response to the originator: here's what was approved
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.CORE_RESPONSE_REGISTER_IDENTITY,
                        buildJsonObject {
                            put("success", true)
                            put("requestedLocalHandle", requestedLocalHandle ?: "")
                            put("approvedLocalHandle", addedIdentity.localHandle)
                            put("handle", addedIdentity.handle)
                            put("uuid", addedIdentity.uuid)
                            put("name", addedIdentity.name)
                            put("parentHandle", addedIdentity.parentHandle ?: "")
                        }
                    ))

                    // Broadcast registry update
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.CORE_IDENTITY_REGISTRY_UPDATED
                    ))
                } else {
                    // Registration was rejected by the reducer (validation failed).
                    // Send failure response — the reducer already logged the reason.
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.CORE_RESPONSE_REGISTER_IDENTITY,
                        buildJsonObject {
                            put("success", false)
                            put("requestedLocalHandle", requestedLocalHandle ?: "")
                            put("error", "Registration rejected. Check logs for details.")
                        }
                    ))
                }
            }
            ActionRegistry.Names.CORE_UNREGISTER_IDENTITY -> {
                // Broadcast registry update if something actually changed
                val prevRegistry = prevCoreState?.identityRegistry ?: emptyMap()
                val newRegistry = latestCoreState?.identityRegistry ?: emptyMap()
                if (prevRegistry != newRegistry) {
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.CORE_IDENTITY_REGISTRY_UPDATED
                    ))
                }
            }
        }
    }

    private fun persistAndBroadcastIdentities(state: CoreState, store: Store) {
        val persistencePayload = IdentitiesLoadedPayload(state.userIdentities, state.activeUserId)
        store.deferredDispatch(identity.handle, Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
            put("subpath", identitiesFileName)
            put("content", Json.encodeToString(persistencePayload))
            put("encrypt", true)
        }))

        store.deferredDispatch(identity.handle, Action(ActionNames.CORE_PUBLISH_IDENTITIES_UPDATED, buildJsonObject {
            put("identities", Json.encodeToJsonElement(state.userIdentities))
            state.activeUserId?.let { put("activeId", it) }
        }))
    }

    override fun reducer(state: FeatureState?, action: Action): FeatureState? {
        val coreState = state as? CoreState ?: CoreState()
        val originator = action.originator ?: ""

        when (action.name) {
            ActionNames.SYSTEM_PUBLISH_INITIALIZING -> return coreState.copy(lifecycle = AppLifecycle.INITIALIZING)
            ActionNames.SYSTEM_PUBLISH_STARTING -> return coreState.copy(lifecycle = AppLifecycle.RUNNING)
            ActionNames.SYSTEM_PUBLISH_CLOSING -> return coreState.copy(lifecycle = AppLifecycle.CLOSING)
            ActionNames.CORE_SET_ACTIVE_VIEW -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<SetActiveViewPayload>(it) }
                return payload?.let { coreState.copy(activeViewKey = it.key) } ?: coreState
            }
            ActionNames.CORE_SHOW_DEFAULT_VIEW -> return coreState.copy(activeViewKey = coreState.defaultViewKey)
            ActionNames.CORE_UPDATE_WINDOW_SIZE -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<UpdateWindowSizePayload>(it) }
                return payload?.let { coreState.copy(windowWidth = it.width, windowHeight = it.height) } ?: coreState
            }
            ActionNames.CORE_SHOW_TOAST -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<ShowToastPayload>(it) }
                return payload?.let { coreState.copy(toastMessage = it.message) } ?: coreState
            }
            ActionNames.CORE_CLEAR_TOAST -> return coreState.copy(toastMessage = null)
            ActionNames.CORE_SHOW_CONFIRMATION_DIALOG -> {
                val request = action.payload?.let {
                    try {
                        Json.decodeFromJsonElement<ConfirmationDialogRequest>(it)
                    } catch (e: Exception) {
                        platformDependencies.log(app.auf.util.LogLevel.ERROR, identity.handle, "Failed to decode ConfirmationDialogRequest: ${e.message}")
                        null
                    }
                }
                return coreState.copy(confirmationRequest = request?.copy(originator = originator))
            }
            ActionNames.CORE_DISMISS_CONFIRMATION_DIALOG -> return coreState.copy(confirmationRequest = null)
            ActionNames.SETTINGS_PUBLISH_LOADED -> {
                val loadedValues = action.payload
                val width = loadedValues?.get(settingKeyWidth)?.jsonPrimitive?.content?.toIntOrNull()
                val height = loadedValues?.get(settingKeyHeight)?.jsonPrimitive?.content?.toIntOrNull()
                return coreState.copy(windowWidth = width ?: coreState.windowWidth, windowHeight = height ?: coreState.windowHeight)
            }
            ActionNames.SETTINGS_PUBLISH_VALUE_CHANGED -> {
                val payload = action.payload ?: return coreState
                val key = payload["key"]?.jsonPrimitive?.content
                val value = payload["value"]?.jsonPrimitive?.content
                var newCoreState = coreState
                when (key) {
                    settingKeyWidth -> value?.toIntOrNull()?.let { if (it != coreState.windowWidth) newCoreState = coreState.copy(windowWidth = it) }
                    settingKeyHeight -> value?.toIntOrNull()?.let { if (it != coreState.windowHeight) newCoreState = coreState.copy(windowHeight = it) }
                }
                return newCoreState
            }
            ActionNames.CORE_INTERNAL_IDENTITIES_LOADED -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<IdentitiesLoadedPayload>(it) } ?: return coreState
                if (payload.identities.isEmpty()) {
                    val defaultUser = Identity(platformDependencies.generateUUID(), name = "DefaultUser", handle = "default-user", localHandle = "default-user")
                    return coreState.copy(
                        userIdentities = listOf(defaultUser),
                        activeUserId = defaultUser.handle
                    )
                } else {
                    val activeId = if (payload.activeId in payload.identities.map { it.handle }) payload.activeId else payload.identities.first().handle
                    return coreState.copy(
                        userIdentities = payload.identities,
                        activeUserId = activeId
                    )
                }
            }
            ActionNames.CORE_ADD_USER_IDENTITY -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<AddUserIdentityPayload>(it) } ?: return coreState
                val newUser = Identity(platformDependencies.generateUUID(), localHandle = payload.name, handle=payload.name, name=payload.name)
                return coreState.copy(userIdentities = coreState.userIdentities + newUser)
            }
            ActionNames.CORE_REMOVE_USER_IDENTITY -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<IdentityIdPayload>(it) } ?: return coreState
                val updatedIdentities = coreState.userIdentities.filterNot { it.handle == payload.id }
                val updatedActiveId = if (coreState.activeUserId == payload.id) updatedIdentities.firstOrNull()?.handle else coreState.activeUserId
                return coreState.copy(userIdentities = updatedIdentities, activeUserId = updatedActiveId)
            }
            ActionNames.CORE_SET_ACTIVE_USER_IDENTITY -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<IdentityIdPayload>(it) } ?: return coreState
                if (coreState.userIdentities.any { it.handle == payload.id }) {
                    return coreState.copy(activeUserId = payload.id)
                }
                return coreState
            }

            // ================================================================
            // Identity Registry Management (Phase 2)
            // ================================================================

            ActionRegistry.Names.CORE_REGISTER_IDENTITY -> {
                val payload = action.payload?.let {
                    Json.decodeFromJsonElement<RegisterIdentityPayload>(it)
                }
                if (payload == null) {
                    platformDependencies.log(app.auf.util.LogLevel.ERROR, identity.handle,
                        "REGISTER_IDENTITY: missing or invalid payload")
                    return coreState
                }

                // Validate localHandle format: [a-z][a-z0-9-]*
                if (!isValidLocalHandle(payload.localHandle)) {
                    platformDependencies.log(app.auf.util.LogLevel.ERROR, identity.handle,
                        "REGISTER_IDENTITY: invalid localHandle '${payload.localHandle}' — " +
                                "must match [a-z][a-z0-9-]* (start with letter, then letters/digits/hyphens)")
                    return coreState
                }

                // The originator IS the parent — enforced by design.
                // No feature can register identities outside its own namespace.
                val parentHandle = originator.ifEmpty { null }

                // Validate that the parent actually exists in the registry
                if (parentHandle != null && parentHandle !in coreState.identityRegistry) {
                    platformDependencies.log(app.auf.util.LogLevel.ERROR, identity.handle,
                        "REGISTER_IDENTITY: originator '${parentHandle}' not found in identity registry")
                    return coreState
                }

                // Deduplicate among siblings (same parent namespace)
                val finalLocalHandle = deduplicateLocalHandle(
                    payload.localHandle, parentHandle, coreState.identityRegistry
                )
                val fullHandle = constructHandle(parentHandle, finalLocalHandle)

                val newIdentity = Identity(
                    uuid = platformDependencies.generateUUID(),
                    localHandle = finalLocalHandle,
                    handle = fullHandle,
                    name = payload.name,
                    parentHandle = parentHandle,
                    registeredAt = platformDependencies.currentTimeMillis()
                )

                return coreState.copy(
                    identityRegistry = coreState.identityRegistry + (fullHandle to newIdentity)
                )
            }

            ActionRegistry.Names.CORE_UNREGISTER_IDENTITY -> {
                val payload = action.payload?.let {
                    Json.decodeFromJsonElement<UnregisterIdentityPayload>(it)
                }
                if (payload == null) {
                    platformDependencies.log(app.auf.util.LogLevel.ERROR, identity.handle,
                        "UNREGISTER_IDENTITY: missing or invalid payload")
                    return coreState
                }

                if (payload.handle !in coreState.identityRegistry) {
                    platformDependencies.log(app.auf.util.LogLevel.WARN, identity.handle,
                        "UNREGISTER_IDENTITY: handle '${payload.handle}' not in registry")
                    return coreState
                }

                // Namespace enforcement: originator can only unregister identities
                // within its own namespace (handle must start with originator + ".")
                // or the exact handle itself (unregister self).
                val isOwnNamespace = payload.handle == originator ||
                        payload.handle.startsWith("$originator.")
                if (!isOwnNamespace) {
                    platformDependencies.log(app.auf.util.LogLevel.ERROR, identity.handle,
                        "UNREGISTER_IDENTITY: originator '$originator' cannot unregister " +
                                "'${payload.handle}' — outside its namespace")
                    return coreState
                }

                // Cascade: remove the identity and all its descendants.
                // Any identity whose handle starts with "removedHandle." is a descendant.
                val handlePrefix = "${payload.handle}."
                val handlesToRemove = coreState.identityRegistry.keys.filter { key ->
                    key == payload.handle || key.startsWith(handlePrefix)
                }.toSet()

                return coreState.copy(
                    identityRegistry = coreState.identityRegistry - handlesToRemove
                )
            }

            else -> return coreState
        }
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