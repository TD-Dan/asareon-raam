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

    // --- Pending Command Tracking payload classes ---
    @Serializable private data class RegisterPendingCommandPayload(
        val correlationId: String,
        val sessionId: String,
        val actionName: String,
        val originatorId: String
    )
    @Serializable private data class ClearPendingCommandPayload(val correlationId: String)

    // --- Identity registration payload classes ---
    // Note: no parentHandle field — the originator IS the parent (enforced by design).
    @Serializable private data class RegisterIdentityPayload(
        val localHandle: String? = null,
        val name: String,
        val uuid: String? = null,
        val displayColor: String? = null,
        val displayIcon: String? = null,
        val displayEmoji: String? = null
    )
    @Serializable private data class UnregisterIdentityPayload(val handle: String)
    @Serializable private data class UpdateIdentityPayload(
        val handle: String,
        val newName: String,
        val displayColor: String? = null,
        val displayIcon: String? = null,
        val displayEmoji: String? = null
    )

    // --- Permission management payload classes (Phase 1) ---
    @Serializable private data class SetPermissionPayload(
        val identityHandle: String,
        val permissionKey: String,
        val level: String
    )
    @Serializable private data class SetPermissionsBatchPayload(
        val grants: List<BatchGrantEntry>
    )
    @Serializable private data class BatchGrantEntry(
        val identityHandle: String,
        val permissionKey: String,
        val level: String
    )

    /**
     * Lenient Json instance for decoding action payloads. Uses ignoreUnknownKeys
     * because upstream features (e.g., CoreFeature's own handleSideEffects) may
     * enrich payloads with extra fields like correlationId before dispatch.
     */
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonPretty = Json { prettyPrint = true }

    private val settingKeyWidth = "core.window.width"
    private val settingKeyHeight = "core.window.height"
    private val settingKeyUseIdentityColor = "core.use_identity_color"
    private val identitiesFileName = "identities.json"

    companion object {
        /** TTL for pending command entries: 5 minutes. */
        const val PENDING_COMMAND_TTL_MS = 5 * 60 * 1000L

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

    /**
     * Applies a single permission grant to an identity in the registry.
     * Validates the permission key and level before applying.
     */
    private fun applyPermissionGrant(
        store: Store,
        identityHandle: String,
        permissionKey: String,
        levelStr: String
    ): Boolean {
        val level = try {
            PermissionLevel.valueOf(levelStr)
        } catch (e: IllegalArgumentException) {
            platformDependencies.log(
                app.auf.util.LogLevel.ERROR, identity.handle,
                "SET_PERMISSION: invalid level '$levelStr' — must be one of ${PermissionLevel.entries.map { it.name }}"
            )
            return false
        }

        val registry = store.state.value.identityRegistry
        val targetIdentity = registry[identityHandle]
        if (targetIdentity == null) {
            platformDependencies.log(
                app.auf.util.LogLevel.ERROR, identity.handle,
                "SET_PERMISSION: identity '$identityHandle' not found in registry"
            )
            return false
        }

        val updatedPermissions = targetIdentity.permissions + (permissionKey to PermissionGrant(level = level))
        val updatedIdentity = targetIdentity.copy(permissions = updatedPermissions)

        store.updateIdentityRegistry { it + (identityHandle to updatedIdentity) }

        platformDependencies.log(
            app.auf.util.LogLevel.INFO, identity.handle,
            "SET_PERMISSION: '$identityHandle' → '$permissionKey' = $level"
        )
        return true
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
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SETTINGS_ADD, buildJsonObject {
                    put("key", settingKeyUseIdentityColor); put("type", "BOOLEAN"); put("label", "Use Identity Color as App Theme")
                    put("description", "When enabled, the active user identity's display color replaces the app's primary color. Secondary color is auto-derived.")
                    put("section", "Appearance"); put("defaultValue", "false")
                }))
            }
            ActionRegistry.Names.SYSTEM_STARTING -> {
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_READ, buildJsonObject { put("path", identitiesFileName)}))
            }
            ActionRegistry.Names.CORE_DISMISS_CONFIRMATION_DIALOG -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<DismissConfirmationPayload>(it) } ?: return
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
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_OPEN_WORKSPACE_FOLDER, buildJsonObject {
                    put("path", "logs")
                }))
            }
            ActionRegistry.Names.CORE_COPY_TO_CLIPBOARD -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<CopyToClipboardPayload>(it) }
                payload?.let {
                    platformDependencies.copyToClipboard(it.text)
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.CORE_SHOW_TOAST, buildJsonObject { put("message", "Copied to clipboard.") }))
                }
            }
            // Phase 3: Targeted response from FilesystemFeature — identity file loaded.
            // Migrated from onPrivateData.
            ActionRegistry.Names.FILESYSTEM_RETURN_READ -> {
                val data = action.payload ?: return
                val path = data["path"]?.jsonPrimitive?.contentOrNull
                val content = data["content"]?.jsonPrimitive?.contentOrNull
                val correlationId = data["correlationId"]?.jsonPrimitive?.contentOrNull

                // Route command-originated read results to the session via DELIVER_TO_SESSION.
                if (correlationId != null && latestCoreState != null) {
                    val pendingCommand = latestCoreState.pendingCommands[correlationId]
                    if (pendingCommand != null) {
                        if (content != null) {
                            val ext = path?.substringAfterLast('.', "") ?: ""
                            val label = path ?: "file"
                            val formatted = "```$ext \"$label\"\n$content\n```"
                            store.deferredDispatch(identity.handle, Action(
                                ActionRegistry.Names.COMMANDBOT_DELIVER_TO_SESSION,
                                buildJsonObject {
                                    put("correlationId", correlationId)
                                    put("sessionId", pendingCommand.sessionId)
                                    put("message", formatted)
                                }
                            ))
                        }
                        store.deferredDispatch(identity.handle, Action(
                            ActionRegistry.Names.CORE_CLEAR_PENDING_COMMAND,
                            buildJsonObject { put("correlationId", correlationId) }
                        ))
                    }
                }

                // Existing: handle identities.json load (non-command read, no correlationId).
                if (path == identitiesFileName) {
                    if (content != null) {
                        try {
                            val loaded = json.decodeFromString<IdentitiesLoadedPayload>(content)
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

            // Route command-originated directory listings to the session via DELIVER_TO_SESSION.
            ActionRegistry.Names.FILESYSTEM_RETURN_LIST -> {
                val data = action.payload ?: return
                val correlationId = data["correlationId"]?.jsonPrimitive?.contentOrNull ?: return
                if (latestCoreState == null) return
                val pendingCommand = latestCoreState.pendingCommands[correlationId] ?: return

                val listing = data["listing"]?.jsonArray
                val path = data["path"]?.jsonPrimitive?.contentOrNull ?: ""

                val header = if (path.isNotBlank()) "Listing: $path" else "Listing: (root)"
                val body = if (listing != null && listing.isNotEmpty()) {
                    listing.joinToString("\n") { entry ->
                        val entryObj = entry.jsonObject
                        val path = entryObj["path"]?.jsonPrimitive?.contentOrNull ?: "?"
                        val isDir = entryObj["isDirectory"]?.jsonPrimitive?.boolean ?: false
                        if (isDir) "  $path/" else "  $path"
                    }
                } else {
                    "  (empty)"
                }
                val formatted = "```text \"$header\"\n$body\n```"

                store.deferredDispatch(identity.handle, Action(
                    ActionRegistry.Names.COMMANDBOT_DELIVER_TO_SESSION,
                    buildJsonObject {
                        put("correlationId", correlationId)
                        put("sessionId", pendingCommand.sessionId)
                        put("message", formatted)
                    }
                ))
                store.deferredDispatch(identity.handle, Action(
                    ActionRegistry.Names.CORE_CLEAR_PENDING_COMMAND,
                    buildJsonObject { put("correlationId", correlationId) }
                ))
            }
            // --- User Identity Management (registry-direct) ---
            // All identity mutations go through store.updateIdentityRegistry().
            // No intermediate CoreState.userIdentities — the registry IS the truth.

            ActionRegistry.Names.CORE_IDENTITIES_LOADED -> {
                if (latestCoreState != null) {
                    val loadedPayload = action.payload?.let { json.decodeFromJsonElement<IdentitiesLoadedPayload>(it) }
                    val loadedIdentities = loadedPayload?.identities ?: emptyList()

                    store.updateIdentityRegistry { current ->
                        var updated = current

                        // Restore feature permission overrides from disk.
                        // Compile-time defaults first, then persisted overrides on top.
                        for (loaded in loadedIdentities.filter { it.uuid == null }) {
                            val existing = current[loaded.handle] ?: continue
                            val defaults = DefaultPermissions.grantsFor(loaded.handle)
                            val merged = defaults + loaded.permissions
                            if (merged != existing.permissions) {
                                updated = updated + (loaded.handle to existing.copy(permissions = merged))
                            }
                        }

                        // Restore ALL child identities from disk.
                        for (loaded in loadedIdentities.filter { it.uuid != null }) {
                            val parentExists = loaded.parentHandle?.let { it in updated } ?: true
                            if (parentExists) {
                                updated = updated + (loaded.handle to loaded)
                            }
                        }

                        updated
                    }

                    // Ensure there's at least one user identity.
                    val coreChildren = store.state.value.identityRegistry.values
                        .filter { it.parentHandle == "core" && it.uuid != null }
                    if (coreChildren.isEmpty()) {
                        val defaultUser = Identity(
                            uuid = platformDependencies.generateUUID(),
                            localHandle = "default-user", handle = "core.default-user",
                            name = "DefaultUser", parentHandle = "core",
                            registeredAt = platformDependencies.currentTimeMillis()
                        )
                        store.updateIdentityRegistry { it + (defaultUser.handle to defaultUser) }
                    }

                    persistIdentitiesFromRegistry(store)
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.CORE_IDENTITY_REGISTRY_UPDATED
                    ))
                }
            }

            ActionRegistry.Names.CORE_ADD_USER_IDENTITY -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<AddUserIdentityPayload>(it) } ?: return
                val registry = store.state.value.identityRegistry
                val localHandle = slugifyName(payload.name)
                val finalLocalHandle = deduplicateLocalHandle(localHandle, "core", registry)
                val fullHandle = "core.$finalLocalHandle"
                val newUser = Identity(
                    uuid = platformDependencies.generateUUID(),
                    localHandle = finalLocalHandle,
                    handle = fullHandle,
                    name = payload.name,
                    parentHandle = "core",
                    registeredAt = platformDependencies.currentTimeMillis()
                )
                store.updateIdentityRegistry { it + (fullHandle to newUser) }

                // Auto-activate if this is the first user, or no active user is set.
                val coreState = store.state.value.featureStates["core"] as? CoreState
                if (coreState?.activeUserId == null) {
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.CORE_SET_ACTIVE_USER_IDENTITY,
                        buildJsonObject { put("id", fullHandle) }
                    ))
                }

                persistIdentitiesFromRegistry(store)
                store.deferredDispatch(identity.handle, Action(
                    ActionRegistry.Names.CORE_IDENTITY_REGISTRY_UPDATED
                ))
            }

            ActionRegistry.Names.CORE_REMOVE_USER_IDENTITY -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<IdentityIdPayload>(it) } ?: return
                val handleToRemove = payload.id
                val registry = store.state.value.identityRegistry
                if (handleToRemove !in registry) return

                store.updateIdentityRegistry { it - handleToRemove }

                // If removed identity was the active one, pick the next core.* child.
                val coreState = store.state.value.featureStates["core"] as? CoreState
                if (coreState?.activeUserId == handleToRemove) {
                    val nextUser = store.state.value.identityRegistry.values
                        .filter { it.parentHandle == "core" && it.uuid != null }
                        .minByOrNull { it.handle }
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.CORE_SET_ACTIVE_USER_IDENTITY,
                        buildJsonObject { put("id", nextUser?.handle ?: "") }
                    ))
                }

                persistIdentitiesFromRegistry(store)
                store.deferredDispatch(identity.handle, Action(
                    ActionRegistry.Names.CORE_IDENTITY_REGISTRY_UPDATED
                ))
            }

            ActionRegistry.Names.CORE_SET_ACTIVE_USER_IDENTITY -> {
                persistIdentitiesFromRegistry(store)
            }

            // --- Identity Registry Management ---
            // Business logic lives here; storage is delegated to store.updateIdentityRegistry().
            ActionRegistry.Names.CORE_REGISTER_IDENTITY -> {
                val originator = action.originator ?: return
                val payload = action.payload?.let {
                    json.decodeFromJsonElement<RegisterIdentityPayload>(it)
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

                // ── Idempotent reclaim: if an identity with this UUID already exists,
                // return it instead of creating a duplicate. This handles the restart
                // scenario where identities are restored from disk but features
                // re-register them from their own config files.
                if (payload.uuid != null) {
                    val existingByUUID = registry.findByUUID(payload.uuid)
                    if (existingByUUID != null) {
                        // Update name if it changed, but keep the existing handle and permissions.
                        val reclaimed = if (existingByUUID.name != payload.name
                            || payload.displayColor != existingByUUID.displayColor
                            || payload.displayIcon != existingByUUID.displayIcon
                            || payload.displayEmoji != existingByUUID.displayEmoji
                        ) {
                            existingByUUID.copy(
                                name = payload.name,
                                displayColor = payload.displayColor ?: existingByUUID.displayColor,
                                displayIcon = payload.displayIcon ?: existingByUUID.displayIcon,
                                displayEmoji = payload.displayEmoji ?: existingByUUID.displayEmoji
                            )
                        } else {
                            existingByUUID
                        }
                        if (reclaimed !== existingByUUID) {
                            store.updateIdentityRegistry { it + (reclaimed.handle to reclaimed) }
                        }

                        platformDependencies.log(
                            app.auf.util.LogLevel.INFO, identity.handle,
                            "REGISTER_IDENTITY: Reclaimed existing identity '${reclaimed.handle}' (UUID=${payload.uuid})"
                        )

                        // Return the existing identity to the caller
                        store.deferredDispatch(identity.handle, Action(
                            ActionRegistry.Names.CORE_RETURN_REGISTER_IDENTITY,
                            buildJsonObject {
                                put("success", true)
                                put("requestedLocalHandle", requestedLocalHandle)
                                put("approvedLocalHandle", reclaimed.localHandle)
                                put("handle", reclaimed.handle)
                                put("uuid", reclaimed.uuid)
                                put("name", reclaimed.name)
                                put("parentHandle", reclaimed.parentHandle ?: "")
                            },
                            targetRecipient = originator
                        ))
                        return
                    }
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
                    registeredAt = platformDependencies.currentTimeMillis(),
                    displayColor = payload.displayColor,
                    displayIcon = payload.displayIcon,
                    displayEmoji = payload.displayEmoji
                )

                // Delegate storage to the Store
                store.updateIdentityRegistry { it + (fullHandle to newIdentity) }

                // Children inherit permissions from their parent feature identity
                // via resolveEffectivePermissions — no per-child defaults needed.

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
                    json.decodeFromJsonElement<UnregisterIdentityPayload>(it)
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
                    json.decodeFromJsonElement<UpdateIdentityPayload>(it)
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
                    name = payload.newName,
                    // Carry display fields through if explicitly provided in payload,
                    // otherwise preserve the existing values.
                    displayColor = if (action.payload?.containsKey("displayColor") == true)
                        payload.displayColor
                    else existingIdentity.displayColor,
                    displayIcon = if (action.payload?.containsKey("displayIcon") == true)
                        payload.displayIcon
                    else existingIdentity.displayIcon,
                    displayEmoji = if (action.payload?.containsKey("displayEmoji") == true)
                        payload.displayEmoji
                    else existingIdentity.displayEmoji
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

                // Persist updated identities to disk so the rename survives restart.
                persistIdentitiesFromRegistry(store)
            }

            // --- Permission Management (Phase 1 + Phase 2) ---
            ActionRegistry.Names.CORE_SET_PERMISSION -> {
                val payload = action.payload?.let {
                    json.decodeFromJsonElement<SetPermissionPayload>(it)
                } ?: return

                val changed = applyPermissionGrant(store, payload.identityHandle, payload.permissionKey, payload.level)
                if (changed) {
                    // Persist from registry (authoritative source including permissions)
                    persistIdentitiesFromRegistry(store)
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.CORE_PERMISSIONS_UPDATED
                    ))
                }
            }
            ActionRegistry.Names.CORE_SET_PERMISSIONS_BATCH -> {
                val payload = action.payload?.let {
                    json.decodeFromJsonElement<SetPermissionsBatchPayload>(it)
                } ?: return

                var anyChanged = false
                for (grant in payload.grants) {
                    val changed = applyPermissionGrant(store, grant.identityHandle, grant.permissionKey, grant.level)
                    if (changed) anyChanged = true
                }

                if (anyChanged) {
                    // Persist from registry (authoritative source including permissions)
                    persistIdentitiesFromRegistry(store)
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.CORE_PERMISSIONS_UPDATED
                    ))
                }
            }

            ActionRegistry.Names.COMMANDBOT_ACTION_CREATED -> {
                val acPayload = action.payload ?: return
                val originatorId = acPayload["originatorId"]?.jsonPrimitive?.contentOrNull ?: return
                val sessionId = acPayload["sessionId"]?.jsonPrimitive?.contentOrNull ?: return
                val actionName = acPayload["actionName"]?.jsonPrimitive?.contentOrNull ?: return
                val actionPayload = acPayload["actionPayload"]?.jsonObject ?: return
                val correlationId = acPayload["correlationId"]?.jsonPrimitive?.contentOrNull

                // Only claim actions from core user identities.
                val originatorIdentity = store.state.value.identityRegistry[originatorId]
                if (originatorIdentity?.parentHandle != "core") return

                // Inject correlationId so the handling feature can thread it to ACTION_RESULT.
                // Guard: don't overwrite a correlationId the user explicitly included.
                val enrichedPayload = if (correlationId != null && actionPayload["correlationId"] == null) {
                    JsonObject(actionPayload + ("correlationId" to JsonPrimitive(correlationId)))
                } else {
                    actionPayload
                }

                // Dispatch the domain action attributed to the user.
                // Causality tracking is preserved — the originator on the bus
                // is the actual user who typed the command.
                val domainAction = Action(name = actionName, payload = enrichedPayload)
                store.deferredDispatch(originatorId, domainAction)

                // Track the pending command so we can route RETURN_* data to the session.
                if (correlationId != null) {
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.CORE_REGISTER_PENDING_COMMAND,
                        buildJsonObject {
                            put("correlationId", correlationId)
                            put("sessionId", sessionId)
                            put("actionName", actionName)
                            put("originatorId", originatorId)
                        }
                    ))
                    // Schedule TTL cleanup.
                    store.scheduleDelayed(PENDING_COMMAND_TTL_MS, identity.handle, Action(
                        ActionRegistry.Names.CORE_CLEAR_PENDING_COMMAND,
                        buildJsonObject { put("correlationId", correlationId) }
                    ))
                }

                platformDependencies.log(
                    app.auf.util.LogLevel.INFO, identity.handle,
                    "Dispatched '$actionName' on behalf of user '$originatorId' (session=$sessionId, correlationId=$correlationId)."
                )
            }
        }
    }

    /**
     * Persists ALL identities from the authoritative identity registry.
     * Core is the single owner of all identity data and permissions.
     *
     * Includes feature identities (for their permission overrides), core.* user
     * identities, and all other child identities (agent.*, session.*, etc.).
     * On load, feature permissions are merged with compile-time defaults, and
     * all other identities are restored directly into the registry.
     */
    private fun persistIdentitiesFromRegistry(store: Store) {
        val registry = store.state.value.identityRegistry
        val coreState = store.state.value.featureStates["core"] as? CoreState ?: return

        // Save ALL identities — Core owns the identity registry.
        val allIdentities = registry.values.sortedBy { it.handle }.toList()

        val persistencePayload = IdentitiesLoadedPayload(allIdentities, coreState.activeUserId)
        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_WRITE, buildJsonObject {
            put("path", identitiesFileName)
            put("content", jsonPretty.encodeToString(persistencePayload))
            // Encryption temporarily disabled for debugging
            put("encrypt", false)
        }))

        // Broadcast for features that listen to IDENTITIES_UPDATED
        val coreUserIdentities = registry.values
            .filter { it.parentHandle == "core" && it.uuid != null }
            .sortedBy { it.handle }
            .toList()
        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.CORE_IDENTITIES_UPDATED, buildJsonObject {
            put("identities", Json.encodeToJsonElement(coreUserIdentities))
            coreState.activeUserId?.let { put("activeId", it) }
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
                val payload = action.payload?.let { json.decodeFromJsonElement<SetActiveViewPayload>(it) }
                return payload?.let { coreState.copy(activeViewKey = it.key) } ?: coreState
            }
            ActionRegistry.Names.CORE_SHOW_DEFAULT_VIEW -> return coreState.copy(activeViewKey = coreState.defaultViewKey)
            ActionRegistry.Names.CORE_UPDATE_WINDOW_SIZE -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<UpdateWindowSizePayload>(it) }
                return payload?.let { coreState.copy(windowWidth = it.width, windowHeight = it.height) } ?: coreState
            }
            ActionRegistry.Names.CORE_SHOW_TOAST -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<ShowToastPayload>(it) }
                return payload?.let { coreState.copy(toastMessage = it.message) } ?: coreState
            }
            ActionRegistry.Names.CORE_CLEAR_TOAST -> return coreState.copy(toastMessage = null)
            ActionRegistry.Names.CORE_SHOW_CONFIRMATION_DIALOG -> {
                val request = action.payload?.let {
                    try {
                        json.decodeFromJsonElement<ConfirmationDialogRequest>(it)
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
                val useIdentityColor = loadedValues?.get(settingKeyUseIdentityColor)?.jsonPrimitive?.content == "true"
                return coreState.copy(
                    windowWidth = width ?: coreState.windowWidth,
                    windowHeight = height ?: coreState.windowHeight,
                    useIdentityColorAsPrimary = useIdentityColor
                )
            }
            ActionRegistry.Names.SETTINGS_VALUE_CHANGED -> {
                val payload = action.payload ?: return coreState
                val key = payload["key"]?.jsonPrimitive?.content
                val value = payload["value"]?.jsonPrimitive?.content
                var newCoreState = coreState
                when (key) {
                    settingKeyWidth -> value?.toIntOrNull()?.let { if (it != coreState.windowWidth) newCoreState = coreState.copy(windowWidth = it) }
                    settingKeyHeight -> value?.toIntOrNull()?.let { if (it != coreState.windowHeight) newCoreState = coreState.copy(windowHeight = it) }
                    settingKeyUseIdentityColor -> newCoreState = coreState.copy(useIdentityColorAsPrimary = value == "true")
                }
                return newCoreState
            }
            ActionRegistry.Names.CORE_IDENTITIES_LOADED -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<IdentitiesLoadedPayload>(it) } ?: return coreState
                val coreChildren = payload.identities.filter { it.parentHandle == "core" && it.uuid != null }
                val activeId = when {
                    coreChildren.isEmpty() -> "core.default-user"
                    payload.activeId in coreChildren.map { it.handle } -> payload.activeId
                    else -> coreChildren.first().handle
                }
                return coreState.copy(activeUserId = activeId)
            }
            ActionRegistry.Names.CORE_ADD_USER_IDENTITY -> {
                // Identity creation handled entirely in handleSideEffects.
                return coreState
            }
            ActionRegistry.Names.CORE_REMOVE_USER_IDENTITY -> {
                // Registry removal and activeUserId update handled in handleSideEffects.
                return coreState
            }
            ActionRegistry.Names.CORE_SET_ACTIVE_USER_IDENTITY -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<IdentityIdPayload>(it) } ?: return coreState
                return coreState.copy(activeUserId = payload.id)
            }

            // ================================================================
            // Identity Registry Management (Phase 2)
            // Handled entirely in handleSideEffects via store.updateIdentityRegistry().
            // ================================================================

            // ================================================================
            // Pending Command Tracking (ACTION_RESULT / DELIVER_TO_SESSION)
            // ================================================================
            ActionRegistry.Names.CORE_REGISTER_PENDING_COMMAND -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<RegisterPendingCommandPayload>(it) } ?: return coreState
                val pending = PendingCommand(
                    correlationId = payload.correlationId,
                    sessionId = payload.sessionId,
                    actionName = payload.actionName,
                    originatorId = payload.originatorId,
                    createdAt = platformDependencies.currentTimeMillis()
                )
                return coreState.copy(
                    pendingCommands = coreState.pendingCommands + (payload.correlationId to pending)
                )
            }
            ActionRegistry.Names.CORE_CLEAR_PENDING_COMMAND -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<ClearPendingCommandPayload>(it) } ?: return coreState
                return coreState.copy(
                    pendingCommands = coreState.pendingCommands - payload.correlationId
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
            // Pre-Phase 1 fix: use feature handle "core" instead of unregistered "core"
            IconButton(onClick = { store.dispatch("core", Action(ActionRegistry.Names.CORE_SET_ACTIVE_VIEW, buildJsonObject { put("key", viewKeyIdentities) })) }) {
                Icon(Icons.Default.Person, "Identity Manager", tint = if (activeViewKey == viewKeyIdentities) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        @Composable
        override fun MenuContent(store: Store, onDismiss: () -> Unit) {
            DropdownMenuItem(
                text = { Text("About") },
                onClick = {
                    // Pre-Phase 1 fix: use feature handle "core" instead of unregistered "core"
                    store.dispatch("core", Action(ActionRegistry.Names.CORE_SET_ACTIVE_VIEW, buildJsonObject { put("key", viewKeyAbout) }))
                    onDismiss()
                },
                leadingIcon = { Icon(Icons.Default.Info, "About Application") }
            )
        }
    }
}