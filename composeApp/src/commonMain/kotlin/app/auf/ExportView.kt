// FILE: composeApp/src/commonMain/kotlin/app/auf/ExportView.kt

package app.auf

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ExportView(
    appState: AppState,
    stateManager: StateManager,
    platformDependencies: PlatformDependencies, // <-- Pass in dependency
    modifier: Modifier = Modifier
) {
    // --- MODIFIED: Use the path from the global AppState ---
    var destinationPath by remember { mutableStateOf(appState.lastUsedExportPath) }
    val exportList = remember(appState.holonIdsForExport, appState.holonGraph) {
        appState.holonGraph.filter { it.id in appState.holonIdsForExport }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        // --- MODIFIED: Consistent Header ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Export for AUF Manual Runtime", style = MaterialTheme.typography.h5)
            IconButton(onClick = { stateManager.setViewMode(ViewMode.CHAT) }) {
                Icon(Icons.Default.Close, contentDescription = "Close Export View")
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            "Select holons from the Knowledge Graph on the left to add/remove them from the export manifest below. " +
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
                    items(exportList.sortedBy { it.name }) { holon ->
                        Text("- ${holon.name} (${holon.id})", fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            // --- MODIFIED: Use the platform dependency to show folder picker ---
            Button(onClick = {
                platformDependencies.showFolderPicker()?.let {
                    destinationPath = it
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