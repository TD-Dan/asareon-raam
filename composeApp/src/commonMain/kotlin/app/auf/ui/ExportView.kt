package app.auf.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import app.auf.core.StateManager
import app.auf.feature.knowledgegraph.KnowledgeGraphState
import app.auf.feature.knowledgegraph.KnowledgeGraphViewMode
import app.auf.util.PlatformDependencies

@Composable
fun ExportView(
    kgState: KnowledgeGraphState,
    stateManager: StateManager,
    modifier: Modifier = Modifier
) {
    val platformDependencies = remember { PlatformDependencies() }
    var destinationPath by remember { mutableStateOf<String?>(null) }
    val exportListIds = kgState.holonIdsForExport

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Export for Manual Runtime", style = MaterialTheme.typography.headlineSmall)
            IconButton(onClick = { stateManager.setKnowledgeGraphViewMode(KnowledgeGraphViewMode.INSPECTOR) }) {
                Icon(Icons.Default.Close, contentDescription = "Close Export View")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Select holons to include in the export. The currently active context has been pre-selected. All selected holons will be copied into the destination folder.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Export Manifest (${exportListIds.size} / ${kgState.holonGraph.size} items)",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row {
                TextButton(onClick = { stateManager.selectAllForExport() }) {
                    Text("Select All")
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { stateManager.deselectAllForExport() }) {
                    Text("Deselect All")
                }
            }
        }
        Divider()

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            items(kgState.holonGraph, key = { it.header.id }) { holon ->
                val isSelected = holon.header.id in exportListIds
                val isPersona = holon.header.id == kgState.aiPersonaId
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { stateManager.toggleHolonForExport(holon.header.id) },
                        enabled = !isPersona
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = holon.header.name,
                        fontStyle = if (isPersona) FontStyle.Italic else FontStyle.Normal,
                        fontFamily = FontFamily.Monospace,
                        color = if (isPersona) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

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

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = { stateManager.setKnowledgeGraphViewMode(KnowledgeGraphViewMode.INSPECTOR) }) {
                Text("Cancel")
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { stateManager.executeExport(destinationPath!!) },
                enabled = destinationPath != null && exportListIds.isNotEmpty()
            ) {
                Text("Execute Export")
            }
        }
    }
}