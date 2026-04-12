package asareon.raam.feature.lua

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import asareon.raam.core.Action
import asareon.raam.core.Feature
import asareon.raam.core.Store
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.ui.components.CodeEditor
import asareon.raam.ui.components.SyntaxMode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Full CRUD Script Manager view matching the app's visual language.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    var showCreateDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }
    var isEditing by remember { mutableStateOf(false) }
    var editBuffer by remember { mutableStateOf("") }

    // Auto-select first script if selection is stale
    val scripts = luaState.scripts.values.toList()
    if (selectedScript != null && selectedScript !in luaState.scripts) {
        selectedScript = scripts.firstOrNull()?.handle
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ══════════════════════════════════════════════════════════════════
        // HEADER (matches TopAppBar pattern from AgentManager / SessionManager)
        // ══════════════════════════════════════════════════════════════════
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "LUA Scripting",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = {
                        store.dispatch("lua", Action(
                            name = ActionRegistry.Names.FILESYSTEM_OPEN_WORKSPACE_FOLDER,
                            payload = buildJsonObject { put("path", "") }
                        ))
                    }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Open workspace folder")
                    }
                    FilledTonalButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("New Script")
                    }
                }
            }
            Text(
                "Automate actions with Lua scripts. Each script runs sandboxed with its own identity. " +
                        "Per-script permissions are managed in the Permission Manager.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider()

        // ══════════════════════════════════════════════════════════════════
        // MAIN CONTENT: Left list + Right detail
        // ══════════════════════════════════════════════════════════════════
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // ── Left Panel: Script List ──
            Column(
                modifier = Modifier
                    .width(280.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                LazyColumn(
                    modifier = Modifier.weight(1f).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (scripts.isEmpty()) {
                        item {
                            Text(
                                "No scripts yet.\nClick \"New Script\" to get started.",
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
                                    isEditing = false
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

            VerticalDivider()

            // ── Right Panel: Detail ──
            val selected = selectedScript?.let { luaState.scripts[it] }
            if (selected != null) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    // Detail header with actions
                    ScriptDetailHeader(
                        script = selected,
                        isEditing = isEditing,
                        onEdit = {
                            // Read current content into buffer (placeholder — in real impl would read from file)
                            editBuffer = "-- Script: ${selected.name}\n-- Edit content here\n"
                            isEditing = true
                        },
                        onSave = {
                            store.dispatch("lua", Action(
                                name = ActionRegistry.Names.LUA_SAVE_SCRIPT,
                                payload = buildJsonObject {
                                    put("scriptHandle", selected.handle)
                                    put("content", editBuffer)
                                }
                            ))
                            isEditing = false
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

                    // Script content area
                    if (isEditing) {
                        CodeEditor(
                            value = editBuffer,
                            onValueChange = { editBuffer = it },
                            syntax = SyntaxMode.LUA,
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                            bordered = false
                        )
                    } else {
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
                            LazyColumn(
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
                                        Text(
                                            text = "[${entry.level.uppercase()}] ${entry.message}",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            color = when (entry.level) {
                                                "error" -> MaterialTheme.colorScheme.error
                                                "warn" -> MaterialTheme.colorScheme.tertiary
                                                else -> MaterialTheme.colorScheme.onSurface
                                            },
                                            modifier = Modifier.padding(vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // No selection
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text(
                        "Select a script to view details.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

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

    if (showCreateDialog) {
        CreateScriptDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                store.dispatch("lua", Action(
                    name = ActionRegistry.Names.LUA_CREATE_SCRIPT,
                    payload = buildJsonObject { put("name", name) }
                ))
                showCreateDialog = false
            }
        )
    }

    if (showDeleteDialog != null) {
        val handle = showDeleteDialog!!
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete Script") },
            text = { Text("This will permanently remove the script file from disk. This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.dispatch("lua", Action(
                            name = ActionRegistry.Names.LUA_DELETE_SCRIPT,
                            payload = buildJsonObject { put("scriptHandle", handle) }
                        ))
                        if (selectedScript == handle) selectedScript = null
                        showDeleteDialog = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) { Text("Cancel") }
            }
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
    isEditing: Boolean,
    onEdit: () -> Unit,
    onSave: () -> Unit,
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

        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            // Edit / Save toggle
            if (isEditing) {
                FilledTonalButton(onClick = onSave) {
                    Icon(Icons.Default.Save, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Save")
                }
            } else {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit script")
                }
            }

            // Clone
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Clone script") } },
                state = rememberTooltipState()
            ) {
                IconButton(onClick = onClone) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Clone script")
                }
            }

            // Delete
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Delete script") } },
                state = rememberTooltipState()
            ) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete script",
                        tint = MaterialTheme.colorScheme.error
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
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("File:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(script.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Identity:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(script.handle, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
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
// Create Script Dialog
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun CreateScriptDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Lua Script") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Script name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name) },
                enabled = name.isNotBlank()
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
