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
 * This view is a "dumb component". Its only responsibility is to render the `ImportState`
 * and emit events (via lambdas) when the user interacts with it. It is fully decoupled
 * from the application's business logic and does not know about StateManager or any ViewModel.
 * This makes it highly reusable and easy to test.
 *
 * ---
 * ## Dependencies
 * - `app.auf.ImportState`
 * - `app.auf.ImportItem`
 *
 * @version 1.2
 * @since 2025-08-14
 */
@Composable
fun ImportView(
    importState: ImportState,
    onClose: () -> Unit,
    onAnalyze: (path: String) -> Unit,
    onActionSelected: (itemPath: String, action: ImportAction) -> Unit,
    onExecute: () -> Unit
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
        // --- FIX APPLIED: Emits an event instead of calling a manager ---
        Button(
            onClick = { onAnalyze(sourcePath) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Analyze Folder")
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (importState.items.isNotEmpty()) {
            Box(modifier = Modifier.weight(1f).border(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.12f))) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
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
                    items(importState.items) { item ->
                        ImportItemRow(
                            item = item,
                            selectedAction = importState.selectedActions[item.sourcePath] ?: item.initialAction,
                            // --- FIX APPLIED: The row now correctly calls the lambda passed to the view ---
                            onActionSelected = { action ->
                                onActionSelected(item.sourcePath, action)
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
                // --- FIX APPLIED: Emits the 'onExecute' event ---
                Button(onClick = onExecute) {
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
    val fileName = item.sourcePath.substringAfterLast('/').substringAfterLast('\\')


    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(fileName, style = MaterialTheme.typography.body1)
        }

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
                    // For now, the user can only choose between the proposed action and ignoring it.
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