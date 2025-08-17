package app.auf.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.auf.core.AppState
import app.auf.core.StateManager
import app.auf.core.ViewMode
import app.auf.util.PlatformDependencies

@Composable
fun ExportView(
    appState: AppState,
    stateManager: StateManager,
    modifier: Modifier = Modifier
) {
    // This is a temporary solution for dependency injection.
    // In a full DI framework, this would be provided.
    val platformDependencies = remember { PlatformDependencies() }
    var destinationPath by remember { mutableStateOf<String?>(null) }
    val exportList = remember(appState.holonIdsForExport, appState.holonGraph) {
        appState.holonGraph.filter { it.header.id in appState.holonIdsForExport }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        // Top Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Export for Manual Runtime", style = MaterialTheme.typography.headlineSmall)
            IconButton(onClick = { stateManager.setViewMode(ViewMode.CHAT) }) {
                Icon(Icons.Default.Close, contentDescription = "Close Export View")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Select holons from the Knowledge Graph to add them to the manifest. All selected holons will be copied as a flat list into the destination folder.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Manifest Card
        Card(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            elevation = CardDefaults.cardElevation(0.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            if (exportList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No holons selected for export.")
                }
            } else {
                LazyColumn(modifier = Modifier.padding(12.dp)) {
                    item {
                        Text(
                            "Export Manifest (${exportList.size} items)",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Divider()
                    }
                    items(exportList) { holon ->
                        Text(
                            "- ${holon.header.name} (${holon.header.id})",
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Destination Selector
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                destinationPath = platformDependencies.selectDirectoryPath()
            }) {
                Text("Select Destination...")
            }
            Spacer(Modifier.width(12.dp))
            destinationPath?.let {
                Text(
                    text = "Destination: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Action Buttons
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