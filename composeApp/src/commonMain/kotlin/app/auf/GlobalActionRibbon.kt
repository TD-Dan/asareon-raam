package app.auf

import androidx.compose.foundation.layout.*
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun GlobalActionRibbon(
    stateManager: StateManager
) {
    var isMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxHeight().width(48.dp).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box {
            IconButton(onClick = { isMenuExpanded = true }) {
                Icon(Icons.Default.Menu, contentDescription = "Application Menu")
            }
            DropdownMenu(
                expanded = isMenuExpanded,
                onDismissRequest = { isMenuExpanded = false }
            ) {
                DropdownMenuItem(onClick = {
                    stateManager.setViewMode(ViewMode.EXPORT)
                    isMenuExpanded = false
                }) {
                    Text("Export for AUF manual runtime")
                }
                // --- MODIFIED: Enabled and implemented ---
                DropdownMenuItem(onClick = {
                    stateManager.setViewMode(ViewMode.IMPORT)
                    isMenuExpanded = false
                }) {
                    Text("Import & Sync from manual runtime")
                }
                Divider()
                DropdownMenuItem(onClick = {
                    stateManager.openBackupFolder()
                    isMenuExpanded = false
                }) {
                    Text("Manage Backups")
                }
            }
        }
    }
}