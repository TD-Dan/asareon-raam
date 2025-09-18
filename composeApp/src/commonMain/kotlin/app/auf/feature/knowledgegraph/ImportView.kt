// --- file: commonMain/kotlin/app/auf/ui/ImportView.kt ---
package app.auf.feature.knowledgegraph

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
import app.auf.core.StateManager
import app.auf.util.PlatformDependencies

@Composable
fun ImportView(
    kgState: KnowledgeGraphState,
    stateManager: StateManager
) {
    val platformDependencies = remember { PlatformDependencies() }
    val isRecursive = kgState.isImportRecursive
    val showOnlyChanged = kgState.showOnlyChangedImportItems
    val sourcePath = kgState.importSourcePath

    val filteredItems = remember(kgState.importItems, showOnlyChanged) {
        if (showOnlyChanged) {
            kgState.importItems.filter { it.initialAction !is Ignore }
        } else {
            kgState.importItems
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Import & Sync Holons", style = MaterialTheme.typography.headlineSmall)
            IconButton(onClick = { stateManager.dispatch(SetViewMode(KnowledgeGraphViewMode.INSPECTOR)) }) {
                Icon(Icons.Default.Close, contentDescription = "Close Import View")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

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
                    stateManager.dispatch(StartImportAnalysis(selectedPath))
                }
            }) {
                Text("Select & Analyze...")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = isRecursive,
                    onCheckedChange = { stateManager.dispatch(SetImportRecursive(it)) }
                )
                Text("Import sub-folders recursively")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = showOnlyChanged,
                    onCheckedChange = { stateManager.dispatch(ToggleShowOnlyChangedImportItems) }
                )
                Text("Show only changed files")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (kgState.importItems.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(if (sourcePath.isBlank()) "Select a folder to analyze." else "No importable .json files found.")
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(filteredItems, key = { it.sourcePath }) { item ->
                    ImportItemRow(
                        item = item,
                        selectedAction = kgState.importSelectedActions[item.sourcePath] ?: item.initialAction,
                        onActionSelected = { newAction ->
                            stateManager.dispatch(UpdateImportAction(item.sourcePath, newAction))
                        },
                        potentialParents = kgState.holonGraph.map { it.header }
                    )
                    HorizontalDivider()
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = { stateManager.dispatch(SetViewMode(KnowledgeGraphViewMode.INSPECTOR)) }) {
                Text("Cancel")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { stateManager.dispatch(ExecuteImport) },
                enabled = kgState.importItems.isNotEmpty()
            ) {
                Text("Execute Import")
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
            ImportActionType.entries.filter { it != ImportActionType.CREATE_ROOT }.forEach { actionType ->
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
        ImportActionType.entries.filter { it != ImportActionType.CREATE_ROOT }.forEach { actionType ->
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
        ImportActionType.UPDATE -> initialAction as? Update ?: Update("")
        ImportActionType.INTEGRATE -> initialAction as? Integrate ?: Integrate("")
        ImportActionType.ASSIGN_PARENT -> AssignParent()
        ImportActionType.QUARANTINE -> Quarantine("Manual Quarantine")
        ImportActionType.IGNORE -> Ignore()
        ImportActionType.CREATE_ROOT -> CreateRoot()
    }
}