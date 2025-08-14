package app.auf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A Composable screen that provides a user interface for the import workbench.
 *
 * ---
 * ## Mandate
 * This view's responsibility is to render the state of an ongoing import operation, as
 * defined by the `ImportState` data class. It displays a list of files to be imported,
 * shows the automatically proposed action for each, and allows the user to override those
 * actions. It then provides the final user-approved import plan to the `StateManager`
 * for execution. It is a "dumb" view that only reflects state and forwards user events.
 *
 * ---
 * ## Dependencies
 * - `app.auf.AppState` (specifically `ImportState` and `ImportItem`)
 * - `app.auf.StateManager` (for dispatching user events like `SelectImportAction` or `ExecuteImport`)
 *
 * @version 1.1
 * @since 2025-08-14
 */
@Composable
fun ImportView(
    importState: ImportState,
    stateManager: StateManager,
    onClose: () -> Unit
) {
    var sourcePath by remember { mutableStateOf(importState.sourcePath) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Import Workbench", style = MaterialTheme.typography.h4)
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = sourcePath,
            onValueChange = { sourcePath = it },
            label = { Text("Source Folder Path") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { stateManager.analyzeImportFolder(sourcePath) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Analyze Folder")
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (importState.items.isNotEmpty()) {
            Box(modifier = Modifier.weight(1f).border(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.12f))) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    // Header Row
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colors.surface).padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("File", modifier = Modifier.weight(1f), style = MaterialTheme.typography.subtitle2)
                            Text("Proposed Action", modifier = Modifier.weight(1f), style = MaterialTheme.typography.subtitle2)
                        }
                        Divider()
                    }
                    // Data Rows
                    items(importState.items) { item ->
                        ImportItemRow(
                            item = item,
                            selectedAction = importState.selectedActions[item.sourcePath] ?: item.initialAction,
                            onActionSelected = { action ->
                                stateManager.selectImportAction(item.sourcePath, action)
                            }
                        )
                        Divider()
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(onClick = onClose) {
                    Text("Cancel")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { stateManager.executeImport() }) {
                    Text("Execute Import")
                }
            }
        } else {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text("No importable files found. Please analyze a folder.")
            }
        }
    }
}

@Composable
fun ImportItemRow(
    item: ImportItem,
    selectedAction: ImportAction,
    onActionSelected: (ImportAction) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    // --- FIX APPLIED ---
    // The unresolved reference 'sourceFile' is fixed here. We now derive the
    // filename from the platform-agnostic 'sourcePath' string. This is a robust
    // way to handle paths from different OSes.
    val fileName = item.sourcePath.substringAfterLast('/').substringAfterLast('\\')


    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Column for File Name
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(fileName, style = MaterialTheme.typography.body1)
        }

        // Column for Dropdown Action Selector
        Column(modifier = Modifier.weight(1f)) {
            Box {
                OutlinedTextField(
                    value = selectedAction.summary,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth().clickable { expanded = true },
                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, "Dropdown") }
                )

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    val possibleActions = when (item.initialAction) {
                        is AssignParent -> listOf(selectedAction, Ignore()) // Can only assign a parent or ignore
                        else -> listOf(item.initialAction, Ignore())
                    }
                    // For now, the user can only choose between the proposed action and ignoring it.
                    // A future version could allow changing the type of action.
                    DropdownMenuItem(onClick = {
                        onActionSelected(item.initialAction)
                        expanded = false
                    }) {
                        Text(item.initialAction.summary)
                    }
                    DropdownMenuItem(onClick = {
                        onActionSelected(Ignore())
                        expanded = false
                    }) {
                        Text("Ignore")
                    }
                }
            }
        }
    }
}