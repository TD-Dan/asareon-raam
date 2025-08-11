package app.auf

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import javax.swing.JFileChooser
import javax.swing.filechooser.FileSystemView

@Composable
fun ImportView(
    appState: AppState,
    stateManager: StateManager,
    modifier: Modifier = Modifier
) {
    val importState = appState.importState

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Import & Sync from AUF manual runtime", style = MaterialTheme.typography.h5, modifier = Modifier.padding(bottom = 16.dp))

        if (importState == null) {
            SelectSourceStage(stateManager)
        } else {
            WorkbenchStage(appState, stateManager)
        }
    }
}

@Composable
private fun SelectSourceStage(stateManager: StateManager) {
    var sourcePath by remember { mutableStateOf<String?>(null) }
    Column {
        Text(
            "Select the folder containing the flat list of holons from your manual session. " +
                    "The tool will analyze the folder and present an interactive workbench.",
            style = MaterialTheme.typography.body2,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                val fileChooser = JFileChooser(FileSystemView.getFileSystemView().homeDirectory).apply {
                    dialogTitle = "Select Manual Runtime Folder"
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                }
                if (fileChooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    sourcePath = fileChooser.selectedFile.absolutePath
                }
            }) {
                Text("Select Source Folder...")
            }
            sourcePath?.let {
                Spacer(Modifier.width(8.dp))
                Text("Source: $it", style = MaterialTheme.typography.caption)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = { stateManager.setViewMode(ViewMode.CHAT) }) {
                Text("Cancel")
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { stateManager.analyzeImportFolder(sourcePath!!) },
                enabled = sourcePath != null
            ) {
                Text("Analyze")
            }
        }
    }
}

@Composable
private fun WorkbenchStage(
    appState: AppState,
    stateManager: StateManager
) {
    val importState = appState.importState!!

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Analysis complete. Review and modify the proposed actions for each holon. Click 'Execute Sync' to apply all changes.",
            style = MaterialTheme.typography.body2,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Row(modifier = Modifier.fillMaxWidth().background(Color.LightGray.copy(alpha = 0.3f)).padding(8.dp)) {
            Text("Source Holon", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
            Text("Proposed Action", modifier = Modifier.weight(1.5f), fontWeight = FontWeight.Bold)
            Text("Target State / Info", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(importState.items) { item ->
                val action = importState.selectedActions[item.sourceFile.absolutePath] ?: item.initialAction
                WorkbenchItemRow(
                    item = item,
                    selectedAction = action,
                    stateManager = stateManager,
                    appState = appState
                )
                Divider()
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = { stateManager.setViewMode(ViewMode.CHAT) }) {
                Text("Cancel")
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { stateManager.executeImport() }) {
                Text("Execute Sync")
            }
        }
    }
}


@Composable
private fun WorkbenchItemRow(
    item: ImportItem,
    selectedAction: ImportAction,
    stateManager: StateManager,
    appState: AppState
) {
    var isExpanded by remember { mutableStateOf(false) }
    var showParentSelector by remember { mutableStateOf(false) }

    val rowColor = if (selectedAction is Quarantine) Color.Red.copy(alpha = 0.1f) else Color.Transparent

    Row(
        modifier = Modifier.fillMaxWidth().background(rowColor).padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Panel 1: Source Manifest
        Text(item.sourceFile.name, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = MaterialTheme.typography.caption.fontSize)

        // Panel 2: Action Pipeline
        Box(modifier = Modifier.weight(1.5f).padding(horizontal = 8.dp)) {
            OutlinedButton(
                onClick = { isExpanded = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(backgroundColor = Color.White)
            ) {
                Text(selectedAction.summary, modifier = Modifier.weight(1f), maxLines = 1)
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Change Action")
            }

            DropdownMenu(
                expanded = isExpanded,
                onDismissRequest = { isExpanded = false }
            ) {
                // Always available
                DropdownMenuItem(onClick = {
                    stateManager.updateImportAction(item.sourceFile.absolutePath, Ignore())
                    isExpanded = false
                }) { Text("Ignore") }

                // --- FIX IS HERE: The 'when' is now exhaustive ---
                when (val initial = item.initialAction) {
                    is Update -> {
                        DropdownMenuItem(onClick = { stateManager.updateImportAction(item.sourceFile.absolutePath, initial); isExpanded = false }) { Text(initial.summary) }
                    }
                    is Integrate -> {
                        DropdownMenuItem(onClick = { stateManager.updateImportAction(item.sourceFile.absolutePath, initial); isExpanded = false }) { Text(initial.summary) }
                        DropdownMenuItem(onClick = { showParentSelector = true; isExpanded = false }) { Text("Assign Parent...") }
                    }
                    is AssignParent -> {
                        DropdownMenuItem(onClick = { showParentSelector = true; isExpanded = false }) { Text("Assign Parent...") }
                    }
                    is Quarantine -> {
                        DropdownMenuItem(onClick = { stateManager.updateImportAction(item.sourceFile.absolutePath, initial); isExpanded = false }) { Text("Quarantine File") }
                    }
                    is Ignore -> {
                        // This case is handled by the "Always available" item above, so nothing extra is needed here.
                    }
                }
            }
        }

        // Panel 3: Target State Preview
        val targetText = when (selectedAction) {
            is Update -> "Overwrites existing holon."
            is Integrate -> "Parent: ${appState.holonGraph.find{it.id == selectedAction.parentHolonId}?.name ?: "Unknown"}"
            is AssignParent -> "Parent: ${appState.holonGraph.find { it.id == selectedAction.assignedParentId }?.name ?: "Not Assigned"}"
            is Quarantine -> selectedAction.reason
            is Ignore -> "No change."
        }
        Text(targetText, modifier = Modifier.weight(1f), style = MaterialTheme.typography.caption)
    }

    if (showParentSelector) {
        ParentSelectorDialog(
            holons = appState.holonGraph,
            onDismiss = { showParentSelector = false },
            onSelect = { parentId ->
                stateManager.updateImportAction(
                    item.sourceFile.absolutePath,
                    AssignParent(assignedParentId = parentId)
                )
                showParentSelector = false
            }
        )
    }
}

@Composable
fun ParentSelectorDialog(
    holons: List<HolonHeader>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Parent Holon") },
        text = {
            Box(modifier = Modifier.fillMaxSize(0.8f)) {
                val state = rememberLazyListState()
                LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                    items(holons.filter { it.type != "Quarantined_File" }) { holon ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(holon.id) }
                                .padding(horizontal = 8.dp, vertical = 12.dp)
                        ) {
                            Spacer(Modifier.width((holon.depth * 16).dp))
                            Text(holon.name)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}