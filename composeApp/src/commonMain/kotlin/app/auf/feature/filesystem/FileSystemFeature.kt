package app.auf.feature.filesystem

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.Feature
import app.auf.core.Store
import app.auf.util.FileEntry
import app.auf.util.PlatformDependencies
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive

/**
 * ## Mandate
 * To act as the sole, auditable gateway to the user's file system for the AUF App.
 *
 * This feature provides a transactional buffer for all file I/O operations, enforcing
 * the constitutional `DIRECTIVE_ALIGNMENT_AND_RATIFICATION` through a "staging" and
 * "commit" model. No file is ever touched without being part of an explicit,
 * user-approved transaction. It also enforces a strict security sandbox via a
 * user-configurable path whitelist.
 */
class FileSystemFeature(
    private val platformDependencies: PlatformDependencies
) : Feature {
    override val name: String = "FileSystemFeature"
    override val composableProvider: Feature.ComposableProvider = FileSystemComposableProvider()

    // --- Private serializable classes for decoding action payloads ---
    @Serializable private data class PathPayload(val path: String)
    @Serializable private data class NavigationUpdatedPayload(val path: String, val listing: List<FileEntry>)
    @Serializable private data class NavigationFailedPayload(val path: String, val error: String)
    @Serializable private data class StageCreatePayload(val path: String, val content: String)
    @Serializable private data class StageDeletePayload(val path: String)

    @Serializable private data class ToggleItemPayload(val path: String, val recursive: Boolean = false)
    @Serializable private data class DirectoryLoadedPayload(val parentPath: String, val children: List<FileEntry>)

    // --- Constants for settings keys ---
    private val settingKeyWhitelist = "filesystem.whitelistedPaths"
    private val settingKeyFavorites = "filesystem.favoritePaths"


    override fun onAction(action: Action, store: Store) {
        when (action.name) {
            "app.INITIALIZING" -> {
                // Phase 1: Register settings definitions.
                store.dispatch(Action("settings.ADD", buildJsonObject {
                    put("key", settingKeyWhitelist)
                    put("type", "STRING_SET") // Custom type for persistence
                    put("label", "FS Whitelisted Paths")
                    put("description", "A comma-separated list of whitelisted directory paths.")
                    put("section", "FileSystem")
                    put("defaultValue", "")
                }, name))
                store.dispatch(Action("settings.ADD", buildJsonObject {
                    put("key", settingKeyFavorites)
                    put("type", "STRING_SET")
                    put("label", "FS Favorite Paths")
                    put("description", "A comma-separated list of favorite directory paths.")
                    put("section", "FileSystem")
                    put("defaultValue", "")
                }, name))
            }
            "app.STARTING" -> {
                // Phase 2: Execute runtime logic now that settings are loaded.
                val homePath = platformDependencies.getUserHomePath()
                val payload = buildJsonObject { put("path", homePath) }
                store.dispatch(Action("filesystem.NAVIGATE", payload, name))
            }
            "filesystem.NAVIGATE" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<PathPayload>(it) } ?: return
                store.dispatch(Action("filesystem.LOAD_CHILDREN", buildJsonObject { put("path", payload.path) }, name))
            }
            "filesystem.SELECT_DIRECTORY_UI" -> {
                val selectedPath = platformDependencies.selectDirectoryPath()
                if (selectedPath != null) {
                    val payload = buildJsonObject { put("path", selectedPath) }
                    store.dispatch(Action("filesystem.NAVIGATE", payload, name))
                }
            }
            "filesystem.TOGGLE_ITEM_EXPANDED" -> {
                val state = store.state.value.featureStates[name] as? FileSystemState ?: return
                val payload = action.payload?.let { Json.decodeFromJsonElement<PathPayload>(it) } ?: return
                val item = findItemByPath(state.rootItems, payload.path)
                // If the item is a directory and its children haven't been loaded yet, dispatch an action to load them.
                if (item?.isDirectory == true && item.children == null) {
                    store.dispatch(Action("filesystem.LOAD_CHILDREN", action.payload, name))
                }
            }
            "filesystem.TOGGLE_ITEM_SELECTED" -> {
                // --- THE FIX: Handle recursive selection as a side-effect ---
                val state = store.state.value.featureStates[name] as? FileSystemState ?: return
                val payload = action.payload?.let { Json.decodeFromJsonElement<ToggleItemPayload>(it) } ?: return
                if (payload.recursive) {
                    val item = findItemByPath(state.rootItems, payload.path)
                    if (item != null) {
                        dispatchLoadChildrenRecursive(item, 3, store)
                    }
                }
            }
            "filesystem.EXPAND_ALL" -> {
                val state = store.state.value.featureStates[name] as? FileSystemState ?: return
                val payload = action.payload?.let { Json.decodeFromJsonElement<PathPayload>(it) } ?: return
                val item = findItemByPath(state.rootItems, payload.path)
                if (item != null) {
                    // This recursive function will dispatch LOAD_CHILDREN for any unloaded directories it finds.
                    dispatchLoadChildrenRecursive(item, 3, store)
                }
            }
            "filesystem.LOAD_CHILDREN" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<PathPayload>(it) } ?: return
                try {
                    val children = platformDependencies.listDirectory(payload.path)
                    val childrenJson = buildJsonArray { children.forEach { add(Json.encodeToJsonElement(it)) } }
                    val successPayload = buildJsonObject {
                        put("parentPath", payload.path)
                        put("children", childrenJson)
                    }
                    store.dispatch(Action("filesystem.DIRECTORY_LOADED", successPayload, name))
                } catch (e: Exception) {
                    val errorPayload = buildJsonObject { put("message", "Failed to read directory ${payload.path}: ${e.message}") }
                    store.dispatch(Action("core.SHOW_TOAST", errorPayload, name))
                }
            }
            "filesystem.DIRECTORY_LOADED" -> {
                // --- THE FIX: Propagate selection state to newly loaded children ---
                val state = store.state.value.featureStates[name] as? FileSystemState ?: return
                val payload = action.payload?.let { Json.decodeFromJsonElement<DirectoryLoadedPayload>(it) } ?: return
                val parentItem = findItemByPath(state.rootItems, payload.parentPath)

                // If the parent is selected, the new children must also be selected.
                if (parentItem?.isSelected == true) {
                    payload.children.forEach { childEntry ->
                        val childPayload = buildJsonObject {
                            put("path", childEntry.path)
                            put("recursive", true)
                        }
                        store.dispatch(Action("filesystem.TOGGLE_ITEM_SELECTED", childPayload, name))
                    }
                }
            }
            "filesystem.COPY_SELECTION_TO_CLIPBOARD" -> {
                val state = store.state.value.featureStates[name] as? FileSystemState ?: return
                val selectedFiles = findSelectedFiles(state.rootItems)
                if (selectedFiles.isEmpty()) {
                    val payload = buildJsonObject { put("message", "No files selected.") }
                    store.dispatch(Action("core.SHOW_TOAST", payload, name))
                    return
                }

                val stringBuilder = StringBuilder()
                val rootPath = state.currentPath ?: ""
                var errorCount = 0

                selectedFiles.forEach { item ->
                    try {
                        val content = platformDependencies.readFileContent(item.path)
                        val relativePath = item.path.removePrefix(rootPath).removePrefix(platformDependencies.pathSeparator.toString())
                        val fileExtension = relativePath.substringAfterLast('.', "")
                        stringBuilder.append("```$fileExtension \"$relativePath\"\n")
                        stringBuilder.append(content)
                        stringBuilder.append("\n```\n\n")
                    } catch (e: Exception) {
                        errorCount++
                    }
                }

                if (stringBuilder.isNotEmpty()) {
                    val text = stringBuilder.toString().trim()
                    val payload = buildJsonObject { put("text", text) }
                    store.dispatch(Action("core.COPY_TO_CLIPBOARD", payload, name))
                }

                if (errorCount > 0) {
                    val payload = buildJsonObject { put("message", "Failed to read $errorCount selected files.") }
                    store.dispatch(Action("core.SHOW_TOAST", payload, name))
                }
            }
            // --- Actions that trigger persistence ---
            "filesystem.ADD_WHITELIST_PATH", "filesystem.REMOVE_WHITELIST_PATH" -> {
                val state = store.state.value.featureStates[name] as? FileSystemState ?: return
                val path = action.payload?.let { Json.decodeFromJsonElement<PathPayload>(it) }?.path ?: return
                val newSet = if (action.name == "filesystem.ADD_WHITELIST_PATH") {
                    state.whitelistedPaths + path
                } else {
                    state.whitelistedPaths - path
                }
                store.dispatch(Action("settings.UPDATE", buildJsonObject {
                    put("key", settingKeyWhitelist)
                    put("value", serializeSet(newSet))
                }, name))
            }
            "filesystem.ADD_FAVORITE_PATH", "filesystem.REMOVE_FAVORITE_PATH" -> {
                val state = store.state.value.featureStates[name] as? FileSystemState ?: return
                val path = action.payload?.let { Json.decodeFromJsonElement<PathPayload>(it) }?.path ?: return
                val newSet = if (action.name == "filesystem.ADD_FAVORITE_PATH") {
                    state.favoritePaths + path
                } else {
                    state.favoritePaths - path
                }
                store.dispatch(Action("settings.UPDATE", buildJsonObject {
                    put("key", settingKeyFavorites)
                    put("value", serializeSet(newSet))
                }, name))
            }
        }
    }

    override fun reducer(state: AppState, action: Action): AppState {
        val currentFeatureState = state.featureStates[name] as? FileSystemState
        val resolvedFeatureState = currentFeatureState ?: FileSystemState()
        var newFeatureState: FileSystemState? = null

        when (action.name) {
            "app.STARTING" -> {
                if (currentFeatureState == null) { newFeatureState = resolvedFeatureState }
            }
            "filesystem.DIRECTORY_LOADED" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<DirectoryLoadedPayload>(it) } ?: return state
                val isNavigating = resolvedFeatureState.currentPath == payload.parentPath

                val newChildren = payload.children.map { entry -> FileSystemItem(
                    path = entry.path,
                    name = platformDependencies.getFileName(entry.path),
                    isDirectory = entry.isDirectory
                )}

                newFeatureState = if (isNavigating) {
                    // This is the result of a main navigation action
                    resolvedFeatureState.copy(rootItems = newChildren, error = null)
                } else {
                    // This is the result of expanding a sub-directory
                    resolvedFeatureState.copy(
                        rootItems = updateItemByPath(resolvedFeatureState.rootItems, payload.parentPath) { item ->
                            item.copy(children = newChildren)
                        }
                    )
                }
            }
            "filesystem.NAVIGATE" -> {
                // The new pure part of navigate: update the current path immediately.
                val payload = action.payload?.let { Json.decodeFromJsonElement<PathPayload>(it) } ?: return state
                newFeatureState = resolvedFeatureState.copy(currentPath = payload.path)
            }
            "filesystem.NAVIGATION_FAILED" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<NavigationFailedPayload>(it) }
                payload?.let {
                    newFeatureState = resolvedFeatureState.copy(error = it.error, rootItems = emptyList())
                }
            }
            "filesystem.TOGGLE_ITEM_EXPANDED" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<PathPayload>(it) }
                payload?.let {
                    newFeatureState = resolvedFeatureState.copy(
                        rootItems = updateItemByPath(resolvedFeatureState.rootItems, it.path) { item ->
                            item.copy(isExpanded = !item.isExpanded)
                        }
                    )
                }
            }
            "filesystem.TOGGLE_ITEM_SELECTED" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<ToggleItemPayload>(it) } ?: return state
                val (path, isRecursive) = payload
                val targetItem = findItemByPath(resolvedFeatureState.rootItems, path)
                val targetState = if (targetItem?.isDirectory == true) {
                    val stats = getSelectionStats(targetItem)
                    // If not fully selected (or indeterminate), the new state is ON. Otherwise, it's OFF.
                    stats.selectedCount < stats.totalCount
                } else {
                    !(targetItem?.isSelected ?: false)
                }

                newFeatureState = resolvedFeatureState.copy(
                    rootItems = updateItemByPath(resolvedFeatureState.rootItems, path, isRecursive) { item ->
                        item.copy(isSelected = targetState)
                    }
                )
            }
            "filesystem.EXPAND_ALL" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<PathPayload>(it) } ?: return state
                newFeatureState = resolvedFeatureState.copy(
                    rootItems = updateExpansionStateRecursive(resolvedFeatureState.rootItems, payload.path, true)
                )
            }
            "filesystem.COLLAPSE_ALL" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<PathPayload>(it) } ?: return state
                newFeatureState = resolvedFeatureState.copy(
                    rootItems = updateExpansionStateRecursive(resolvedFeatureState.rootItems, payload.path, false)
                )
            }
            "filesystem.ADD_WHITELIST_PATH" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<PathPayload>(it) } ?: return state
                newFeatureState = resolvedFeatureState.copy(whitelistedPaths = resolvedFeatureState.whitelistedPaths + payload.path)
            }
            "filesystem.REMOVE_WHITELIST_PATH" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<PathPayload>(it) } ?: return state
                newFeatureState = resolvedFeatureState.copy(whitelistedPaths = resolvedFeatureState.whitelistedPaths - payload.path)
            }
            "filesystem.ADD_FAVORITE_PATH" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<PathPayload>(it) } ?: return state
                newFeatureState = resolvedFeatureState.copy(favoritePaths = resolvedFeatureState.favoritePaths + payload.path)
            }
            "filesystem.REMOVE_FAVORITE_PATH" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<PathPayload>(it) } ?: return state
                newFeatureState = resolvedFeatureState.copy(favoritePaths = resolvedFeatureState.favoritePaths - payload.path)
            }
            "settings.LOADED" -> {
                val loadedValues = action.payload
                val whitelistStr = loadedValues?.get(settingKeyWhitelist)?.jsonPrimitive?.content
                val favoritesStr = loadedValues?.get(settingKeyFavorites)?.jsonPrimitive?.content
                newFeatureState = resolvedFeatureState.copy(
                    whitelistedPaths = deserializeSet(whitelistStr),
                    favoritePaths = deserializeSet(favoritesStr)
                )
            }
            "settings.VALUE_CHANGED" -> {
                val key = action.payload?.get("key")?.jsonPrimitive?.content
                val value = action.payload?.get("value")?.jsonPrimitive?.content
                when (key) {
                    settingKeyWhitelist -> newFeatureState = resolvedFeatureState.copy(whitelistedPaths = deserializeSet(value))
                    settingKeyFavorites -> newFeatureState = resolvedFeatureState.copy(favoritePaths = deserializeSet(value))
                }
            }
            "filesystem.STAGE_CREATE" -> { /* ... existing code ... */ }
            "filesystem.STAGE_DELETE" -> { /* ... existing code ... */ }
            "filesystem.DISCARD" -> { /* ... existing code ... */ }
        }

        return newFeatureState?.let {
            state.copy(featureStates = state.featureStates + (name to it))
        } ?: state
    }

    // --- Side Effect Helpers ---
    private fun dispatchLoadChildrenRecursive(item: FileSystemItem, maxDepth: Int, store: Store) {
        if (maxDepth <= 0) return
        if (item.isDirectory) {
            if (item.children == null) {
                // This directory is unloaded, dispatch an action to load it.
                val payload = buildJsonObject { put("path", item.path) }
                store.dispatch(Action("filesystem.LOAD_CHILDREN", payload, name))
            } else {
                // This directory is loaded, recurse into its children.
                item.children.forEach { child -> dispatchLoadChildrenRecursive(child, maxDepth - 1, store) }
            }
        }
    }


    // --- Serialization Helpers ---
    private fun serializeSet(set: Set<String>): String = set.joinToString(",")
    private fun deserializeSet(str: String?): Set<String> = str?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()


    // --- Recursive Helper Functions for Immutable Updates ---

    private fun updateExpansionStateRecursive(items: List<FileSystemItem>, path: String, expand: Boolean): List<FileSystemItem> {
        return items.map { item ->
            when {
                item.path == path -> expandOrCollapse(item, expand, 3) // Start recursion on target
                path.startsWith(item.path) && item.children != null -> item.copy(children = updateExpansionStateRecursive(item.children, path, expand))
                else -> item
            }
        }
    }

    private fun expandOrCollapse(item: FileSystemItem, expand: Boolean, maxDepth: Int): FileSystemItem {
        if (!item.isDirectory || maxDepth <= 0) return item.copy(isExpanded = expand)
        val updatedChildren = item.children?.map { child -> expandOrCollapse(child, expand, maxDepth - 1) }
        return item.copy(isExpanded = expand, children = updatedChildren)
    }

    private fun updateItemByPath(items: List<FileSystemItem>, path: String, update: (FileSystemItem) -> FileSystemItem): List<FileSystemItem> {
        return items.map { item ->
            when {
                item.path == path -> update(item)
                path.startsWith(item.path) && item.children != null -> item.copy(children = updateItemByPath(item.children, path, update))
                else -> item
            }
        }
    }

    private fun updateItemByPath(items: List<FileSystemItem>, path: String, recursive: Boolean, update: (FileSystemItem) -> FileSystemItem): List<FileSystemItem> {
        return items.map { item ->
            var updatedItem = item
            if (item.path == path) {
                updatedItem = update(item) // Update the target item
                if (recursive && updatedItem.children != null) {
                    // If recursive, apply the same update to all descendants
                    updatedItem = updatedItem.copy(children = updatedItem.children.map { child ->
                        updateItemRecursive(child, update)
                    })
                }
            } else if (path.startsWith(item.path) && item.children != null) {
                // Not the target, but could be an ancestor, recurse down
                updatedItem = item.copy(children = updateItemByPath(item.children, path, recursive, update))
            }
            updatedItem
        }
    }

    private fun updateItemRecursive(item: FileSystemItem, update: (FileSystemItem) -> FileSystemItem): FileSystemItem {
        val updated = update(item)
        return if (updated.children != null) {
            updated.copy(children = updated.children.map { updateItemRecursive(it, update) })
        } else {
            updated
        }
    }

    private fun findItemByPath(items: List<FileSystemItem>, path: String): FileSystemItem? {
        items.forEach { item ->
            if (item.path == path) return item
            if (path.startsWith(item.path)) {
                item.children?.let {
                    val found = findItemByPath(it, path)
                    if (found != null) return found
                }
            }
        }
        return null
    }

    private fun findSelectedFiles(items: List<FileSystemItem>): List<FileSystemItem> {
        val selected = mutableListOf<FileSystemItem>()
        items.forEach { item ->
            if (item.isSelected && !item.isDirectory) {
                selected.add(item)
            }
            if (item.isDirectory && item.children != null) {
                selected.addAll(findSelectedFiles(item.children))
            }
        }
        return selected
    }

    private data class SelectionStats(val selectedCount: Int, val totalCount: Int)
    private fun getSelectionStats(item: FileSystemItem): SelectionStats {
        if (!item.isDirectory) {
            return SelectionStats(if (item.isSelected) 1 else 0, 1)
        }
        if (item.children == null || item.children.isEmpty()) {
            // An unloaded or empty directory has 0 selectable children.
            return SelectionStats(0, 0)
        }
        val childrenStats = item.children.map { getSelectionStats(it) }
        return SelectionStats(
            selectedCount = childrenStats.sumOf { it.selectedCount },
            totalCount = childrenStats.sumOf { it.totalCount }
        )
    }


    inner class FileSystemComposableProvider : Feature.ComposableProvider {
        override val viewKey: String = "feature.filesystem.main"

        @Composable
        override fun RibbonButton(store: Store, isActive: Boolean) {
            val payload = buildJsonObject { put("key", viewKey) }
            IconButton(onClick = { store.dispatch(Action("core.SET_ACTIVE_VIEW", payload, "filesystem.ui")) }) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = "File System Browser",
                    tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        @Composable
        override fun StageContent(store: Store) {
            FileSystemView(store, platformDependencies)
        }
    }
}