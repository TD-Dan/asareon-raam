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

/**
 * A Composable screen for managing the import of Holons from an external source.
 *
 * ---
 * ## Mandate
 * This view's responsibility is to render the `ImportState` provided by the
 * `ImportExportViewModel` and to delegate all user actions (analyze, import, select action)
 * back to that ViewModel. It is a "dumb" component that only knows how to display state
 * and forward events.
 *
 * ---
 * ## Dependencies
 * - `app.auf.ImportExportViewModel`: The source of truth for the view's state and logic.
 * - `app.auf.ImportState`: The data class that this view renders.
 *
 * @version 1.3
 * @since 2025-08-15
 */
@Composable
fun ImportView(
    viewModel: ImportExportViewModel,
    currentGraph: List<HolonHeader>,
    personaId: String,
    // --- FIX IS HERE: `holonsBasePath` parameter removed. ---
) {
    val importState by viewModel.importState.collectAsState()
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
                    IconButton(onClick = { viewModel.cancelImport() }) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = sourcePath,
                        onValueChange = { /* No-op */ },
                        label = { Text("Source Folder") },
                        modifier = Modifier.weight(1f),
                        readOnly = true
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { viewModel.analyzeFolder(sourcePath, currentGraph) }) {
                        Text("Analyze Folder")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (state.items.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No importable files found. Please analyze a folder.")
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

                Button(
                    // --- FIX IS HERE: `holonsBasePath` argument removed from the call. ---
                    onClick = { viewModel.executeImport(currentGraph, personaId) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.items.isNotEmpty()
                ) {
                    Text("Execute Import")
                }
            }
        }
    }
}

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