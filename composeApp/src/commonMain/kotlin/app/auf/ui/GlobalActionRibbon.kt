package app.auf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.auf.core.StateManager
import app.auf.core.ViewMode
import aufapp.composeapp.generated.resources.Res
import aufapp.composeapp.generated.resources.icon
import org.jetbrains.compose.resources.painterResource

@Composable
fun GlobalActionRibbon(
    stateManager: StateManager
) {
    var isMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(48.dp)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(onClick = { stateManager.setViewMode(ViewMode.CHAT) }) {
            Icon(
                painter = painterResource(Res.drawable.icon),
                contentDescription = "Home (Chat View)",
                tint = Color.Unspecified
            )
        }
        HorizontalDivider()


        Box {
            IconButton(onClick = { isMenuExpanded = true }) {
                Icon(Icons.Default.Menu, contentDescription = "Application Menu")
            }
            DropdownMenu(
                expanded = isMenuExpanded,
                onDismissRequest = { isMenuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Export for AUF manual runtime") },
                    onClick = {
                        stateManager.setViewMode(ViewMode.EXPORT)
                        isMenuExpanded = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Import & Sync from manual runtime") },
                    onClick = {
                        stateManager.setViewMode(ViewMode.IMPORT)
                        isMenuExpanded = false
                    }
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Copy codebase to clipboard") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Code,
                            contentDescription = "Copy Codebase",
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = {
                        stateManager.copyCodebaseToClipboard()
                        isMenuExpanded = false
                    }
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Manage Backups") },
                    onClick = {
                        stateManager.openBackupFolder()
                        isMenuExpanded = false
                    }
                )
            }
        }
    }
}