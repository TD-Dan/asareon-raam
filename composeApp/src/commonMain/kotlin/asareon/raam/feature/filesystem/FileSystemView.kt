package asareon.raam.feature.filesystem

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import asareon.raam.core.*
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.ui.components.topbar.HeaderAction
import asareon.raam.ui.components.topbar.RaamTopBar
import asareon.raam.ui.components.topbar.TooltipIconButton
import asareon.raam.ui.theme.spacing
import asareon.raam.util.PlatformDependencies
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun FileSystemView(
    store: Store,
    platformDependencies: PlatformDependencies
) {
    val appState by store.state.collectAsState()
    val fsState = appState.featureStates["filesystem"] as? FileSystemState
    val parentPath = fsState?.currentPath?.let { platformDependencies.getParentDirectory(it) }
    var isFavoritesMenuExpanded by remember { mutableStateOf(false) }
    var isWhitelistMenuExpanded by remember { mutableStateOf(false) }

    val hasSelection = fsState?.rootItems?.any { findSelectedFiles(listOf(it)).isNotEmpty() } == true
    val headerActions = listOf(
        HeaderAction(
            id = "copy-selection",
            label = "Copy selection to clipboard",
            icon = Icons.Default.ContentCopy,
            priority = 30,
            enabled = hasSelection,
            onClick = {
                store.dispatch(
                    "filesystem",
                    Action(ActionRegistry.Names.FILESYSTEM_COPY_SELECTION_TO_CLIPBOARD)
                )
            },
        ),
        HeaderAction(
            id = "select-folder",
            label = "Select folder",
            icon = Icons.Default.FolderOpen,
            priority = 20,
            onClick = {
                store.dispatch(
                    "filesystem",
                    Action(ActionRegistry.Names.FILESYSTEM_SELECT_DIRECTORY_UI)
                )
            },
        ),
        HeaderAction(
            id = "go-up",
            label = "Go up one directory",
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            priority = 10,
            enabled = parentPath != null,
            onClick = {
                parentPath?.let {
                    store.dispatch(
                        "filesystem",
                        Action(
                            ActionRegistry.Names.FILESYSTEM_NAVIGATE,
                            buildJsonObject { put("path", it) },
                        ),
                    )
                }
            },
        ),
    )

    val navigateTo: (String) -> Unit = { path ->
        store.dispatch(
            "filesystem",
            Action(
                ActionRegistry.Names.FILESYSTEM_NAVIGATE,
                buildJsonObject { put("path", path) },
            ),
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        RaamTopBar(
            actions = headerActions,
            subContent = {
                Text(
                    text = fsState?.currentPath ?: "Loading...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = MaterialTheme.spacing.screenEdge,
                        vertical = MaterialTheme.spacing.inner,
                    ),
                )
            },
        ) {
            // Center slot: title + inline whitelist/favorites dropdowns.
            // Those two stay inline because their DropdownMenus anchor to the
            // IconButton — letting them spill into the responsive overflow kebab
            // would break the anchor.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Local File System Access",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                WhitelistDropdown(
                    expanded = isWhitelistMenuExpanded,
                    onExpandedChange = { isWhitelistMenuExpanded = it },
                    whitelistedPaths = fsState?.whitelistedPaths ?: emptySet(),
                    onNavigate = navigateTo,
                )
                FavoritesDropdown(
                    expanded = isFavoritesMenuExpanded,
                    onExpandedChange = { isFavoritesMenuExpanded = it },
                    favoritePaths = fsState?.favoritePaths ?: emptySet(),
                    onNavigate = navigateTo,
                )
            }
        }

        // --- Error Panel or File Tree ---
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(MaterialTheme.spacing.screenEdge)
        ) {
            fsState?.error?.let { ErrorPanel(it) }
                ?: if (fsState?.rootItems?.isEmpty() == true && fsState.currentPath != null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) { Text("Directory is empty.") }
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
}

@Composable
private fun WhitelistDropdown(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    whitelistedPaths: Set<String>,
    onNavigate: (String) -> Unit,
) {
    Box {
        TooltipIconButton(
            label = "Navigate to whitelisted folder",
            onClick = { onExpandedChange(true) },
            enabled = whitelistedPaths.isNotEmpty(),
        ) {
            Icon(Icons.Default.Edit, contentDescription = "Navigate to whitelisted folder")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            Text(
                "Whitelisted Folders",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
            )
            HorizontalDivider()
            whitelistedPaths.sorted().forEach { path ->
                DropdownMenuItem(
                    text = { Text(path) },
                    onClick = {
                        onNavigate(path)
                        onExpandedChange(false)
                    },
                )
            }
        }
    }
}

@Composable
private fun FavoritesDropdown(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    favoritePaths: Set<String>,
    onNavigate: (String) -> Unit,
) {
    Box {
        TooltipIconButton(
            label = "Navigate to favorite",
            onClick = { onExpandedChange(true) },
            enabled = favoritePaths.isNotEmpty(),
        ) {
            Icon(Icons.Default.Star, contentDescription = "Navigate to favorite")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            favoritePaths.sorted().forEach { path ->
                DropdownMenuItem(
                    text = { Text(path) },
                    onClick = {
                        onNavigate(path)
                        onExpandedChange(false)
                    },
                )
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
            onToggleExpand = { store.dispatch("filesystem", Action(ActionRegistry.Names.FILESYSTEM_TOGGLE_ITEM_EXPANDED, buildJsonObject { put("path", item.path) })) },
            onToggleSelect = {
                val payload = buildJsonObject {
                    put("path", item.path)
                    put("recursive", item.isDirectory)
                }
                store.dispatch("filesystem", Action(ActionRegistry.Names.FILESYSTEM_TOGGLE_ITEM_SELECTED, payload))
            },
            onNavigate = {
                if (item.isDirectory) {
                    store.dispatch("filesystem", Action(ActionRegistry.Names.FILESYSTEM_NAVIGATE, buildJsonObject { put("path", item.path) }))
                }
            },
            onContextMenuAction = { actionName ->
                store.dispatch("filesystem", Action(actionName, buildJsonObject { put("path", item.path) }))
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
    // Use ContextMenuArea for right-click support
    ContextMenuArea(
        items = {
            if (item.isDirectory) {
                listOf(
                    ContextMenuItem("Browse from here") { onNavigate() },
                    ContextMenuItem("Expand All") { onContextMenuAction(ActionRegistry.Names.FILESYSTEM_EXPAND_ALL) },
                    ContextMenuItem("Collapse All") { onContextMenuAction(ActionRegistry.Names.FILESYSTEM_COLLAPSE_ALL) },
                    // Divider is not standard, use custom items or separate menus
                    ContextMenuItem(if (fsState?.favoritePaths?.contains(item.path) == true) "Remove from Favorites" else "Add to Favorites") {
                        onContextMenuAction(if (fsState?.favoritePaths?.contains(item.path) == true) ActionRegistry.Names.FILESYSTEM_REMOVE_FAVORITE_PATH else ActionRegistry.Names.FILESYSTEM_ADD_FAVORITE_PATH)
                    },
                    ContextMenuItem(if (fsState?.whitelistedPaths?.contains(item.path) == true) "Remove from Whitelist" else "Add to Whitelist") {
                        onContextMenuAction(if (fsState?.whitelistedPaths?.contains(item.path) == true) ActionRegistry.Names.FILESYSTEM_REMOVE_WHITELIST_PATH else ActionRegistry.Names.FILESYSTEM_ADD_WHITELIST_PATH)
                    }
                )
            } else {
                emptyList()
            }
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleSelect)
                .padding(start = (level * 24).dp)
                .padding(vertical = 1.dp), // Halved vertical padding
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

            val expandIcon = if (item.isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight
            val icon = if (item.isDirectory) expandIcon else Icons.Default.Description

            Icon(
                imageVector = icon,
                contentDescription = if (item.isDirectory) "Expand/Collapse" else "File",
                modifier = Modifier.clickable(enabled = item.isDirectory, onClick = onToggleExpand),
                tint = if (item.isDirectory) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
            )

            val whitelistStatus = getWhitelistStatus(item.path, fsState?.whitelistedPaths ?: emptySet(), platformDependencies)
            val textColor = if (whitelistStatus != WhitelistStatus.NONE) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.outline // Darker grey for non-whitelisted items
            }

            // --- THE FIX: Use a Row for horizontal alignment ---
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor,
                )
                if (whitelistStatus != WhitelistStatus.NONE) {
                    val indicatorText = if (whitelistStatus == WhitelistStatus.ROOT) "Whitelisted - Filesystem editing allowed!" else "Editing allowed!"
                    Text(
                        text = "($indicatorText)",
                        style = MaterialTheme.typography.labelSmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
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
    }
}

private enum class WhitelistStatus { NONE, ROOT, DESCENDANT }
private fun getWhitelistStatus(path: String, whitelistedPaths: Set<String>, platform: PlatformDependencies): WhitelistStatus {
    if (whitelistedPaths.contains(path)) return WhitelistStatus.ROOT

    var current: String? = platform.getParentDirectory(path)
    while (current != null) {
        if (whitelistedPaths.contains(current)) {
            return WhitelistStatus.DESCENDANT
        }
        current = platform.getParentDirectory(current)
    }
    return WhitelistStatus.NONE
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