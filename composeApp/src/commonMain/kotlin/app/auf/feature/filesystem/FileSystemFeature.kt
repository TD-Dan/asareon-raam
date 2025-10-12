package app.auf.feature.filesystem

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import app.auf.core.*
import app.auf.util.BasePath
import app.auf.util.FileEntry
import app.auf.util.PlatformDependencies
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
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
    @Serializable private data class NavigationFailedPayload(val path: String, val error: String)
    @Serializable private data class SystemReadPayload(val subpath: String)
    @Serializable private data class SystemWritePayload(val subpath: String, val content: String, val encrypt: Boolean = false)
    @Serializable private data class SystemDeletePayload(val subpath: String)
    @Serializable private data class SystemDeleteDirectoryPayload(val subpath: String)
    @Serializable private data class OpenAppSubfolderPayload(val folder: String)

    private val settingKeyWhitelist = "filesystem.whitelistedPaths"
    private val settingKeyFavorites = "filesystem.favoritePaths"

    private fun getSandboxPathFor(originator: String): String {
        val appZoneRoot = platformDependencies.getBasePathFor(BasePath.APP_ZONE)
        val safeOriginator = originator.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return "$appZoneRoot${platformDependencies.pathSeparator}$safeOriginator"
    }

    override fun onAction(action: Action, store: Store) {
        val originator = action.originator ?: return
        when (action.name) {
            "system.INITIALIZING" -> {
                store.dispatch(this.name, Action("settings.ADD", buildJsonObject {
                    put("key", settingKeyWhitelist); put("type", "STRING_SET"); put("label", "Whitelisted Paths")
                    put("description", "Whitelisted directory paths that the app is allowed to edit.")
                    put("section", "FileSystem"); put("defaultValue", "")
                }))
                store.dispatch(this.name, Action("settings.ADD", buildJsonObject {
                    put("key", settingKeyFavorites); put("type", "STRING_SET"); put("label", "Favorite Paths")
                    put("description", "A list of favorite directory paths.")
                    put("section", "FileSystem"); put("defaultValue", "")
                }))
            }
            "system.STARTING" -> {
                store.dispatch(this.name, Action("filesystem.NAVIGATE", buildJsonObject { put("path", platformDependencies.getUserHomePath()) }))
            }
            "filesystem.NAVIGATE" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<PathPayload>(it) } ?: return
                store.dispatch(this.name, Action("filesystem.LOAD_CHILDREN", buildJsonObject { put("path", payload.path) }))
            }
            "filesystem.SELECT_DIRECTORY_UI" -> {
                platformDependencies.selectDirectoryPath()?.let {
                    store.dispatch(this.name, Action("filesystem.NAVIGATE", buildJsonObject { put("path", it) }))
                }
            }
            "filesystem.TOGGLE_ITEM_EXPANDED" -> {
                val state = store.state.value.featureStates[name] as? FileSystemState ?: return
                val payload = action.payload?.let { Json.decodeFromJsonElement<PathPayload>(it) } ?: return
                val item = findItemByPath(state.rootItems, payload.path)
                if (item?.isDirectory == true && item.children == null) {
                    store.dispatch(this.name, Action("filesystem.LOAD_CHILDREN", action.payload))
                }
            }
            "filesystem.TOGGLE_ITEM_SELECTED" -> {
                val state = store.state.value.featureStates[name] as? FileSystemState ?: return
                val payload = action.payload?.let { Json.decodeFromJsonElement<ToggleItemPayload>(it) } ?: return
                if (payload.recursive) {
                    findItemByPath(state.rootItems, payload.path)?.let { dispatchLoadChildrenRecursive(it, 3, store) }
                }
            }
            "filesystem.EXPAND_ALL" -> {
                val state = store.state.value.featureStates[name] as? FileSystemState ?: return
                val payload = action.payload?.let { Json.decodeFromJsonElement<PathPayload>(it) } ?: return
                findItemByPath(state.rootItems, payload.path)?.let { dispatchLoadChildrenRecursive(it, 3, store) }
            }
            "filesystem.LOAD_CHILDREN" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<PathPayload>(it) } ?: return
                try {
                    val children = platformDependencies.listDirectory(payload.path)
                    store.dispatch(this.name, Action("filesystem.internal.DIRECTORY_LOADED", buildJsonObject {
                        put("parentPath", payload.path); put("children", Json.encodeToJsonElement(children))
                    }))
                } catch (e: Exception) {
                    store.dispatch(this.name, Action("core.SHOW_TOAST", buildJsonObject { put("message", "Failed to read directory ${payload.path}: ${e.message}") }))
                }
            }
            "filesystem.internal.DIRECTORY_LOADED" -> {
                val state = store.state.value.featureStates[name] as? FileSystemState ?: return
                val payload = action.payload?.let { Json.decodeFromJsonElement<DirectoryLoadedPayload>(it) } ?: return
                if (findItemByPath(state.rootItems, payload.parentPath)?.isSelected == true) {
                    payload.children.forEach { child ->
                        store.dispatch(this.name, Action("filesystem.TOGGLE_ITEM_SELECTED", buildJsonObject {
                            put("path", child.path); put("recursive", true)
                        }))
                    }
                }
            }
            "filesystem.COPY_SELECTION_TO_CLIPBOARD" -> {
                val state = store.state.value.featureStates[name] as? FileSystemState ?: return
                val selectedFiles = findSelectedFiles(state.rootItems)
                if (selectedFiles.isEmpty()) {
                    store.dispatch(this.name, Action("core.SHOW_TOAST", buildJsonObject { put("message", "No files selected.") })); return
                }
                val stringBuilder = StringBuilder()
                val rootPath = state.currentPath ?: ""
                var errorCount = 0
                selectedFiles.forEach { item ->
                    try {
                        val content = platformDependencies.readFileContent(item.path)
                        val relativePath = item.path.removePrefix(rootPath).removePrefix(platformDependencies.pathSeparator.toString())
                        stringBuilder.append("```${relativePath.substringAfterLast('.', "")} \"$relativePath\"\n$content\n```\n\n")
                    } catch (e: Exception) { errorCount++ }
                }
                if (stringBuilder.isNotEmpty()) {
                    store.dispatch(this.name, Action("core.COPY_TO_CLIPBOARD", buildJsonObject { put("text", stringBuilder.toString().trim()) }))
                }
                if (errorCount > 0) {
                    store.dispatch(this.name, Action("core.SHOW_TOAST", buildJsonObject { put("message", "Failed to read $errorCount files.") }))
                }
            }
            "filesystem.ADD_WHITELIST_PATH", "filesystem.REMOVE_WHITELIST_PATH" -> {
                val state = store.state.value.featureStates[name] as? FileSystemState ?: return
                val path = action.payload?.let { Json.decodeFromJsonElement<PathPayload>(it) }?.path ?: return
                val newSet = if (action.name.startsWith("filesystem.ADD")) state.whitelistedPaths + path else state.whitelistedPaths - path
                store.dispatch(this.name, Action("settings.UPDATE", buildJsonObject { put("key", settingKeyWhitelist); put("value", serializeSet(newSet)) }))
            }
            "filesystem.ADD_FAVORITE_PATH", "filesystem.REMOVE_FAVORITE_PATH" -> {
                val state = store.state.value.featureStates[name] as? FileSystemState ?: return
                val path = action.payload?.let { Json.decodeFromJsonElement<PathPayload>(it) }?.path ?: return
                val newSet = if (action.name.startsWith("filesystem.ADD")) state.favoritePaths + path else state.favoritePaths - path
                store.dispatch(this.name, Action("settings.UPDATE", buildJsonObject { put("key", settingKeyFavorites); put("value", serializeSet(newSet)) }))
            }
            "filesystem.SYSTEM_LIST" -> {
                val sandboxPath = getSandboxPathFor(originator)
                try {
                    if (!platformDependencies.fileExists(sandboxPath)) platformDependencies.createDirectories(sandboxPath)
                    store.deliverPrivateData(this.name, originator, platformDependencies.listDirectory(sandboxPath))
                } catch (e: Exception) { store.deliverPrivateData(this.name, originator, emptyList<FileEntry>()) }
            }
            "filesystem.SYSTEM_READ" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<SystemReadPayload>(it) } ?: return
                val fullPath = "${getSandboxPathFor(originator)}${platformDependencies.pathSeparator}${payload.subpath}"
                try {
                    store.deliverPrivateData(this.name, originator, buildJsonObject {
                        put("subpath", payload.subpath); put("content", cryptoManager.decrypt(platformDependencies.readFileContent(fullPath)))
                    })
                } catch (e: Exception) {
                    store.deliverPrivateData(this.name, originator, buildJsonObject {
                        put("subpath", payload.subpath); put("content", JsonNull)
                    })
                }
            }
            "filesystem.SYSTEM_WRITE" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<SystemWritePayload>(it) } ?: return
                val fullPath = "${getSandboxPathFor(originator)}${platformDependencies.pathSeparator}${payload.subpath}"
                try {
                    platformDependencies.writeFileContent(fullPath, if (payload.encrypt) cryptoManager.encrypt(payload.content) else payload.content)
                } catch (e: Exception) {
                    store.dispatch(this.name, Action("core.SHOW_TOAST", buildJsonObject { put("message", "Error writing system file: ${e.message}") }))
                }
            }
            "filesystem.SYSTEM_DELETE" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<SystemDeletePayload>(it) } ?: return
                val fullPath = "${getSandboxPathFor(originator)}${platformDependencies.pathSeparator}${payload.subpath}"
                if (platformDependencies.fileExists(fullPath)) platformDependencies.deleteFile(fullPath)
            }
            "filesystem.SYSTEM_DELETE_DIRECTORY" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<SystemDeleteDirectoryPayload>(it) } ?: return
                val fullPath = "${getSandboxPathFor(originator)}${platformDependencies.pathSeparator}${payload.subpath}"
                if (platformDependencies.fileExists(fullPath)) platformDependencies.deleteDirectory(fullPath)
            }
            "filesystem.OPEN_SYSTEM_FOLDER" -> platformDependencies.openFolderInExplorer(getSandboxPathFor(originator))
            "filesystem.OPEN_APP_SUBFOLDER" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<OpenAppSubfolderPayload>(it) } ?: return
                platformDependencies.openFolderInExplorer("${platformDependencies.getBasePathFor(BasePath.APP_ZONE)}${platformDependencies.pathSeparator}${payload.folder}")
            }
        }
    }

    override fun reducer(state: AppState, action: Action): AppState {
        val currentFeatureState = state.featureStates[name] as? FileSystemState ?: FileSystemState()
        var newFeatureState: FileSystemState? = null
        val payload = action.payload
        when (action.name) {
            "filesystem.internal.DIRECTORY_LOADED" -> {
                val decoded = payload?.let { Json.decodeFromJsonElement<DirectoryLoadedPayload>(it) } ?: return state
                val newChildren = decoded.children.map { FileSystemItem(it.path, platformDependencies.getFileName(it.path), it.isDirectory) }
                newFeatureState = if (currentFeatureState.currentPath == decoded.parentPath) currentFeatureState.copy(rootItems = newChildren, error = null)
                else currentFeatureState.copy(rootItems = updateItemByPath(currentFeatureState.rootItems, decoded.parentPath) { it.copy(children = newChildren) })
            }
            "filesystem.NAVIGATE" -> newFeatureState = currentFeatureState.copy(currentPath = payload?.let { Json.decodeFromJsonElement<PathPayload>(it) }?.path)
            "filesystem.publish.NAVIGATION_FAILED" -> newFeatureState = currentFeatureState.copy(error = payload?.let { Json.decodeFromJsonElement<NavigationFailedPayload>(it) }?.error, rootItems = emptyList())
            "filesystem.TOGGLE_ITEM_EXPANDED" -> {
                val path = payload?.let { Json.decodeFromJsonElement<PathPayload>(it) }?.path ?: return state
                newFeatureState = currentFeatureState.copy(rootItems = updateItemByPath(currentFeatureState.rootItems, path) { it.copy(isExpanded = !it.isExpanded) })
            }
            "filesystem.TOGGLE_ITEM_SELECTED" -> {
                val decoded = payload?.let { Json.decodeFromJsonElement<ToggleItemPayload>(it) } ?: return state
                val targetItem = findItemByPath(currentFeatureState.rootItems, decoded.path)
                val targetState = if (targetItem?.isDirectory == true) { val stats = getSelectionStats(targetItem); stats.selectedCount < stats.totalCount }
                else !(targetItem?.isSelected ?: false)
                newFeatureState = currentFeatureState.copy(rootItems = updateItemByPath(currentFeatureState.rootItems, decoded.path, decoded.recursive) { it.copy(isSelected = targetState) })
            }
            "filesystem.EXPAND_ALL" -> newFeatureState = currentFeatureState.copy(rootItems = updateExpansionStateRecursive(currentFeatureState.rootItems, payload?.let { Json.decodeFromJsonElement<PathPayload>(it) }?.path ?: "", true))
            "filesystem.COLLAPSE_ALL" -> newFeatureState = currentFeatureState.copy(rootItems = updateExpansionStateRecursive(currentFeatureState.rootItems, payload?.let { Json.decodeFromJsonElement<PathPayload>(it) }?.path ?: "", false))
            "filesystem.ADD_WHITELIST_PATH" -> newFeatureState = currentFeatureState.copy(whitelistedPaths = currentFeatureState.whitelistedPaths + (payload?.let { Json.decodeFromJsonElement<PathPayload>(it) }?.path ?: ""))
            "filesystem.REMOVE_WHITELIST_PATH" -> newFeatureState = currentFeatureState.copy(whitelistedPaths = currentFeatureState.whitelistedPaths - (payload?.let { Json.decodeFromJsonElement<PathPayload>(it) }?.path ?: ""))
            "filesystem.ADD_FAVORITE_PATH" -> newFeatureState = currentFeatureState.copy(favoritePaths = currentFeatureState.favoritePaths + (payload?.let { Json.decodeFromJsonElement<PathPayload>(it) }?.path ?: ""))
            "filesystem.REMOVE_FAVORITE_PATH" -> newFeatureState = currentFeatureState.copy(favoritePaths = currentFeatureState.favoritePaths - (payload?.let { Json.decodeFromJsonElement<PathPayload>(it) }?.path ?: ""))
            "settings.publish.LOADED" -> newFeatureState = currentFeatureState.copy(
                whitelistedPaths = deserializeSet(payload?.get(settingKeyWhitelist)?.jsonPrimitive?.content),
                favoritePaths = deserializeSet(payload?.get(settingKeyFavorites)?.jsonPrimitive?.content)
            )
            "settings.publish.VALUE_CHANGED" -> {
                when (payload?.get("key")?.jsonPrimitive?.content) {
                    settingKeyWhitelist -> newFeatureState = currentFeatureState.copy(whitelistedPaths = deserializeSet(payload["value"]?.jsonPrimitive?.content))
                    settingKeyFavorites -> newFeatureState = currentFeatureState.copy(favoritePaths = deserializeSet(payload["value"]?.jsonPrimitive?.content))
                }
            }
        }
        return newFeatureState?.let { if (it != currentFeatureState) state.copy(featureStates = state.featureStates + (name to it)) else state } ?: state
    }

    private fun dispatchLoadChildrenRecursive(item: FileSystemItem, maxDepth: Int, store: Store) {
        if (maxDepth <= 0 || !item.isDirectory) return
        if (item.children == null) store.dispatch(this.name, Action("filesystem.LOAD_CHILDREN", buildJsonObject { put("path", item.path) }))
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
            IconButton(onClick = { store.dispatch("filesystem.ui", Action("core.SET_ACTIVE_VIEW", buildJsonObject { put("key", viewKey) })) }) {
                Icon(Icons.Default.Folder, "File System Browser", tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
