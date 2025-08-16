package app.auf.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.material.MaterialTheme // --- FIX IS HERE ---
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.auf.StateManager
import app.auf.core.AppState
import app.auf.core.ViewMode
import javax.swing.JFileChooser
import javax.swing.filechooser.FileSystemView

@Composable
fun ExportView(
    appState: AppState,
    stateManager: StateManager,
    modifier: Modifier = Modifier
) {
    var destinationPath by remember { mutableStateOf<String?>(null) }
    val exportList = remember(appState.holonIdsForExport, appState.holonGraph) {
        appState.holonGraph.filter { it.id in appState.holonIdsForExport }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Export for AUF Manual Runtime", style = MaterialTheme.typography.h5, modifier = Modifier.padding(bottom = 16.dp))
        Text(
            "Select holons from the Knowledge Graph on the left to add them to the export manifest below. " +
                    "All selected holons will be copied as a flat list into the destination folder.",
            style = MaterialTheme.typography.body2,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Card(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            border = BorderStroke(1.dp, Color.LightGray),
            elevation = 0.dp
        ) {
            if (exportList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No holons selected for export.")
                }
            } else {
                LazyColumn(modifier = Modifier.padding(8.dp)) {
                    item {
                        Text(
                            "Export Manifest (${exportList.size} items)",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    items(exportList) { holon ->
                        Text("- ${holon.name} (${holon.id})", fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                val fileChooser = JFileChooser(FileSystemView.getFileSystemView().homeDirectory).apply {
                    dialogTitle = "Select Destination Folder"
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    isAcceptAllFileFilterUsed = false
                }
                if (fileChooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    destinationPath = fileChooser.selectedFile.absolutePath
                }
            }) {
                Text("Select Destination...")
            }
            Spacer(Modifier.width(8.dp))
            destinationPath?.let {
                Text("Destination: $it", style = MaterialTheme.typography.caption)
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = { stateManager.setViewMode(ViewMode.CHAT) }) {
                Text("Cancel")
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { stateManager.executeExport(destinationPath!!) },
                enabled = destinationPath != null && exportList.isNotEmpty()
            ) {
                Text("Execute Export")
            }
        }
    }
}