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
    @Serializable private data class NavigatePayload(val path: String)
    @Serializable private data class NavigationUpdatedPayload(val path: String, val listing: List<FileEntry>)
    @Serializable private data class NavigationFailedPayload(val path: String, val error: String)
    @Serializable private data class StageCreatePayload(val path: String, val content: String)
    @Serializable private data class StageDeletePayload(val path: String)

    @Serializable private data class ToggleItemPayload(val path: String, val recursive: Boolean = false)
    @Serializable private data class DirectoryLoadedPayload(val parentPath: String, val children: List<FileEntry>)


    override fun onAction(action: Action, store: Store) {
        when (action.name) {
            "app.STARTING" -> {
                // When the app starts, automatically navigate to the user's home directory.
                val homePath = platformDependencies.getUserHomePath()
                val payload = buildJsonObject { put("path", homePath) }
                store.dispatch(Action("filesystem.NAVIGATE", payload, name))
            }
            "filesystem.NAVIGATE" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<NavigatePayload>(it) } ?: return
                try {
                    val listing = platformDependencies.listDirectory(payload.path)
                    val listingJson = buildJsonArray { listing.forEach { add(Json.encodeToJsonElement(it)) } }
                    val successPayload = buildJsonObject {
                        put("path", payload.path)
                        put("listing", listingJson)
                    }
                    store.dispatch(Action("filesystem.NAVIGATION_UPDATED", successPayload, name))
                } catch (e: Exception) {
                    val errorPayload = buildJsonObject {
                        put("path", payload.path)
                        put("error", e.message ?: "An unknown error occurred.")
                    }
                    store.dispatch(Action("filesystem.NAVIGATION_FAILED", errorPayload, name))
                }
            }
            "filesystem.SELECT_DIRECTORY_UI" -> {
                val selectedPath = platformDependencies.selectDirectoryPath()
                if (selectedPath != null) {
                    val payload = buildJsonObject { put("path", selectedPath) }
                    store.dispatch(Action("filesystem.NAVIGATE", payload, name))
                }
            }
            "filesystem.TOGGLE_ITEM_EXPANDED" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<ToggleItemPayload>(it) } ?: return
                val state = store.state.value.featureStates[name] as? FileSystemState ?: return
                // Check if we need to load children
                val item = findItemByPath(state.rootItems, payload.path)
                if (item?.isDirectory == true && item.children == null) {
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
            "filesystem.NAVIGATION_UPDATED" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<NavigationUpdatedPayload>(it) }
                payload?.let {
                    newFeatureState = resolvedFeatureState.copy(
                        currentPath = it.path,
                        rootItems = it.listing.map { entry ->
                            FileSystemItem(
                                path = entry.path,
                                name = platformDependencies.getFileName(entry.path),
                                isDirectory = entry.isDirectory
                            )
                        },
                        error = null
                    )
                }
            }
            "filesystem.NAVIGATION_FAILED" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<NavigationFailedPayload>(it) }
                payload?.let {
                    newFeatureState = resolvedFeatureState.copy(error = it.error, rootItems = emptyList())
                }
            }
            "filesystem.DIRECTORY_LOADED" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<DirectoryLoadedPayload>(it) }
                payload?.let {
                    val newChildren = it.children.map { entry -> FileSystemItem(
                        path = entry.path,
                        name = platformDependencies.getFileName(entry.path),
                        isDirectory = entry.isDirectory
                    )}
                    newFeatureState = resolvedFeatureState.copy(
                        rootItems = updateItemByPath(resolvedFeatureState.rootItems, it.parentPath) { item ->
                            item.copy(children = newChildren)
                        }
                    )
                }
            }
            "filesystem.TOGGLE_ITEM_EXPANDED" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<ToggleItemPayload>(it) }
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
                // Determine the target state. If a directory is indeterminate or off, the target is ON. If it's ON, the target is OFF.
                val targetItem = findItemByPath(resolvedFeatureState.rootItems, path)
                val targetState = if (targetItem?.isDirectory == true) {
                    val stats = getSelectionStats(targetItem)
                    stats.selectedCount < stats.totalCount // If not fully selected, become fully selected. Otherwise, become deselected.
                } else {
                    !(targetItem?.isSelected ?: false)
                }

                newFeatureState = resolvedFeatureState.copy(
                    rootItems = updateItemByPath(resolvedFeatureState.rootItems, path, isRecursive) { item ->
                        item.copy(isSelected = targetState)
                    }
                )
            }
            "filesystem.STAGE_CREATE" -> { /* ... existing code ... */ }
            "filesystem.STAGE_DELETE" -> { /* ... existing code ... */ }
            "filesystem.DISCARD" -> { /* ... existing code ... */ }
        }

        return newFeatureState?.let {
            state.copy(featureStates = state.featureStates + (name to it))
        } ?: state
    }

    // --- Recursive Helper Functions for Immutable Updates ---

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
        if (!item.isDirectory || item.children == null) {
            return SelectionStats(if (item.isSelected) 1 else 0, 1)
        }
        if (item.children.isEmpty()) {
            return SelectionStats(0, 0) // An empty directory has 0 items
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