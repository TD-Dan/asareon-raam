package app.auf.feature.filesystem

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.onClick
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import app.auf.core.Action
import app.auf.core.Store
import app.auf.util.PlatformDependencies
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun FileSystemView(
    store: Store,
    platformDependencies: PlatformDependencies
) {
    val appState by store.state.collectAsState()
    val fsState = appState.featureStates["FileSystemFeature"] as? FileSystemState
    val parentPath = fsState?.currentPath?.let { platformDependencies.getParentDirectory(it) }
    var isFavoritesMenuExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // --- Header ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("File System", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            // Favorites Dropdown
            Box {
                IconButton(
                    onClick = { isFavoritesMenuExpanded = true },
                    enabled = fsState?.favoritePaths?.isNotEmpty() == true
                ) {
                    Icon(Icons.Default.Star, contentDescription = "Navigate to Favorite")
                }
                DropdownMenu(
                    expanded = isFavoritesMenuExpanded,
                    onDismissRequest = { isFavoritesMenuExpanded = false }
                ) {
                    fsState?.favoritePaths?.sorted()?.forEach { path ->
                        DropdownMenuItem(
                            text = { Text(path) },
                            onClick = {
                                val payload = buildJsonObject { put("path", path) }
                                store.dispatch(Action("filesystem.NAVIGATE", payload, "filesystem.ui"))
                                isFavoritesMenuExpanded = false
                            }
                        )
                    }
                }
            }

            Button(
                onClick = { store.dispatch(Action("filesystem.COPY_SELECTION_TO_CLIPBOARD", null, "filesystem.ui")) },
                enabled = fsState?.rootItems?.any { findSelectedFiles(listOf(it)).isNotEmpty() } == true
            ) {
                Text("Copy selection")
            }
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { parentPath?.let { store.dispatch(Action("filesystem.NAVIGATE", buildJsonObject { put("path", it) }, "filesystem.ui")) } },
                enabled = parentPath != null
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Go Up")
            }
            IconButton(onClick = { store.dispatch(Action("filesystem.SELECT_DIRECTORY_UI", null, "filesystem.ui")) }) {
                Icon(Icons.Default.FolderOpen, contentDescription = "Select Folder")
            }
        }

        Text(
            text = fsState?.currentPath ?: "Loading...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        HorizontalDivider()

        // --- Error Panel or File Tree ---
        fsState?.error?.let { ErrorPanel(it) }
            ?: if (fsState?.rootItems?.isEmpty() == true && fsState.currentPath != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Directory is empty.") }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(fsState?.rootItems ?: emptyList(), key = { it.path }) { item ->
                        FileTree(
                            item = item,
                            store = store,
                            fsState = fsState,
                            platformDependencies = platformDependencies
                        )
                    }
                }
            }
    }
}

@Composable
private fun FileTree(
    item: FileSystemItem,
    store: Store,
    fsState: FileSystemState?,
    platformDependencies: PlatformDependencies,
    level: Int = 0
) {
    Column {
        FileRow(
            item = item,
            level = level,
            fsState = fsState,
            platformDependencies = platformDependencies,
            onToggleExpand = { store.dispatch(Action("filesystem.TOGGLE_ITEM_EXPANDED", buildJsonObject { put("path", item.path) }, "filesystem.ui")) },
            onToggleSelect = {
                val payload = buildJsonObject {
                    put("path", item.path)
                    put("recursive", item.isDirectory)
                }
                store.dispatch(Action("filesystem.TOGGLE_ITEM_SELECTED", payload, "filesystem.ui"))
            },
            onNavigate = {
                if (item.isDirectory) {
                    store.dispatch(Action("filesystem.NAVIGATE", buildJsonObject { put("path", item.path) }, "filesystem.ui"))
                }
            },
            onContextMenuAction = { actionName ->
                store.dispatch(Action(actionName, buildJsonObject { put("path", item.path) }, "filesystem.ui"))
            }
        )
        if (item.isExpanded && item.children != null) {
            item.children.forEach { child ->
                FileTree(
                    item = child,
                    store = store,
                    fsState = fsState,
                    platformDependencies = platformDependencies,
                    level = level + 1
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    item: FileSystemItem,
    level: Int,
    fsState: FileSystemState?,
    platformDependencies: PlatformDependencies,
    onToggleExpand: () -> Unit,
    onToggleSelect: () -> Unit,
    onNavigate: () -> Unit,
    onContextMenuAction: (String) -> Unit
) {
    var isContextMenuVisible by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = (level * 24).dp)
                .padding(vertical = 1.dp) // Halved vertical padding
                .onClick(
                    onClick = onNavigate,
                    onLongClick = { if (item.isDirectory) isContextMenuVisible = true }
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (item.isDirectory) {
                val selectionState = determineSelectionState(item)
                TriStateCheckbox(
                    state = selectionState,
                    onClick = onToggleSelect,
                    modifier = Modifier.testTag("checkbox-${item.path}")
                )
            } else {
                Checkbox(
                    checked = item.isSelected,
                    onCheckedChange = { onToggleSelect() },
                    modifier = Modifier.testTag("checkbox-${item.path}")
                )
            }

            val expandIcon = if (item.isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight
            val icon = if (item.isDirectory) expandIcon else Icons.Default.Description

            Icon(
                imageVector = icon,
                contentDescription = if (item.isDirectory) "Expand/Collapse" else "File",
                modifier = Modifier.clickable(enabled = item.isDirectory, onClick = onToggleExpand),
                tint = if (item.isDirectory) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
            )

            val isWhitelisted = fsState != null && isPathWhitelisted(item.path, fsState.whitelistedPaths, platformDependencies)
            val textColor = if (isWhitelisted) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.outline // Darker grey for non-whitelisted items
            }

            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                color = textColor,
            )

            Spacer(Modifier.weight(1f)) // Pushes subsequent items to the right

            if (item.isDirectory && isWhitelisted) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Whitelisted",
                    tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp)
                )
            }

            if (fsState?.favoritePaths?.contains(item.path) == true) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = "Favorite",
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        if (item.isDirectory) {
            DropdownMenu(
                expanded = isContextMenuVisible,
                onDismissRequest = { isContextMenuVisible = false }
            ) {
                DropdownMenuItem(text = { Text("Expand All") }, onClick = { onContextMenuAction("filesystem.EXPAND_ALL"); isContextMenuVisible = false })
                DropdownMenuItem(text = { Text("Collapse All") }, onClick = { onContextMenuAction("filesystem.COLLAPSE_ALL"); isContextMenuVisible = false })
                HorizontalDivider()
                if (fsState?.favoritePaths?.contains(item.path) == true) {
                    DropdownMenuItem(text = { Text("Remove from Favorites") }, onClick = { onContextMenuAction("filesystem.REMOVE_FAVORITE_PATH"); isContextMenuVisible = false })
                } else {
                    DropdownMenuItem(text = { Text("Add to Favorites") }, onClick = { onContextMenuAction("filesystem.ADD_FAVORITE_PATH"); isContextMenuVisible = false })
                }
                if (fsState?.whitelistedPaths?.contains(item.path) == true) {
                    DropdownMenuItem(text = { Text("Remove from Whitelist") }, onClick = { onContextMenuAction("filesystem.REMOVE_WHITELIST_PATH"); isContextMenuVisible = false })
                } else {
                    DropdownMenuItem(text = { Text("Add to Whitelist") }, onClick = { onContextMenuAction("filesystem.ADD_WHITELIST_PATH"); isContextMenuVisible = false })
                }
            }
        }
    }
}

private fun isPathWhitelisted(path: String, whitelistedPaths: Set<String>, platform: PlatformDependencies): Boolean {
    var current: String? = path
    while (current != null) {
        if (whitelistedPaths.contains(current)) {
            return true
        }
        current = platform.getParentDirectory(current)
    }
    return false
}

private fun determineSelectionState(item: FileSystemItem): ToggleableState {
    if (!item.isDirectory) return if (item.isSelected) ToggleableState.On else ToggleableState.Off

    val stats = getSelectionStats(item)
    return when {
        stats.selectedCount == 0 -> ToggleableState.Off
        stats.selectedCount == stats.totalCount && stats.totalCount > 0 -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
}

private data class SelectionStats(val selectedCount: Int, val totalCount: Int)
private fun getSelectionStats(item: FileSystemItem): SelectionStats {
    if (!item.isDirectory) {
        return SelectionStats(if (item.isSelected) 1 else 0, 1)
    }
    if (item.children == null || item.children.isEmpty()) {
        return SelectionStats(0, 0)
    }
    val childrenStats = item.children.map { getSelectionStats(it) }
    return SelectionStats(
        selectedCount = childrenStats.sumOf { it.selectedCount },
        totalCount = childrenStats.sumOf { it.totalCount }
    )
}

@Composable
private fun ErrorPanel(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Text(text = message, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(16.dp))
    }
}

private fun findSelectedFiles(items: List<FileSystemItem>): List<FileSystemItem> {
    val selected = mutableListOf<FileSystemItem>()
    items.forEach { item ->
        if (item.isSelected && !item.isDirectory) selected.add(item)
        item.children?.let { selected.addAll(findSelectedFiles(it)) }
    }
    return selected
}