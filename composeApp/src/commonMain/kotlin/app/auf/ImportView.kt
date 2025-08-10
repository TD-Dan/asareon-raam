package app.auf

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
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
    var sourcePath by remember { mutableStateOf<String?>(null) }
    val analysisResult = appState.importAnalysisResult

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Import & Sync from AUF manual runtime", style = MaterialTheme.typography.h5, modifier = Modifier.padding(bottom = 16.dp))

        if (analysisResult == null || sourcePath == null) {
            // --- Stage 1: Selection ---
            Text(
                "Select the folder containing the flat list of holons from your manual session. " +
                        "The tool will analyze the folder and show you what has changed before making any modifications.",
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

        } else {
            // --- Stage 2: Analysis & Confirmation ---
            Text(
                "Analysis complete. Review the proposed changes below. No files will be modified until you click 'Execute Sync'.",
                style = MaterialTheme.typography.body2,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AnalysisResultColumn("To Be Updated (${analysisResult.updatedHolons.size})", analysisResult.updatedHolons.map { it.existingHeader.name })
                AnalysisResultColumn("New Holons (${analysisResult.newHolons.size})", analysisResult.newHolons.map { it.name })
                AnalysisResultColumn("Unchanged (${analysisResult.unchangedHolons.size})", analysisResult.unchangedHolons.map { it.name })
            }

            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
}

@Composable
private fun RowScope.AnalysisResultColumn(title: String, items: List<String>) {
    Card(modifier = Modifier.weight(1f).fillMaxHeight(), border = BorderStroke(1.dp, Color.LightGray)) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            Divider()
            Spacer(Modifier.height(8.dp))
            LazyColumn {
                items(items) { item ->
                    Text(item, fontFamily = FontFamily.Monospace, fontSize = MaterialTheme.typography.caption.fontSize)
                }
            }
        }
    }
}