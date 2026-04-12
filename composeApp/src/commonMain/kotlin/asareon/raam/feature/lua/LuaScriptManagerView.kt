package asareon.raam.feature.lua

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Script Manager stage view. Lists all loaded Lua scripts with their status,
 * provides load/unload/reload controls, and displays console output.
 */
@Composable
fun LuaScriptManagerView(store: Store, features: List<Feature>) {
    val appState by store.state.collectAsState()
    val luaState = appState.featureStates["lua"] as? LuaState

    if (luaState == null || !luaState.runtimeAvailable) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Lua scripting is not available on this platform.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    var selectedScript by remember { mutableStateOf<String?>(null) }

    Row(modifier = Modifier.fillMaxSize()) {
        // Left panel: Script list
        Column(
            modifier = Modifier
                .width(300.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Lua Scripts",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${luaState.scripts.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))

            if (luaState.scripts.isEmpty()) {
                Text(
                    "No scripts loaded.\nPlace .lua files in the lua/ workspace directory and load them here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(luaState.scripts.values.toList()) { script ->
                        ScriptListItem(
                            script = script,
                            isSelected = selectedScript == script.handle,
                            onSelect = { selectedScript = script.handle },
                            onUnload = {
                                store.dispatch("lua", Action(
                                    name = ActionRegistry.Names.LUA_UNLOAD_SCRIPT,
                                    payload = buildJsonObject { put("scriptHandle", script.handle) }
                                ))
                            },
                            onReload = {
                                store.dispatch("lua", Action(
                                    name = ActionRegistry.Names.LUA_RELOAD_SCRIPT,
                                    payload = buildJsonObject { put("scriptHandle", script.handle) }
                                ))
                            }
                        )
                    }
                }
            }
        }

        // Right panel: Console output for selected script
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(12.dp)
        ) {
            if (selectedScript != null) {
                val script = luaState.scripts[selectedScript]
                val console = luaState.consoleBuffers[selectedScript] ?: emptyList()

                if (script != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            script.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        StatusChip(script.status)
                    }

                    Text(
                        script.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (script.lastError != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            script.lastError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Console",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))

                    // Console output
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
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Select a script to view its console output.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ScriptListItem(
    script: ScriptInfo,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onUnload: () -> Unit,
    onReload: () -> Unit
) {
    Surface(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        shape = MaterialTheme.shapes.small,
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        tonalElevation = if (isSelected) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    script.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    script.handle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            StatusChip(script.status)

            IconButton(onClick = onReload, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Reload",
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(onClick = onUnload, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Unload",
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun StatusChip(status: ScriptStatus) {
    val (color, label) = when (status) {
        ScriptStatus.RUNNING -> MaterialTheme.colorScheme.primary to "Running"
        ScriptStatus.ERRORED -> MaterialTheme.colorScheme.error to "Error"
        ScriptStatus.LOADING -> MaterialTheme.colorScheme.tertiary to "Loading"
        ScriptStatus.STOPPED -> MaterialTheme.colorScheme.outline to "Stopped"
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}
