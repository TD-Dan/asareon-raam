// --- FILE: commonMain/kotlin/app/auf/ui/ImportView.kt ---
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
import app.auf.core.CreateRoot
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
 * @version 2.3
 * @since 2025-08-28
 */
@Composable
fun ImportView(
    viewModel: ImportExportViewModel,
    currentGraph: List<HolonHeader>,
    personaId: String?,
    onCancel: () -> Unit
) {
    val platformDependencies = remember { PlatformDependencies() }
    val importState by viewModel.importState.collectAsState()
    // --- MODIFICATION: Collect the recursive state ---
    val isRecursive by viewModel.isRecursive.collectAsState()
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
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = "Close Import View")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Source Folder Selection
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = sourcePath,
                    onValueChange = { /* No-op */ },
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
            // --- MODIFICATION START: Add the recursive toggle ---
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = isRecursive,
                    onCheckedChange = { viewModel.setRecursive(it, currentGraph) }
                )
                Text("Import sub-folders recursively")
            }
            // --- MODIFICATION END ---
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
                    items(state.items, key = { it.sourcePath }) { item ->
                        ImportItemRow(
                            item = item,
                            selectedAction = state.selectedActions[item.sourcePath] ?: item.initialAction,
                            onActionSelected = { newAction ->
                                viewModel.updateImportAction(item.sourcePath, newAction)
                            },
                            potentialParents = currentGraph
                        )
                        Divider()
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Action Buttons
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onCancel) {
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
    onActionSelected: (ImportAction) -> Unit,
    potentialParents: List<HolonHeader>
) {
    val fileName = remember(item.sourcePath) { item.sourcePath.substringAfterLast('/').substringAfterLast('\\') }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(fileName, modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(16.dp))

        Box {
            if (selectedAction is AssignParent) {
                ParentSelector(
                    selectedAction = selectedAction,
                    potentialParents = potentialParents,
                    onActionSelected = onActionSelected
                )
            } else {
                GenericActionSelector(
                    initialAction = item.initialAction,
                    selectedAction = selectedAction,
                    onActionSelected = onActionSelected
                )
            }
        }
    }
}

@Composable
private fun ParentSelector(
    selectedAction: AssignParent,
    potentialParents: List<HolonHeader>,
    onActionSelected: (ImportAction) -> Unit
) {
    var parentMenuExpanded by remember { mutableStateOf(false) }
    var actionMenuExpanded by remember { mutableStateOf(false) }

    val selectedParentName = selectedAction.assignedParentId?.let { id ->
        potentialParents.find { it.id == id }?.name
    } ?: "Select Parent..."

    Row {
        OutlinedButton(
            onClick = { parentMenuExpanded = true },
            modifier = Modifier.width(200.dp)
        ) {
            Text(selectedParentName, modifier = Modifier.weight(1f), maxLines = 1)
        }
        DropdownMenu(
            expanded = parentMenuExpanded,
            onDismissRequest = { parentMenuExpanded = false }
        ) {
            potentialParents.forEach { parent ->
                DropdownMenuItem(
                    text = { Text(parent.name) },
                    onClick = {
                        onActionSelected(AssignParent(assignedParentId = parent.id))
                        parentMenuExpanded = false
                    }
                )
            }
        }
        IconButton(onClick = { actionMenuExpanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Change Action Type")
        }
        DropdownMenu(
            expanded = actionMenuExpanded,
            onDismissRequest = { actionMenuExpanded = false }
        ) {
            // --- MODIFICATION: Filter out CREATE_ROOT from this menu as it's auto-detected ---
            ImportActionType.values().filter { it != ImportActionType.CREATE_ROOT }.forEach { actionType ->
                DropdownMenuItem(
                    text = { Text(actionType.toInstance(selectedAction).summary) },
                    onClick = {
                        onActionSelected(actionType.toInstance(selectedAction))
                        actionMenuExpanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun GenericActionSelector(
    initialAction: ImportAction,
    selectedAction: ImportAction,
    onActionSelected: (ImportAction) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

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
        // --- MODIFICATION: Filter out CREATE_ROOT from this menu ---
        ImportActionType.values().filter { it != ImportActionType.CREATE_ROOT }.forEach { actionType ->
            DropdownMenuItem(
                text = { Text(actionType.toInstance(initialAction).summary) },
                onClick = {
                    onActionSelected(actionType.toInstance(initialAction))
                    expanded = false
                }
            )
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
        ImportActionType.CREATE_ROOT -> CreateRoot()
    }
}