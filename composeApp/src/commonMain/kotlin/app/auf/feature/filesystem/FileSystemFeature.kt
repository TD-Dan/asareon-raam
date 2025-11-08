package app.auf.feature.filesystem

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import app.auf.core.*
import app.auf.core.generated.ActionNames
import app.auf.util.BasePath
import app.auf.util.FileEntry
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

class FileSystemFeature(
    private val platformDependencies: PlatformDependencies
) : Feature {
    override val name: String = "filesystem"
    override val composableProvider: Feature.ComposableProvider = FileSystemComposableProvider()

    private val cryptoManager = CryptoManager()

    @Serializable private data class PathPayload(val path: String)
    @Serializable private data class ToggleItemPayload(val path: String, val recursive: Boolean = false)
    @Serializable private data class DirectoryLoadedPayload(val parentPath: String, val children: List<FileEntry>)
    @Serializable private data class SystemListPayload(val subpath: String = "")
    @Serializable private data class SystemListRecursivePayload(val subpath: String)
    @Serializable private data class ReadFilesContentPayload(val subpaths: List<String>)
    @Serializable private data class SystemReadPayload(val subpath: String)
    @Serializable private data class SystemWritePayload(val subpath: String, val content: String, val encrypt: Boolean = false)
    @Serializable private data class SystemDeletePayload(val subpath: String)
    @Serializable private data class SystemDeleteDirectoryPayload(val subpath: String)
    @Serializable private data class OpenAppSubfolderPayload(val folder: String)
    @Serializable data class RequestScopedReadUiPayload(val recursive: Boolean = true, val fileExtensions: List<String>? = null)
    @Serializable private data class StageScopedReadPayload(val requestId: String, val originator: String, val requestPayload: JsonObject)
    @Serializable private data class ConfirmationResponsePayload(val requestId: String, val confirmed: Boolean)


    private val settingKeyWhitelist = "filesystem.whitelistedPaths"
    private val settingKeyFavorites = "filesystem.favoritePaths"

    private fun getSandboxPathFor(originator: String): String {
        val appZoneRoot = platformDependencies.getBasePathFor(BasePath.APP_ZONE)
        val safeOriginator = originator.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return "$appZoneRoot${platformDependencies.pathSeparator}$safeOriginator"
    }

    override fun onPrivateData(envelope: PrivateDataEnvelope, store: Store) {
        val state = store.state.value.featureStates[name] as? FileSystemState ?: return
        when (envelope.type) {
            ActionNames.Envelopes.CORE_RESPONSE_CONFIRMATION -> {
                val payload = Json.decodeFromJsonElement<ConfirmationResponsePayload>(envelope.payload)
                val pendingRequest = state.pendingScopedRead
                if (payload.confirmed && pendingRequest?.requestId == payload.requestId) {
                    // --- THE FIX ---
                    // Dispatch the next action using the PRESERVED originator from the pending request,
                    // not 'this.name'. This correctly maintains the causality chain.
                    store.deferredDispatch(
                        originator = pendingRequest.originator,
                        action = Action(
                            name = ActionNames.FILESYSTEM_INTERNAL_EXECUTE_SCOPED_READ,
                            payload = Json.encodeToJsonElement(pendingRequest.payload) as JsonObject
                        )
                    )
                }
            }
        }
    }

    override fun onAction(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {
        val originator = action.originator ?: return
        val fileSystemState = newState as? FileSystemState ?: return
        when (action.name) {
            ActionNames.SYSTEM_PUBLISH_INITIALIZING -> {
                store.deferredDispatch(this.name, Action(ActionNames.SETTINGS_ADD, buildJsonObject {
                    put("key", settingKeyWhitelist); put("type", "STRING_SET"); put("label", "Whitelisted Paths")
                    put("description", "Whitelisted directory paths that the app is allowed to edit.")
                    put("section", "FileSystem"); put("defaultValue", "")
                }))
                store.deferredDispatch(this.name, Action(ActionNames.SETTINGS_ADD, buildJsonObject {
                    put("key", settingKeyFavorites); put("type", "STRING_SET"); put("label", "Favorite Paths")
                    put("description", "A list of favorite directory paths.")
                    put("section", "FileSystem"); put("defaultValue", "")
                }))
            }
            ActionNames.SYSTEM_PUBLISH_STARTING -> {
                store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_NAVIGATE, buildJsonObject { put("path", platformDependencies.getUserHomePath()) }))
            }
            ActionNames.FILESYSTEM_NAVIGATE -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<PathPayload>(it) } ?: return
                store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_LOAD_CHILDREN, buildJsonObject { put("path", payload.path) }))
            }
            ActionNames.FILESYSTEM_SELECT_DIRECTORY_UI -> {
                platformDependencies.selectDirectoryPath()?.let {
                    store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_NAVIGATE, buildJsonObject { put("path", it) }))
                }
            }
            ActionNames.FILESYSTEM_REQUEST_SCOPED_READ_UI -> {
                val requestId = platformDependencies.generateUUID()
                store.deferredDispatch(this.name, Action(
                    name = ActionNames.FILESYSTEM_INTERNAL_STAGE_SCOPED_READ,
                    payload = buildJsonObject {
                        put("requestId", requestId)
                        put("originator", originator)
                        put("requestPayload", action.payload ?: buildJsonObject {})
                    }
                ))
            }
            ActionNames.FILESYSTEM_INTERNAL_STAGE_SCOPED_READ -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<StageScopedReadPayload>(it) } ?: return
                val dialogRequest = buildJsonObject {
                    put("title", "Danger Zone: Grant File Access?")
                    put("text", "You are about to grant the '${payload.originator}' feature one-time read access to a folder and its entire file content.\n\nDo not expose folders with sensitive content.")
                    put("confirmButtonText", "Proceed with Care")
                    put("requestId", payload.requestId)
                }
                store.deferredDispatch(this.name, Action(ActionNames.CORE_SHOW_CONFIRMATION_DIALOG, dialogRequest))
            }
            ActionNames.FILESYSTEM_INTERNAL_EXECUTE_SCOPED_READ -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<RequestScopedReadUiPayload>(it) } ?: RequestScopedReadUiPayload()
                platformDependencies.selectDirectoryPath()?.let { selectedPath ->
                    platformDependencies.log(LogLevel.INFO, name, "User granted one-time access to '$selectedPath' for '$originator'.")
                    try {
                        val listing = if (payload.recursive) platformDependencies.listDirectoryRecursive(selectedPath) else platformDependencies.listDirectory(selectedPath)
                        val filteredListing = payload.fileExtensions?.let { extensions ->
                            listing.filter { fileEntry -> extensions.any { ext -> fileEntry.path.endsWith(".$ext") } }
                        } ?: listing

                        val responsePayload = buildJsonObject {
                            put("listing", Json.encodeToJsonElement(filteredListing))
                            put("subpath", selectedPath)
                        }
                        val envelope = PrivateDataEnvelope(ActionNames.Envelopes.FILESYSTEM_RESPONSE_LIST_RECURSIVE, responsePayload)
                        store.deliverPrivateData(this.name, originator, envelope)
                    } catch (e: Exception) {
                        platformDependencies.log(LogLevel.ERROR, name, "Scoped read failed for '$selectedPath': ${e.message}")
                    }
                } ?: platformDependencies.log(LogLevel.INFO, name, "User cancelled one-time access grant for '$originator'.")
            }
            ActionNames.FILESYSTEM_TOGGLE_ITEM_EXPANDED -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<PathPayload>(it) } ?: return
                val item = findItemByPath(fileSystemState.rootItems, payload.path)
                if (item?.isDirectory == true && item.children == null) {
                    store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_LOAD_CHILDREN, action.payload))
                }
            }
            ActionNames.FILESYSTEM_TOGGLE_ITEM_SELECTED -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<ToggleItemPayload>(it) } ?: return
                if (payload.recursive) {
                    findItemByPath(fileSystemState.rootItems, payload.path)?.let { dispatchLoadChildrenRecursive(it, 3, store) }
                }
            }
            ActionNames.FILESYSTEM_EXPAND_ALL -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<PathPayload>(it) } ?: return
                findItemByPath(fileSystemState.rootItems, payload.path)?.let { dispatchLoadChildrenRecursive(it, 3, store) }
            }
            ActionNames.FILESYSTEM_LOAD_CHILDREN -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<PathPayload>(it) } ?: return
                try {
                    val children = platformDependencies.listDirectory(payload.path)
                    store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_INTERNAL_DIRECTORY_LOADED, buildJsonObject {
                        put("parentPath", payload.path); put("children", Json.encodeToJsonElement(children))
                    }))
                } catch (e: Exception) {
                    store.dispatch(this.name, Action(ActionNames.CORE_SHOW_TOAST, buildJsonObject { put("message", "Failed to read directory ${payload.path}: ${e.message}") }))
                }
            }
            ActionNames.FILESYSTEM_INTERNAL_DIRECTORY_LOADED -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<DirectoryLoadedPayload>(it) } ?: return
                if (findItemByPath(fileSystemState.rootItems, payload.parentPath)?.isSelected == true) {
                    payload.children.forEach { child ->
                        store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_TOGGLE_ITEM_SELECTED, buildJsonObject {
                            put("path", child.path); put("recursive", true)
                        }))
                    }
                }
            }
            ActionNames.FILESYSTEM_COPY_SELECTION_TO_CLIPBOARD -> {
                val selectedFiles = findSelectedFiles(fileSystemState.rootItems)
                if (selectedFiles.isEmpty()) {
                    store.deferredDispatch(this.name, Action(ActionNames.CORE_SHOW_TOAST, buildJsonObject { put("message", "No files selected.") })); return
                }
                val stringBuilder = StringBuilder()
                val rootPath = fileSystemState.currentPath ?: ""
                var errorCount = 0
                selectedFiles.forEach { item ->
                    try {
                        val content = platformDependencies.readFileContent(item.path)
                        val relativePath = item.path.removePrefix(rootPath).removePrefix(platformDependencies.pathSeparator.toString())
                        stringBuilder.append("```${relativePath.substringAfterLast('.', "")} \"$relativePath\"\n$content\n```\n\n")
                    } catch (_: Exception) { errorCount++ }
                }
                if (stringBuilder.isNotEmpty()) {
                    store.deferredDispatch(this.name, Action(ActionNames.CORE_COPY_TO_CLIPBOARD, buildJsonObject { put("text", stringBuilder.toString().trim()) }))
                }
                if (errorCount > 0) {
                    store.deferredDispatch(this.name, Action(ActionNames.CORE_SHOW_TOAST, buildJsonObject { put("message", "Failed to read $errorCount files.") }))
                }
            }
            ActionNames.FILESYSTEM_ADD_WHITELIST_PATH, ActionNames.FILESYSTEM_REMOVE_WHITELIST_PATH -> {
                val path = action.payload?.let { Json.decodeFromJsonElement<PathPayload>(it) }?.path ?: return
                val newSet = if (action.name.startsWith("filesystem.ADD")) fileSystemState.whitelistedPaths + path else fileSystemState.whitelistedPaths - path
                store.deferredDispatch(this.name, Action(ActionNames.SETTINGS_UPDATE, buildJsonObject { put("key", settingKeyWhitelist); put("value", serializeSet(newSet)) }))
            }
            ActionNames.FILESYSTEM_ADD_FAVORITE_PATH, ActionNames.FILESYSTEM_REMOVE_FAVORITE_PATH -> {
                val path = action.payload?.let { Json.decodeFromJsonElement<PathPayload>(it) }?.path ?: return
                val newSet = if (action.name.startsWith("filesystem.ADD")) fileSystemState.favoritePaths + path else fileSystemState.favoritePaths - path
                store.deferredDispatch(this.name, Action(ActionNames.SETTINGS_UPDATE, buildJsonObject { put("key", settingKeyFavorites); put("value", serializeSet(newSet)) }))
            }
            ActionNames.FILESYSTEM_SYSTEM_LIST -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<SystemListPayload>(it) } ?: SystemListPayload()
                val sandboxPath = getSandboxPathFor(originator)
                val fullPath = if (payload.subpath.isNotBlank()) "$sandboxPath${platformDependencies.pathSeparator}${payload.subpath}" else sandboxPath
                try {
                    if (!platformDependencies.fileExists(fullPath)) platformDependencies.createDirectories(fullPath)
                    val absoluteListing = platformDependencies.listDirectory(fullPath)
                    val relativeListing = absoluteListing.map { FileEntry(platformDependencies.getFileName(it.path), it.isDirectory) }
                    val responsePayload = buildJsonObject {
                        put("listing", Json.encodeToJsonElement(relativeListing))
                        put("subpath", payload.subpath)
                    }
                    val envelope = PrivateDataEnvelope(ActionNames.Envelopes.FILESYSTEM_RESPONSE_LIST, responsePayload)
                    store.deliverPrivateData(this.name, originator, envelope)
                } catch (e: Exception) {
                    platformDependencies.log(LogLevel.ERROR, "filesystem","Filesystem listing failed: ${e.message}")
                    val responsePayload = buildJsonObject {
                        put("listing", Json.encodeToJsonElement(emptyList<FileEntry>()))
                        put("subpath", payload.subpath)
                    }
                    val envelope = PrivateDataEnvelope(ActionNames.Envelopes.FILESYSTEM_RESPONSE_LIST, responsePayload)
                    store.deliverPrivateData(this.name, originator, envelope)
                }
            }
            ActionNames.FILESYSTEM_SYSTEM_LIST_RECURSIVE -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<SystemListRecursivePayload>(it) } ?: return
                val sandboxPath = getSandboxPathFor(originator)
                val fullPath = if (payload.subpath.isNotBlank()) "$sandboxPath${platformDependencies.pathSeparator}${payload.subpath}" else sandboxPath
                try {
                    if (!platformDependencies.fileExists(fullPath)) platformDependencies.createDirectories(fullPath)
                    val absoluteListing = platformDependencies.listDirectoryRecursive(fullPath)
                    val relativeListing = absoluteListing.map { it.copy(path = it.path.removePrefix(sandboxPath).removePrefix(platformDependencies.pathSeparator.toString())) }
                    val responsePayload = buildJsonObject {
                        put("listing", Json.encodeToJsonElement(relativeListing))
                        put("subpath", payload.subpath)
                    }
                    val envelope = PrivateDataEnvelope(ActionNames.Envelopes.FILESYSTEM_RESPONSE_LIST_RECURSIVE, responsePayload)
                    store.deliverPrivateData(this.name, originator, envelope)
                } catch (e: Exception) {
                    platformDependencies.log(LogLevel.ERROR, "filesystem", "Recursive directory read failed for '${payload.subpath}': ${e.message}")
                    val responsePayload = buildJsonObject {
                        put("listing", Json.encodeToJsonElement(emptyList<FileEntry>()))
                        put("subpath", payload.subpath)
                    }
                    val envelope = PrivateDataEnvelope(ActionNames.Envelopes.FILESYSTEM_RESPONSE_LIST_RECURSIVE, responsePayload)
                    store.deliverPrivateData(this.name, originator, envelope)
                }
            }
            ActionNames.FILESYSTEM_READ_FILES_CONTENT -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<ReadFilesContentPayload>(it) } ?: return
                val sandboxPath = getSandboxPathFor(originator)
                val contentMap = mutableMapOf<String, String>()
                payload.subpaths.forEach { subpath ->
                    val fullPath = "$sandboxPath${platformDependencies.pathSeparator}$subpath"
                    try {
                        contentMap[subpath] = platformDependencies.readFileContent(fullPath)
                    } catch (e: Exception) {
                        platformDependencies.log(LogLevel.WARN, "filesystem", "Bulk read failed for one file '$subpath': ${e.message}")
                    }
                }
                val responsePayload = buildJsonObject { put("contents", Json.encodeToJsonElement(contentMap)) }
                val envelope = PrivateDataEnvelope(ActionNames.Envelopes.FILESYSTEM_RESPONSE_FILES_CONTENT, responsePayload)
                store.deliverPrivateData(this.name, originator, envelope)
            }
            ActionNames.FILESYSTEM_SYSTEM_READ -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<SystemReadPayload>(it) } ?: return
                val fullPath = "${getSandboxPathFor(originator)}${platformDependencies.pathSeparator}${payload.subpath}"
                try {
                    val responsePayload = buildJsonObject {
                        put("subpath", payload.subpath)
                        put("content", cryptoManager.decrypt(platformDependencies.readFileContent(fullPath)))
                    }
                    val envelope = PrivateDataEnvelope(ActionNames.Envelopes.FILESYSTEM_RESPONSE_READ, responsePayload)
                    store.deliverPrivateData(this.name, originator, envelope)
                } catch (e: Exception) {
                    val responsePayload = buildJsonObject {
                        put("subpath", payload.subpath)
                        put("content", JsonNull)
                    }
                    val envelope = PrivateDataEnvelope(ActionNames.Envelopes.FILESYSTEM_RESPONSE_READ, responsePayload)
                    store.deliverPrivateData(this.name, originator, envelope)
                }
            }
            ActionNames.FILESYSTEM_SYSTEM_WRITE -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<SystemWritePayload>(it) } ?: return
                val fullPath = "${getSandboxPathFor(originator)}${platformDependencies.pathSeparator}${payload.subpath}"
                try {
                    platformDependencies.writeFileContent(fullPath, if (payload.encrypt) cryptoManager.encrypt(payload.content) else payload.content)
                } catch (e: Exception) {
                    store.deferredDispatch(this.name, Action(ActionNames.CORE_SHOW_TOAST, buildJsonObject { put("message", "Error writing system file: ${e.message}") }))
                }
            }
            ActionNames.FILESYSTEM_SYSTEM_DELETE -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<SystemDeletePayload>(it) } ?: return
                val fullPath = "${getSandboxPathFor(originator)}${platformDependencies.pathSeparator}${payload.subpath}"
                if (platformDependencies.fileExists(fullPath)) platformDependencies.deleteFile(fullPath)
            }
            ActionNames.FILESYSTEM_SYSTEM_DELETE_DIRECTORY -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<SystemDeleteDirectoryPayload>(it) } ?: return
                val fullPath = "${getSandboxPathFor(originator)}${platformDependencies.pathSeparator}${payload.subpath}"
                if (platformDependencies.fileExists(fullPath)) platformDependencies.deleteDirectory(fullPath)
            }
            ActionNames.FILESYSTEM_OPEN_SYSTEM_FOLDER -> platformDependencies.openFolderInExplorer(getSandboxPathFor(originator))
            ActionNames.FILESYSTEM_OPEN_APP_SUBFOLDER -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<OpenAppSubfolderPayload>(it) } ?: return
                platformDependencies.openFolderInExplorer("${platformDependencies.getBasePathFor(BasePath.APP_ZONE)}${platformDependencies.pathSeparator}${payload.folder}")
            }
        }
    }

    override fun reducer(state: FeatureState?, action: Action): FeatureState? {
        val currentFeatureState = state as? FileSystemState ?: FileSystemState()
        val payload = action.payload

        when (action.name) {
            ActionNames.FILESYSTEM_INTERNAL_STAGE_SCOPED_READ -> {
                val decoded = payload?.let { Json.decodeFromJsonElement<StageScopedReadPayload>(it) } ?: return currentFeatureState
                val requestPayload = Json.decodeFromJsonElement<RequestScopedReadUiPayload>(decoded.requestPayload)
                val pendingRequest = PendingScopedRead(
                    requestId = decoded.requestId,
                    originator = decoded.originator,
                    payload = requestPayload
                )
                return currentFeatureState.copy(pendingScopedRead = pendingRequest)
            }
            ActionNames.CORE_DISMISS_CONFIRMATION_DIALOG -> {
                // Clear the pending request once the dialog is dismissed, regardless of outcome.
                return currentFeatureState.copy(pendingScopedRead = null)
            }
            ActionNames.FILESYSTEM_INTERNAL_DIRECTORY_LOADED -> {
                val decoded = payload?.let { Json.decodeFromJsonElement<DirectoryLoadedPayload>(it) } ?: return currentFeatureState
                val newChildren = decoded.children.map { FileSystemItem(it.path, platformDependencies.getFileName(it.path), it.isDirectory) }
                return if (currentFeatureState.currentPath == decoded.parentPath) currentFeatureState.copy(rootItems = newChildren, error = null)
                else currentFeatureState.copy(rootItems = updateItemByPath(currentFeatureState.rootItems, decoded.parentPath) { it.copy(children = newChildren) })
            }
            ActionNames.FILESYSTEM_NAVIGATE -> return currentFeatureState.copy(currentPath = payload?.let { Json.decodeFromJsonElement<PathPayload>(it) }?.path)
            ActionNames.FILESYSTEM_TOGGLE_ITEM_EXPANDED -> {
                val path = payload?.let { Json.decodeFromJsonElement<PathPayload>(it) }?.path ?: return currentFeatureState
                return currentFeatureState.copy(rootItems = updateItemByPath(currentFeatureState.rootItems, path) { it.copy(isExpanded = !it.isExpanded) })
            }
            ActionNames.FILESYSTEM_TOGGLE_ITEM_SELECTED -> {
                val decoded = payload?.let { Json.decodeFromJsonElement<ToggleItemPayload>(it) } ?: return currentFeatureState
                val targetItem = findItemByPath(currentFeatureState.rootItems, decoded.path)
                val targetState = if (targetItem?.isDirectory == true) { val stats = getSelectionStats(targetItem); stats.selectedCount < stats.totalCount }
                else !(targetItem?.isSelected ?: false)
                return currentFeatureState.copy(rootItems = updateItemByPath(currentFeatureState.rootItems, decoded.path, decoded.recursive) { it.copy(isSelected = targetState) })
            }
            ActionNames.FILESYSTEM_EXPAND_ALL -> return currentFeatureState.copy(rootItems = updateExpansionStateRecursive(currentFeatureState.rootItems, payload?.let { Json.decodeFromJsonElement<PathPayload>(it) }?.path ?: "", true))
            ActionNames.FILESYSTEM_COLLAPSE_ALL -> return currentFeatureState.copy(rootItems = updateExpansionStateRecursive(currentFeatureState.rootItems, payload?.let { Json.decodeFromJsonElement<PathPayload>(it) }?.path ?: "", false))
            ActionNames.FILESYSTEM_ADD_WHITELIST_PATH -> return currentFeatureState.copy(whitelistedPaths = currentFeatureState.whitelistedPaths + (payload?.let { Json.decodeFromJsonElement<PathPayload>(it) }?.path ?: ""))
            ActionNames.FILESYSTEM_REMOVE_WHITELIST_PATH -> return currentFeatureState.copy(whitelistedPaths = currentFeatureState.whitelistedPaths - (payload?.let { Json.decodeFromJsonElement<PathPayload>(it) }?.path ?: ""))
            ActionNames.FILESYSTEM_ADD_FAVORITE_PATH -> return currentFeatureState.copy(favoritePaths = currentFeatureState.favoritePaths + (payload?.let { Json.decodeFromJsonElement<PathPayload>(it) }?.path ?: ""))
            ActionNames.FILESYSTEM_REMOVE_FAVORITE_PATH -> return currentFeatureState.copy(favoritePaths = currentFeatureState.favoritePaths - (payload?.let { Json.decodeFromJsonElement<PathPayload>(it) }?.path ?: ""))
            ActionNames.SETTINGS_PUBLISH_LOADED -> return currentFeatureState.copy(
                whitelistedPaths = deserializeSet(payload?.get(settingKeyWhitelist)?.jsonPrimitive?.content),
                favoritePaths = deserializeSet(payload?.get(settingKeyFavorites)?.jsonPrimitive?.content)
            )
            ActionNames.SETTINGS_PUBLISH_VALUE_CHANGED -> {
                when (payload?.get("key")?.jsonPrimitive?.content) {
                    settingKeyWhitelist -> return currentFeatureState.copy(whitelistedPaths = deserializeSet(payload["value"]?.jsonPrimitive?.content))
                    settingKeyFavorites -> return currentFeatureState.copy(favoritePaths = deserializeSet(payload["value"]?.jsonPrimitive?.content))
                }
            }
        }
        return currentFeatureState
    }

    private fun dispatchLoadChildrenRecursive(item: FileSystemItem, maxDepth: Int, store: Store) {
        if (maxDepth <= 0 || !item.isDirectory) return
        if (item.children == null) store.dispatch(this.name, Action(ActionNames.FILESYSTEM_LOAD_CHILDREN, buildJsonObject { put("path", item.path) }))
        else item.children.forEach { dispatchLoadChildrenRecursive(it, maxDepth - 1, store) }
    }

    private fun serializeSet(set: Set<String>): String = set.joinToString(",")
    private fun deserializeSet(str: String?): Set<String> = str?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    private fun updateExpansionStateRecursive(items: List<FileSystemItem>, path: String, expand: Boolean): List<FileSystemItem> = items.map { item ->
        when {
            item.path == path -> expandOrCollapse(item, expand, 3)
            path.startsWith(item.path) && item.children != null -> item.copy(children = updateExpansionStateRecursive(item.children, path, expand))
            else -> item
        }
    }
    private fun expandOrCollapse(item: FileSystemItem, expand: Boolean, maxDepth: Int): FileSystemItem {
        if (!item.isDirectory || maxDepth <= 0) return item.copy(isExpanded = expand)
        return item.copy(isExpanded = expand, children = item.children?.map { expandOrCollapse(it, expand, maxDepth - 1) })
    }
    private fun updateItemByPath(items: List<FileSystemItem>, path: String, update: (FileSystemItem) -> FileSystemItem): List<FileSystemItem> = items.map { item ->
        when {
            item.path == path -> update(item)
            path.startsWith(item.path) && item.children != null -> item.copy(children = updateItemByPath(item.children, path, update))
            else -> item
        }
    }
    private fun updateItemByPath(items: List<FileSystemItem>, path: String, recursive: Boolean, update: (FileSystemItem) -> FileSystemItem): List<FileSystemItem> = items.map { item ->
        var updatedItem = item
        if (item.path == path) {
            updatedItem = update(item)
            if (recursive && updatedItem.children != null) updatedItem = updatedItem.copy(children = updatedItem.children.map { updateItemRecursive(it, update) })
        } else if (path.startsWith(item.path) && item.children != null) {
            updatedItem = item.copy(children = updateItemByPath(item.children, path, recursive, update))
        }
        updatedItem
    }
    private fun updateItemRecursive(item: FileSystemItem, update: (FileSystemItem) -> FileSystemItem): FileSystemItem {
        val updated = update(item)
        return if (updated.children != null) updated.copy(children = updated.children.map { updateItemRecursive(it, update) }) else updated
    }
    private fun findItemByPath(items: List<FileSystemItem>, path: String): FileSystemItem? {
        items.forEach { item ->
            if (item.path == path) return item
            if (path.startsWith(item.path)) item.children?.let { findItemByPath(it, path)?.let { return it } }
        }
        return null
    }
    private fun findSelectedFiles(items: List<FileSystemItem>): List<FileSystemItem> = items.flatMap { item ->
        when {
            item.isSelected && !item.isDirectory -> listOf(item)
            item.isDirectory && item.children != null -> findSelectedFiles(item.children)
            else -> emptyList()
        }
    }
    private data class SelectionStats(val selectedCount: Int, val totalCount: Int)
    private fun getSelectionStats(item: FileSystemItem): SelectionStats {
        if (!item.isDirectory) return SelectionStats(if (item.isSelected) 1 else 0, 1)
        if (item.children.isNullOrEmpty()) return SelectionStats(0, 0)
        return item.children.map { getSelectionStats(it) }.let { stats -> SelectionStats(stats.sumOf { it.selectedCount }, stats.sumOf { it.totalCount }) }
    }

    inner class FileSystemComposableProvider : Feature.ComposableProvider {
        private val viewKey = "feature.filesystem.main"
        override val stageViews: Map<String, @Composable (Store, List<Feature>) -> Unit> = mapOf(
            viewKey to { store, _ -> FileSystemView(store, platformDependencies) }
        )
        @Composable override fun RibbonContent(store: Store, activeViewKey: String?) {
            val isActive = activeViewKey == viewKey
            IconButton(onClick = { store.dispatch("filesystem.ui", Action(ActionNames.CORE_SET_ACTIVE_VIEW, buildJsonObject { put("key", viewKey) })) }) {
                Icon(Icons.Default.Folder, "File System Browser", tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}