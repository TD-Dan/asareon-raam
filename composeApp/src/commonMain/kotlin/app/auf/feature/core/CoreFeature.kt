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
        val localHandle: String? = null,
        val name: String,
        val uuid: String? = null
    )
    @Serializable private data class UnregisterIdentityPayload(val handle: String)
    @Serializable private data class UpdateIdentityPayload(
        val handle: String,
        val newName: String
    )

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

        /**
         * Converts a human-readable name into a valid localHandle slug.
         * Rules: lowercase, replace non-[a-z0-9] with hyphens, collapse
         * consecutive hyphens, strip leading/trailing hyphens, ensure starts
         * with a letter. Falls back to "unnamed" if result is empty.
         */
        fun slugifyName(name: String): String {
            val slug = name.lowercase()
                .replace(Regex("[^a-z0-9]"), "-")
                .replace(Regex("-+"), "-")
                .trim('-')
            // Ensure starts with a letter (localHandle rule: [a-z][a-z0-9-]*)
            val safe = if (slug.isEmpty()) "unnamed"
            else if (!slug[0].isLetter()) "s-$slug"
            else slug
            return safe
        }
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

    /** Sends a failure response for REGISTER_IDENTITY back to the originator. */
    private fun sendRegistrationFailure(store: Store, originator: String, requestedLocalHandle: String, error: String, uuid: String? = null) {
        store.deferredDispatch(identity.handle, Action(
            ActionRegistry.Names.CORE_RETURN_REGISTER_IDENTITY,
            buildJsonObject {
                put("success", false)
                put("requestedLocalHandle", requestedLocalHandle)
                put("error", error)
                uuid?.let { put("uuid", it) }
            },
            targetRecipient = originator
        ))
    }

    /** Sends a failure response for UPDATE_IDENTITY back to the originator. */
    private fun sendUpdateFailure(store: Store, originator: String, handle: String, error: String) {
        store.deferredDispatch(identity.handle, Action(
            ActionRegistry.Names.CORE_RETURN_UPDATE_IDENTITY,
            buildJsonObject {
                put("success", false)
                put("oldHandle", handle)
                put("error", error)
            },
            targetRecipient = originator
        ))
    }

    override fun handleSideEffects(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {
        val latestCoreState = newState as? CoreState
        val prevCoreState = previousState as? CoreState

        when (action.name) {
            ActionRegistry.Names.SYSTEM_INITIALIZING -> {
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SETTINGS_ADD, buildJsonObject {
                    put("key", settingKeyWidth); put("type", "NUMERIC_LONG"); put("label", "Window Width")
                    put("description", "The width of the application window in pixels.")
                    put("section", "Appearance"); put("defaultValue", "1200")
                }))
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SETTINGS_ADD, buildJsonObject {
                    put("key", settingKeyHeight); put("type", "NUMERIC_LONG"); put("label", "Window Height")
                    put("description", "The height of the application window in pixels.")
                    put("section", "Appearance"); put("defaultValue", "800")
                }))
            }
            ActionRegistry.Names.SYSTEM_STARTING -> {
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_READ, buildJsonObject { put("subpath", identitiesFileName)}))
            }
            ActionRegistry.Names.CORE_DISMISS_CONFIRMATION_DIALOG -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<DismissConfirmationPayload>(it) } ?: return
                val request = prevCoreState?.confirmationRequest ?: return

                val responsePayload = buildJsonObject {
                    put("requestId", request.requestId)
                    put("confirmed", payload.confirmed)
                }
                store.deferredDispatch(identity.handle, Action(
                    name = ActionRegistry.Names.CORE_RETURN_CONFIRMATION,
                    payload = responsePayload,
                    targetRecipient = request.originator
                ))
            }
            ActionRegistry.Names.CORE_UPDATE_WINDOW_SIZE -> {
                latestCoreState?.let {
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SETTINGS_UPDATE, buildJsonObject {
                        put("key", settingKeyWidth); put("value", it.windowWidth.toString())
                    }))
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SETTINGS_UPDATE, buildJsonObject {
                        put("key", settingKeyHeight); put("value", it.windowHeight.toString())
                    }))
                }
            }
            ActionRegistry.Names.CORE_OPEN_LOGS_FOLDER -> {
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_OPEN_APP_SUBFOLDER, buildJsonObject {
                    put("folder", "logs")
                }))
            }
            ActionRegistry.Names.CORE_COPY_TO_CLIPBOARD -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<CopyToClipboardPayload>(it) }
                payload?.let {
                    platformDependencies.copyToClipboard(it.text)
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.CORE_SHOW_TOAST, buildJsonObject { put("message", "Copied to clipboard.") }))
                }
            }
            // Phase 3: Targeted response from FilesystemFeature — identity file loaded.
            // Migrated from onPrivateData.
            ActionRegistry.Names.FILESYSTEM_RETURN_READ -> {
                val data = action.payload ?: return
                if (data["subpath"]?.jsonPrimitive?.content == identitiesFileName) {
                    val content = data["content"]?.jsonPrimitive?.contentOrNull
                    if (content != null) {
                        try {
                            val loaded = Json.decodeFromString<IdentitiesLoadedPayload>(content)
                            store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.CORE_IDENTITIES_LOADED, Json.encodeToJsonElement(loaded) as JsonObject))
                        } catch (e: Exception) {
                            platformDependencies.log(app.auf.util.LogLevel.ERROR, identity.handle, "Failed to parse identities.json: ${e.message}")
                        }
                    } else {
                        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.CORE_IDENTITIES_LOADED, buildJsonObject {
                            put("identities", buildJsonArray { })
                        }))
                    }
                }
            }
            ActionRegistry.Names.CORE_ADD_USER_IDENTITY,
            ActionRegistry.Names.CORE_REMOVE_USER_IDENTITY,
            ActionRegistry.Names.CORE_SET_ACTIVE_USER_IDENTITY,
            ActionRegistry.Names.CORE_IDENTITIES_LOADED -> {
                if (latestCoreState != null) {
                    // Sync user identities to AppState.identityRegistry via Store.
                    // Build registry entries from the authoritative userIdentities list.
                    @Suppress("DEPRECATION")
                    val registryEntries = latestCoreState.userIdentities.associate { userIdentity ->
                        val localHandle = userIdentity.localHandle.ifBlank {
                            userIdentity.name.lowercase().replace(Regex("[^a-z0-9-]"), "-").trimStart('-').ifEmpty { "user" }
                        }
                        val fullHandle = if (userIdentity.handle.startsWith("core.")) userIdentity.handle else "core.$localHandle"
                        val registryIdentity = Identity(
                            uuid = userIdentity.uuid ?: platformDependencies.generateUUID(),
                            localHandle = localHandle,
                            handle = fullHandle,
                            name = userIdentity.name,
                            parentHandle = "core",
                            registeredAt = userIdentity.registeredAt.takeIf { it > 0 } ?: platformDependencies.currentTimeMillis()
                        )
                        fullHandle to registryIdentity
                    }

                    // Replace all "core.*" entries in the registry with the current set.
                    store.updateIdentityRegistry { current ->
                        val withoutCoreChildren = current.filterKeys { key ->
                            !key.startsWith("core.")
                        }
                        withoutCoreChildren + registryEntries
                    }

                    persistAndBroadcastIdentities(latestCoreState, store)
                    // Broadcast identity registry update so all features get the unified notification
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.CORE_IDENTITY_REGISTRY_UPDATED
                    ))
                }
            }

            // --- Identity Registry Management ---
            // Business logic lives here; storage is delegated to store.updateIdentityRegistry().
            ActionRegistry.Names.CORE_REGISTER_IDENTITY -> {
                val originator = action.originator ?: return
                val payload = action.payload?.let {
                    Json.decodeFromJsonElement<RegisterIdentityPayload>(it)
                }
                if (payload == null || payload.name.isBlank()) {
                    platformDependencies.log(app.auf.util.LogLevel.ERROR, identity.handle,
                        "REGISTER_IDENTITY: missing or invalid payload (name is required)")
                    sendRegistrationFailure(store, originator, "", "Missing or invalid payload")
                    return
                }

                // Derive or validate localHandle
                val requestedLocalHandle: String
                if (payload.localHandle != null) {
                    // Explicit localHandle provided — validate format
                    if (!isValidLocalHandle(payload.localHandle)) {
                        platformDependencies.log(app.auf.util.LogLevel.ERROR, identity.handle,
                            "REGISTER_IDENTITY: invalid localHandle '${payload.localHandle}' — " +
                                    "must match [a-z][a-z0-9-]* (start with letter, then letters/digits/hyphens)")
                        sendRegistrationFailure(store, originator, payload.localHandle, "Invalid localHandle format", payload.uuid)
                        return
                    }
                    requestedLocalHandle = payload.localHandle
                } else {
                    // No localHandle — generate from name
                    requestedLocalHandle = slugifyName(payload.name)
                }

                // Validate caller-provided UUID if present
                if (payload.uuid != null) {
                    try {
                        require(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
                            .matches(payload.uuid))
                    } catch (_: Exception) {
                        platformDependencies.log(app.auf.util.LogLevel.ERROR, identity.handle,
                            "REGISTER_IDENTITY: invalid UUID '${payload.uuid}'")
                        sendRegistrationFailure(store, originator, requestedLocalHandle, "Invalid UUID format", payload.uuid)
                        return
                    }
                }

                // The originator IS the parent — enforced by design.
                val parentHandle = originator.ifEmpty { null }
                val registry = store.state.value.identityRegistry

                // Validate that the parent actually exists in the registry
                if (parentHandle != null && parentHandle !in registry) {
                    platformDependencies.log(app.auf.util.LogLevel.ERROR, identity.handle,
                        "REGISTER_IDENTITY: originator '${parentHandle}' not found in identity registry")
                    sendRegistrationFailure(store, originator, requestedLocalHandle, "Parent not found in registry", payload.uuid)
                    return
                }

                // Deduplicate among siblings (same parent namespace)
                val finalLocalHandle = deduplicateLocalHandle(requestedLocalHandle, parentHandle, registry)
                val fullHandle = constructHandle(parentHandle, finalLocalHandle)

                val newIdentity = Identity(
                    uuid = payload.uuid ?: platformDependencies.generateUUID(),
                    localHandle = finalLocalHandle,
                    handle = fullHandle,
                    name = payload.name,
                    parentHandle = parentHandle,
                    registeredAt = platformDependencies.currentTimeMillis()
                )

                // Delegate storage to the Store
                store.updateIdentityRegistry { it + (fullHandle to newIdentity) }

                // Targeted response to the originator: here's what was approved
                store.deferredDispatch(identity.handle, Action(
                    ActionRegistry.Names.CORE_RETURN_REGISTER_IDENTITY,
                    buildJsonObject {
                        put("success", true)
                        put("requestedLocalHandle", requestedLocalHandle)
                        put("approvedLocalHandle", newIdentity.localHandle)
                        put("handle", newIdentity.handle)
                        put("uuid", newIdentity.uuid)
                        put("name", newIdentity.name)
                        put("parentHandle", newIdentity.parentHandle ?: "")
                    },
                    targetRecipient = originator
                ))

                // Broadcast registry update
                store.deferredDispatch(identity.handle, Action(
                    ActionRegistry.Names.CORE_IDENTITY_REGISTRY_UPDATED
                ))
            }
            ActionRegistry.Names.CORE_UNREGISTER_IDENTITY -> {
                val originator = action.originator ?: return
                val payload = action.payload?.let {
                    Json.decodeFromJsonElement<UnregisterIdentityPayload>(it)
                }
                if (payload == null) {
                    platformDependencies.log(app.auf.util.LogLevel.ERROR, identity.handle,
                        "UNREGISTER_IDENTITY: missing or invalid payload")
                    return
                }

                val registry = store.state.value.identityRegistry

                if (payload.handle !in registry) {
                    platformDependencies.log(app.auf.util.LogLevel.WARN, identity.handle,
                        "UNREGISTER_IDENTITY: handle '${payload.handle}' not in registry")
                    return
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
                    return
                }

                // Cascade: remove the identity and all its descendants.
                val handlePrefix = "${payload.handle}."
                val handlesToRemove = registry.keys.filter { key ->
                    key == payload.handle || key.startsWith(handlePrefix)
                }.toSet()

                store.updateIdentityRegistry { it - handlesToRemove }

                // Broadcast registry update
                store.deferredDispatch(identity.handle, Action(
                    ActionRegistry.Names.CORE_IDENTITY_REGISTRY_UPDATED
                ))
            }
            ActionRegistry.Names.CORE_UPDATE_IDENTITY -> {
                val originator = action.originator ?: return
                val payload = action.payload?.let {
                    Json.decodeFromJsonElement<UpdateIdentityPayload>(it)
                }
                if (payload == null || payload.handle.isBlank() || payload.newName.isBlank()) {
                    platformDependencies.log(app.auf.util.LogLevel.ERROR, identity.handle,
                        "UPDATE_IDENTITY: missing or invalid payload")
                    sendUpdateFailure(store, originator, payload?.handle ?: "", "Missing or invalid payload")
                    return
                }

                val registry = store.state.value.identityRegistry
                val existingIdentity = registry[payload.handle]

                if (existingIdentity == null) {
                    platformDependencies.log(app.auf.util.LogLevel.WARN, identity.handle,
                        "UPDATE_IDENTITY: handle '${payload.handle}' not in registry")
                    sendUpdateFailure(store, originator, payload.handle, "Handle not found in registry")
                    return
                }

                // Namespace enforcement: originator can only update identities within its namespace
                val isOwnNamespace = payload.handle == originator ||
                        payload.handle.startsWith("$originator.")
                if (!isOwnNamespace) {
                    platformDependencies.log(app.auf.util.LogLevel.ERROR, identity.handle,
                        "UPDATE_IDENTITY: originator '$originator' cannot update " +
                                "'${payload.handle}' — outside its namespace")
                    sendUpdateFailure(store, originator, payload.handle, "Outside originator namespace")
                    return
                }

                // Compute new slug from new name
                val newLocalHandle = slugifyName(payload.newName)
                val parentHandle = existingIdentity.parentHandle
                val finalLocalHandle = deduplicateLocalHandle(newLocalHandle, parentHandle, registry - payload.handle)
                val newFullHandle = constructHandle(parentHandle, finalLocalHandle)

                val updatedIdentity = existingIdentity.copy(
                    localHandle = finalLocalHandle,
                    handle = newFullHandle,
                    name = payload.newName
                )

                // Atomic swap: remove old handle, add new handle (may be the same if name→slug didn't change)
                val updatedRegistry = (registry - payload.handle) + (newFullHandle to updatedIdentity)

                // Cascade: update children whose parentHandle == old handle
                val cascadedRegistry = updatedRegistry.toMutableMap()
                updatedRegistry.forEach { (childHandle, childIdentity) ->
                    if (childIdentity.parentHandle == payload.handle) {
                        val newChildHandle = "$newFullHandle.${childIdentity.localHandle}"
                        val updatedChild = childIdentity.copy(
                            parentHandle = newFullHandle,
                            handle = newChildHandle
                        )
                        cascadedRegistry.remove(childHandle)
                        cascadedRegistry[newChildHandle] = updatedChild
                    }
                }

                store.updateIdentityRegistry { cascadedRegistry }

                // Targeted response to the originator
                store.deferredDispatch(identity.handle, Action(
                    ActionRegistry.Names.CORE_RETURN_UPDATE_IDENTITY,
                    buildJsonObject {
                        put("success", true)
                        put("oldHandle", payload.handle)
                        put("newHandle", newFullHandle)
                        put("oldLocalHandle", existingIdentity.localHandle)
                        put("newLocalHandle", finalLocalHandle)
                        put("name", payload.newName)
                        put("uuid", updatedIdentity.uuid)
                    },
                    targetRecipient = originator
                ))

                // Broadcast registry update
                store.deferredDispatch(identity.handle, Action(
                    ActionRegistry.Names.CORE_IDENTITY_REGISTRY_UPDATED
                ))
            }

            ActionRegistry.Names.COMMANDBOT_ACTION_CREATED -> {
                val acPayload = action.payload ?: return
                val originatorId = acPayload["originatorId"]?.jsonPrimitive?.contentOrNull ?: return
                val sessionId = acPayload["sessionId"]?.jsonPrimitive?.contentOrNull ?: return
                val actionName = acPayload["actionName"]?.jsonPrimitive?.contentOrNull ?: return
                val actionPayload = acPayload["actionPayload"]?.jsonObject ?: return

                // Only claim actions from core user identities.
                val originatorIdentity = store.state.value.identityRegistry[originatorId]
                if (originatorIdentity?.parentHandle != "core") return

                // Dispatch the domain action attributed to the user.
                // Causality tracking is preserved — the originator on the bus
                // is the actual user who typed the command.
                val domainAction = Action(name = actionName, payload = actionPayload)
                store.deferredDispatch(originatorId, domainAction)

                platformDependencies.log(
                    app.auf.util.LogLevel.INFO, identity.handle,
                    "Dispatched '$actionName' on behalf of user '$originatorId' (session=$sessionId)."
                )
            }
        }
    }

    private fun persistAndBroadcastIdentities(state: CoreState, store: Store) {
        val persistencePayload = IdentitiesLoadedPayload(state.userIdentities, state.activeUserId)
        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
            put("subpath", identitiesFileName)
            put("content", Json.encodeToString(persistencePayload))
            put("encrypt", true)
        }))

        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.CORE_IDENTITIES_UPDATED, buildJsonObject {
            put("identities", Json.encodeToJsonElement(state.userIdentities))
            state.activeUserId?.let { put("activeId", it) }
        }))
    }

    override fun reducer(state: FeatureState?, action: Action): FeatureState? {
        val coreState = state as? CoreState ?: CoreState()
        val originator = action.originator ?: ""

        when (action.name) {
            ActionRegistry.Names.SYSTEM_INITIALIZING -> return coreState.copy(lifecycle = AppLifecycle.INITIALIZING)
            ActionRegistry.Names.SYSTEM_STARTING -> return coreState.copy(lifecycle = AppLifecycle.RUNNING)
            ActionRegistry.Names.SYSTEM_CLOSING -> return coreState.copy(lifecycle = AppLifecycle.CLOSING)
            ActionRegistry.Names.CORE_SET_ACTIVE_VIEW -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<SetActiveViewPayload>(it) }
                return payload?.let { coreState.copy(activeViewKey = it.key) } ?: coreState
            }
            ActionRegistry.Names.CORE_SHOW_DEFAULT_VIEW -> return coreState.copy(activeViewKey = coreState.defaultViewKey)
            ActionRegistry.Names.CORE_UPDATE_WINDOW_SIZE -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<UpdateWindowSizePayload>(it) }
                return payload?.let { coreState.copy(windowWidth = it.width, windowHeight = it.height) } ?: coreState
            }
            ActionRegistry.Names.CORE_SHOW_TOAST -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<ShowToastPayload>(it) }
                return payload?.let { coreState.copy(toastMessage = it.message) } ?: coreState
            }
            ActionRegistry.Names.CORE_CLEAR_TOAST -> return coreState.copy(toastMessage = null)
            ActionRegistry.Names.CORE_SHOW_CONFIRMATION_DIALOG -> {
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
            ActionRegistry.Names.CORE_DISMISS_CONFIRMATION_DIALOG -> return coreState.copy(confirmationRequest = null)
            ActionRegistry.Names.SETTINGS_LOADED -> {
                val loadedValues = action.payload
                val width = loadedValues?.get(settingKeyWidth)?.jsonPrimitive?.content?.toIntOrNull()
                val height = loadedValues?.get(settingKeyHeight)?.jsonPrimitive?.content?.toIntOrNull()
                return coreState.copy(windowWidth = width ?: coreState.windowWidth, windowHeight = height ?: coreState.windowHeight)
            }
            ActionRegistry.Names.SETTINGS_VALUE_CHANGED -> {
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
            ActionRegistry.Names.CORE_IDENTITIES_LOADED -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<IdentitiesLoadedPayload>(it) } ?: return coreState
                val identities: List<Identity>
                val activeId: String?
                if (payload.identities.isEmpty()) {
                    val defaultUser = Identity(platformDependencies.generateUUID(), name = "DefaultUser", handle = "default-user", localHandle = "default-user")
                    identities = listOf(defaultUser)
                    activeId = defaultUser.handle
                } else {
                    identities = payload.identities
                    activeId = if (payload.activeId in identities.map { it.handle }) payload.activeId else identities.first().handle
                }

                // Identity registry migration is handled in handleSideEffects via store.updateIdentityRegistry().
                @Suppress("DEPRECATION")
                return coreState.copy(
                    userIdentities = identities,
                    activeUserId = activeId
                )
            }
            ActionRegistry.Names.CORE_ADD_USER_IDENTITY -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<AddUserIdentityPayload>(it) } ?: return coreState
                val localHandle = slugifyName(payload.name)
                // Dedup against existing user identities (registry sync is handled in handleSideEffects).
                @Suppress("DEPRECATION")
                val existingAsMap = coreState.userIdentities.associateBy { it.handle }
                val finalLocalHandle = deduplicateLocalHandle(localHandle, "core", existingAsMap)
                val fullHandle = "core.$finalLocalHandle"
                val uuid = platformDependencies.generateUUID()
                val newUser = Identity(
                    uuid = uuid,
                    localHandle = finalLocalHandle,
                    handle = fullHandle,
                    name = payload.name,
                    parentHandle = "core",
                    registeredAt = platformDependencies.currentTimeMillis()
                )
                @Suppress("DEPRECATION")
                return coreState.copy(
                    userIdentities = coreState.userIdentities + newUser
                )
            }
            ActionRegistry.Names.CORE_REMOVE_USER_IDENTITY -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<IdentityIdPayload>(it) } ?: return coreState
                @Suppress("DEPRECATION")
                val updatedIdentities = coreState.userIdentities.filterNot { it.handle == payload.id }
                val updatedActiveId = if (coreState.activeUserId == payload.id) updatedIdentities.firstOrNull()?.handle else coreState.activeUserId
                // Identity registry cleanup is handled in handleSideEffects via store.updateIdentityRegistry().
                @Suppress("DEPRECATION")
                return coreState.copy(
                    userIdentities = updatedIdentities,
                    activeUserId = updatedActiveId
                )
            }
            ActionRegistry.Names.CORE_SET_ACTIVE_USER_IDENTITY -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<IdentityIdPayload>(it) } ?: return coreState
                @Suppress("DEPRECATION")
                if (coreState.userIdentities.any { it.handle == payload.id }) {
                    return coreState.copy(activeUserId = payload.id)
                }
                return coreState
            }

            // ================================================================
            // Identity Registry Management (Phase 2)
            // Handled entirely in handleSideEffects via store.updateIdentityRegistry().
            // ================================================================

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
            IconButton(onClick = { store.dispatch("core.ui", Action(ActionRegistry.Names.CORE_SET_ACTIVE_VIEW, buildJsonObject { put("key", viewKeyIdentities) })) }) {
                Icon(Icons.Default.Person, "Identity Manager", tint = if (activeViewKey == viewKeyIdentities) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        @Composable
        override fun MenuContent(store: Store, onDismiss: () -> Unit) {
            DropdownMenuItem(
                text = { Text("About") },
                onClick = {
                    store.dispatch("core.ui", Action(ActionRegistry.Names.CORE_SET_ACTIVE_VIEW, buildJsonObject { put("key", viewKeyAbout) }))
                    onDismiss()
                },
                leadingIcon = { Icon(Icons.Default.Info, "About Application") }
            )
        }
    }
}