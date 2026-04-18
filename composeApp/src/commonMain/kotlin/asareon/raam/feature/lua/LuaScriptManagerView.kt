package asareon.raam.feature.lua

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import asareon.raam.core.Action
import asareon.raam.core.Feature
import asareon.raam.core.Store
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.ui.components.CodeEditor
import asareon.raam.ui.components.SyntaxMode
import asareon.raam.ui.components.destructive.ConfirmDestructiveDialog
import asareon.raam.ui.components.destructive.DangerDropdownMenuItem
import asareon.raam.ui.components.footer.FooterActionEmphasis
import asareon.raam.ui.components.footer.FooterButton
import asareon.raam.ui.components.footer.ViewFooter
import asareon.raam.ui.components.identity.IdentityDraft
import asareon.raam.ui.components.identity.IdentityFieldsSection
import asareon.raam.ui.components.identity.toDraft
import asareon.raam.ui.components.sidepane.SidePane
import asareon.raam.ui.components.sidepane.SidePaneLayout
import asareon.raam.ui.components.sidepane.SidePanePosition
import asareon.raam.ui.components.sidepane.rememberSidePaneState
import asareon.raam.ui.components.topbar.HeaderAction
import asareon.raam.ui.components.topbar.HeaderActionEmphasis
import asareon.raam.ui.components.topbar.HeaderLeading
import asareon.raam.ui.components.topbar.RaamTopBarHeader
import asareon.raam.ui.theme.spacing
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Full CRUD Script Manager view matching the app's visual language.
 */
@Composable
fun LuaScriptManagerView(store: Store, features: List<Feature>) {
    val appState by store.state.collectAsState()
    val luaState = appState.featureStates["lua"] as? LuaState

    if (luaState == null || !luaState.runtimeAvailable) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Lua scripting is not available on this platform.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    var selectedScript by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }
    var editTarget by remember { mutableStateOf<ScriptEditTarget?>(null) }

    // Auto-select first script if selection is stale
    val scripts = luaState.scripts.values.toList()
    if (selectedScript != null && selectedScript !in luaState.scripts) {
        selectedScript = scripts.firstOrNull()?.handle
    }

    if (editTarget != null) {
        ScriptEditorView(
            store = store,
            target = editTarget!!,
            luaState = luaState,
            onClose = { editTarget = null },
        )
        return
    }

    val paneState = rememberSidePaneState()

    Column(modifier = Modifier.fillMaxSize()) {
        RaamTopBarHeader(
            title = "Lua Scripts",
            leading = HeaderLeading.Back(onClick = {
                store.dispatch("core", Action(ActionRegistry.Names.CORE_SHOW_DEFAULT_VIEW))
            }),
            actions = listOf(
                HeaderAction(
                    id = "create-script",
                    label = "Create Script",
                    icon = Icons.Default.Add,
                    priority = 30,
                    emphasis = HeaderActionEmphasis.Create,
                    onClick = { editTarget = ScriptEditTarget.Create },
                ),
                HeaderAction(
                    id = "open-workspace-folder",
                    label = "Open workspace folder",
                    icon = Icons.Default.FolderOpen,
                    priority = 20,
                    onClick = {
                        store.dispatch(
                            "lua",
                            Action(
                                name = ActionRegistry.Names.FILESYSTEM_OPEN_WORKSPACE_FOLDER,
                                payload = buildJsonObject { put("path", "") },
                            ),
                        )
                    },
                ),
            ),
            subContent = {
                Text(
                    text = "Automate actions with Lua scripts. Each script runs sandboxed with its own identity. " +
                        "Per-script permissions are managed in the Permission Manager.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = MaterialTheme.spacing.screenEdge,
                        vertical = MaterialTheme.spacing.inner,
                    ),
                )
            },
        )

        // ══════════════════════════════════════════════════════════════════
        // MAIN CONTENT: Scripts list pane + detail primary
        // ══════════════════════════════════════════════════════════════════
        SidePaneLayout(
            modifier = Modifier.weight(1f),
            paneState = paneState,
            panePosition = SidePanePosition.Start,
            sidePane = {
                SidePane(title = "Scripts") {
                    LazyColumn(
                        modifier = Modifier.weight(1f).padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (scripts.isEmpty()) {
                            item {
                                Text(
                                    "No scripts yet.\nClick \"Create Script\" to get started.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        } else {
                            items(scripts, key = { it.handle }) { script ->
                                ScriptListRow(
                                    script = script,
                                    isSelected = selectedScript == script.handle,
                                    onSelect = {
                                        selectedScript = script.handle
                                    },
                                    onToggle = {
                                        store.dispatch("lua", Action(
                                            name = ActionRegistry.Names.LUA_TOGGLE_SCRIPT,
                                            payload = buildJsonObject { put("scriptHandle", script.handle) }
                                        ))
                                    }
                                )
                            }
                        }
                    }
                }
            },
            primary = {
                val selected = selectedScript?.let { luaState.scripts[it] }
                if (selected != null) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Detail header with actions. Edit opens the full-view editor;
                        // no inline editing in the detail pane anymore.
                        ScriptDetailHeader(
                            script = selected,
                            onEdit = {
                                editTarget = ScriptEditTarget.Edit(selected.handle)
                            },
                            onClone = {
                                store.dispatch("lua", Action(
                                    name = ActionRegistry.Names.LUA_CLONE_SCRIPT,
                                    payload = buildJsonObject { put("scriptHandle", selected.handle) }
                                ))
                            },
                            onDelete = { showDeleteDialog = selected.handle }
                        )

                    HorizontalDivider()

                    // Info + console
                    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                            // Status info
                            ScriptInfoSection(selected, luaState)

                            Spacer(Modifier.height(12.dp))

                            // Console output
                            Text(
                                "Console Output",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))

                            val console = luaState.consoleBuffers[selected.handle] ?: emptyList()
                            val consoleListState = rememberLazyListState()

                            // Auto-scroll to bottom when new entries arrive,
                            // but only if user is already near the bottom.
                            LaunchedEffect(console.size) {
                                if (console.isNotEmpty()) {
                                    val lastVisible = consoleListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                                    val isNearBottom = lastVisible >= console.size - 3
                                    if (isNearBottom) {
                                        consoleListState.animateScrollToItem(console.size - 1)
                                    }
                                }
                            }

                            SelectionContainer {
                                LazyColumn(
                                    state = consoleListState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .background(
                                            MaterialTheme.colorScheme.surfaceContainerLowest,
                                            MaterialTheme.shapes.small
                                        )
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                                        .padding(8.dp)
                                ) {
                                    if (console.isEmpty()) {
                                        item {
                                            Text(
                                                "No output yet.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    } else {
                                        items(console) { entry ->
                                            val prefix = when (entry.level) {
                                                "error" -> "[ERR] "
                                                "warn" -> "[!] "
                                                else -> ""
                                            }
                                            val textColor = if (entry.color != null) {
                                                try {
                                                    val hex = entry.color.removePrefix("#")
                                                    val rgb = hex.toLong(16)
                                                    Color(
                                                        red = ((rgb shr 16) and 0xFF).toInt() / 255f,
                                                        green = ((rgb shr 8) and 0xFF).toInt() / 255f,
                                                        blue = (rgb and 0xFF).toInt() / 255f
                                                    )
                                                } catch (_: Exception) { null }
                                            } else null

                                            Text(
                                                text = prefix + entry.message,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp,
                                                lineHeight = 14.sp,
                                                color = textColor ?: when (entry.level) {
                                                    "error" -> MaterialTheme.colorScheme.error
                                                    "warn" -> MaterialTheme.colorScheme.tertiary
                                                    else -> MaterialTheme.colorScheme.onSurface
                                                },
                                                fontWeight = if (entry.bold == true) FontWeight.Bold else null,
                                                fontStyle = if (entry.italic == true) FontStyle.Italic else null,
                                                modifier = Modifier.padding(vertical = 0.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
            } else {
                // No selection
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Select a script to view details.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        )

        // ══════════════════════════════════════════════════════════════════
        // FOOTER: Summary line
        // ══════════════════════════════════════════════════════════════════
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
                val running = scripts.count { it.status == ScriptStatus.RUNNING }
                val errored = scripts.count { it.status == ScriptStatus.ERRORED }
                val off = scripts.count { it.status == ScriptStatus.STOPPED }
                Text(
                    "${scripts.size} scripts available",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (running > 0) Text(
                    "$running running",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (errored > 0) Text(
                    "$errored error",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
                if (off > 0) Text(
                    "$off off",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // DIALOGS
    // ══════════════════════════════════════════════════════════════════════

    if (showDeleteDialog != null) {
        val handle = showDeleteDialog!!
        ConfirmDestructiveDialog(
            title = "Delete Script?",
            message = "This will permanently remove the script file from disk. This action cannot be undone.",
            onConfirm = {
                store.dispatch(
                    "lua",
                    Action(
                        name = ActionRegistry.Names.LUA_DELETE_SCRIPT,
                        payload = buildJsonObject { put("scriptHandle", handle) },
                    ),
                )
                if (selectedScript == handle) selectedScript = null
                showDeleteDialog = null
            },
            onDismiss = { showDeleteDialog = null },
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// Script List Row
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun ScriptListRow(
    script: ScriptInfo,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onToggle: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onSelect,
        shape = MaterialTheme.shapes.small,
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        tonalElevation = if (isSelected) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Active toggle checkbox
            Checkbox(
                checked = script.status == ScriptStatus.RUNNING || script.status == ScriptStatus.LOADING,
                onCheckedChange = { onToggle() },
                modifier = Modifier.size(32.dp)
            )

            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    script.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }

            // Status badge
            StatusBadge(script.status)
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// Script Detail Header
// ══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScriptDetailHeader(
    script: ScriptInfo,
    onEdit: () -> Unit,
    onClone: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                script.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                script.handle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            // Edit opens the full-view script editor (IdentityFieldsSection + CodeEditor).
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit script")
            }

            // Kebab: Clone, divider, Delete
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Clone Script") },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                        onClick = {
                            menuExpanded = false
                            onClone()
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    DangerDropdownMenuItem(
                        label = "Delete Script",
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// Script Info Section
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun ScriptInfoSection(script: ScriptInfo, luaState: LuaState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Status:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            StatusBadge(script.status)
        }
        if (script.lastError != null) {
            Spacer(Modifier.height(4.dp))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    script.lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
        if (script.sourceContent == null) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Source content not available — toggle the script on to load it from disk.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = FontStyle.Italic
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// Status Badge
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun StatusBadge(status: ScriptStatus) {
    val (color, label) = when (status) {
        ScriptStatus.RUNNING -> MaterialTheme.colorScheme.primary to "running"
        ScriptStatus.ERRORED -> MaterialTheme.colorScheme.error to "error"
        ScriptStatus.LOADING -> MaterialTheme.colorScheme.tertiary to "loading"
        ScriptStatus.STOPPED -> MaterialTheme.colorScheme.outline to "off"
    }
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// Full-view Script Editor — create or edit a Lua script's identity + body
// ══════════════════════════════════════════════════════════════════════════════

/** Which script the full-view editor is targeting. */
private sealed interface ScriptEditTarget {
    data object Create : ScriptEditTarget
    data class Edit(val handle: String) : ScriptEditTarget
}

/** Slug rule mirroring [LuaFeature.handleCreateScript]. */
private fun slugifyScriptName(name: String): String =
    name.lowercase().replace(Regex("[^a-z0-9-]"), "-")
        .replace(Regex("-+"), "-").trimStart('-').trimEnd('-').ifEmpty { "unnamed" }

@Composable
private fun ScriptEditorView(
    store: Store,
    target: ScriptEditTarget,
    luaState: LuaState,
    onClose: () -> Unit,
) {
    val appState by store.state.collectAsState()
    val existing = (target as? ScriptEditTarget.Edit)?.let { luaState.scripts[it.handle] }
    val existingIdentity = (target as? ScriptEditTarget.Edit)
        ?.let { appState.identityRegistry[it.handle] }

    val initialIdentity = remember(target) {
        when (target) {
            is ScriptEditTarget.Create -> IdentityDraft(name = "")
            is ScriptEditTarget.Edit -> existingIdentity?.toDraft()
                ?: IdentityDraft(name = existing?.name ?: "")
        }
    }
    var identityDraft by remember(target) { mutableStateOf(initialIdentity) }

    // Template rendered once on entry — for Create it's the app-script template
    // with the caller's current name/slug; for Edit it's the file's sourceContent.
    // We do not auto-regenerate the template as the user types the name; that
    // would clobber in-progress code edits.
    val initialCode = remember(target) {
        when (target) {
            is ScriptEditTarget.Create -> {
                val starterName = identityDraft.name.ifBlank { "New Script" }
                val starterLocalHandle = slugifyScriptName(starterName)
                LuaScriptTemplates.appScript(starterName, starterLocalHandle)
            }
            is ScriptEditTarget.Edit -> existing?.sourceContent ?: ""
        }
    }
    var code by remember(target) { mutableStateOf(initialCode) }

    var showDiscardDialog by remember { mutableStateOf(false) }
    val dirty = identityDraft != initialIdentity || code != initialCode
    val canSave = identityDraft.name.isNotBlank()

    val tryClose = { if (dirty) showDiscardDialog = true else onClose() }

    val onSave = {
        when (target) {
            is ScriptEditTarget.Create -> {
                store.dispatch("lua", Action(
                    name = ActionRegistry.Names.LUA_CREATE_SCRIPT,
                    payload = buildJsonObject {
                        put("name", identityDraft.name)
                        put("content", code)
                        if (identityDraft.displayColor != null) put("displayColor", identityDraft.displayColor)
                        if (identityDraft.displayIcon != null) put("displayIcon", identityDraft.displayIcon)
                        if (identityDraft.displayEmoji != null) put("displayEmoji", identityDraft.displayEmoji)
                    },
                ))
            }
            is ScriptEditTarget.Edit -> {
                // Save code separately from identity visuals. Name edits are
                // deliberately disabled in Edit mode — renaming a script would
                // require a file-rename side-effect chain not yet implemented.
                if (code != initialCode && !code.startsWith("-- <file content unavailable")) {
                    store.dispatch("lua", Action(
                        name = ActionRegistry.Names.LUA_SAVE_SCRIPT,
                        payload = buildJsonObject {
                            put("scriptHandle", target.handle)
                            put("content", code)
                        },
                    ))
                }
                val visualsChanged = identityDraft.displayColor != initialIdentity.displayColor ||
                    identityDraft.displayIcon != initialIdentity.displayIcon ||
                    identityDraft.displayEmoji != initialIdentity.displayEmoji
                if (visualsChanged) {
                    store.dispatch("core", Action(
                        name = ActionRegistry.Names.CORE_UPDATE_IDENTITY,
                        payload = buildJsonObject {
                            put("handle", target.handle)
                            // Pass the current name unchanged so core doesn't rescope the handle.
                            put("newName", identityDraft.name)
                            put("displayColor", identityDraft.displayColor)
                            put("displayIcon", identityDraft.displayIcon)
                            put("displayEmoji", identityDraft.displayEmoji)
                        },
                    ))
                }
            }
        }
        onClose()
    }

    Column(Modifier.fillMaxSize()) {
        RaamTopBarHeader(
            title = when (target) {
                is ScriptEditTarget.Create -> "New Script"
                is ScriptEditTarget.Edit -> existing?.name ?: "Edit Script"
            },
            subtitle = "Lua Scripts",
            leading = HeaderLeading.Back(onClick = { tryClose() }),
        )
        IdentityFieldsSection(
            draft = identityDraft,
            onDraftChange = { identityDraft = it },
            nameLabel = "Script Name",
            nameEditable = target is ScriptEditTarget.Create,
            modifier = Modifier.padding(
                horizontal = MaterialTheme.spacing.screenEdge,
                vertical = MaterialTheme.spacing.itemGap,
            ),
        )
        CodeEditor(
            value = code,
            onValueChange = { code = it },
            syntax = SyntaxMode.LUA,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.screenEdge),
            bordered = true,
        )
        ViewFooter {
            FooterButton(FooterActionEmphasis.Cancel, "Cancel", onClick = { tryClose() })
            FooterButton(
                emphasis = FooterActionEmphasis.Confirm,
                label = if (target is ScriptEditTarget.Create) "Create" else "Save",
                onClick = { onSave() },
                enabled = canSave,
            )
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard changes?") },
            text = { Text("Your unsaved edits will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onClose()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("Keep editing") }
            },
        )
    }
}
