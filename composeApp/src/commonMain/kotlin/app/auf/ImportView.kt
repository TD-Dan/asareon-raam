// FILE: composeApp/src/commonMain/kotlin/app/auf/ImportView.kt

package app.auf

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ImportView(
    appState: AppState,
    stateManager: StateManager
) {
    val importState by viewModel.importState.collectAsState()
    // The sourcePath is now the single source of truth from the ViewModel's state
    val sourcePath = importState?.sourcePath ?: ""

    importState?.let { state ->
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colors.background
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Import Holons", style = MaterialTheme.typography.h5)
                    // --- MODIFIED: Consistent Close Button ---
                    IconButton(onClick = {
                        viewModel.cancelImport()
                        stateManager.setViewMode(ViewMode.CHAT)
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Close Import View")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // --- MODIFIED: This is now a button like in the ExportView ---
                    Button(onClick = {
                        stateManager.platform.showFolderPicker()?.let { selectedPath ->
                            viewModel.setImportPath(selectedPath)
                            stateManager.updateLastUsedImportPath(selectedPath)
                        }
                    }) {
                        Text("Select Source...")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    // Display the source path from the state
                    if (sourcePath.isNotBlank()) {
                        Text("Source: $sourcePath", style = MaterialTheme.typography.caption)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Analyze button is now separate
                Button(
                    onClick = { viewModel.analyzeFolder(sourcePath, currentGraph) },
                    enabled = sourcePath.isNotBlank()
                ) {
                    Text("Analyze Folder")
                }


                Spacer(modifier = Modifier.height(16.dp))

                if (state.items.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Please select and analyze a folder.")
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(state.items) { item ->
                            ImportItemRow(
                                item = item,
                                selectedAction = state.selectedActions[item.sourcePath] ?: item.initialAction,
                                onActionSelected = { newAction ->
                                    viewModel.updateImportAction(item.sourcePath, newAction)
                                }
                            )
                            Divider()
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    // --- MODIFIED: Consistent Cancel Button ---
                    OutlinedButton(onClick = {
                        viewModel.cancelImport()
                        stateManager.setViewMode(ViewMode.CHAT)
                    }) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { viewModel.executeImport(currentGraph, personaId, holonsBasePath) },
                        enabled = state.items.isNotEmpty()
                    ) {
                        Text("Execute Import")
                    }
                }
            }
        }
    }
}

// ... (ImportItemRow and other private functions remain the same) ...
@Composable
private fun ImportItemRow(
    item: ImportItem,
    selectedAction: ImportAction,
    onActionSelected: (ImportAction) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val actions = ImportActionType.values().map { it.toInstance(item.initialAction) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(item.sourcePath.substringAfterLast('/').substringAfterLast('\\'), modifier = Modifier.weight(1f))

        Spacer(modifier = Modifier.width(16.dp))

        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.width(250.dp)
            ){
                Text(selectedAction.summary, modifier = Modifier.weight(1f))
                Icon(Icons.Default.MoreVert, contentDescription = "Select Action")
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                actions.forEach { action ->
                    DropdownMenuItem(onClick = {
                        onActionSelected(action)
                        expanded = false
                    }) {
                        Text(action.summary)
                    }
                }
            }
        }
    }
}

private fun ImportActionType.toInstance(initialAction: ImportAction): ImportAction {
    return when (this) {
        ImportActionType.UPDATE -> if (initialAction is Update) initialAction else Update("")
        ImportActionType.INTEGRATE -> if (initialAction is Integrate) initialAction else Integrate("")
        ImportActionType.ASSIGN_PARENT -> AssignParent()
        ImportActionType.QUARANTINE -> Quarantine("Manual Quarantine")
        ImportActionType.IGNORE -> Ignore()
    }
}