package app.auf.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.auf.core.AssignParent
import app.auf.core.HolonHeader
import app.auf.core.Ignore
import app.auf.core.ImportAction
import app.auf.core.ImportActionType
import app.auf.core.ImportItem
import app.auf.core.Integrate
import app.auf.core.Quarantine
import app.auf.core.Update
import app.auf.util.PlatformDependencies

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
 * - `app.auf.ui.ImportExportViewModel`: The source of truth for the view's state and logic.
 * - `app.auf.core.ImportState`: The data class that this view renders.
 *
 * @version 2.0
 * @since 2025-08-17
 */
@Composable
fun ImportView(
    viewModel: ImportExportViewModel,
    currentGraph: List<HolonHeader>,
    personaId: String
) {
    val platformDependencies = remember { PlatformDependencies() }
    val importState by viewModel.importState.collectAsState()
    val sourcePath = importState?.sourcePath ?: ""

    importState?.let { state ->
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            // Top Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Import & Sync Holons", style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = { viewModel.cancelImport() }) {
                    Icon(Icons.Default.Close, contentDescription = "Close Import View")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Source Folder Selection
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = sourcePath,
                    onValueChange = { /* No-op, path is set by button */ },
                    label = { Text("Source Folder") },
                    modifier = Modifier.weight(1f),
                    readOnly = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = {
                    platformDependencies.selectDirectoryPath()?.let { selectedPath ->
                        viewModel.analyzeFolder(selectedPath, currentGraph)
                    }
                }) {
                    Text("Select & Analyze...")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Import Manifest List
            if (state.items.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (sourcePath.isBlank()) "Select a folder to analyze." else "No importable .json files found.")
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

            // Action Buttons
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = { viewModel.cancelImport() }) {
                    Text("Cancel")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { viewModel.executeImport(currentGraph, personaId) },
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
    val actions = remember { ImportActionType.values().map { it.toInstance(item.initialAction) } }
    val fileName = remember(item.sourcePath) { item.sourcePath.substringAfterLast('/').substringAfterLast('\\') }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(fileName, modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(16.dp))

        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.width(250.dp)
            ) {
                Text(selectedAction.summary, modifier = Modifier.weight(1f))
                Icon(Icons.Default.MoreVert, contentDescription = "Select Action")
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                actions.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.summary) },
                        onClick = {
                            onActionSelected(action)
                            expanded = false
                        }
                    )
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