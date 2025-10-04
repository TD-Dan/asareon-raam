package app.auf.feature.filesystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.auf.core.Action
import app.auf.core.Store
import app.auf.util.FileEntry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun FileSystemView(store: Store) {
    val fsState by remember {
        derivedStateOf { store.state.value.featureStates["FileSystemFeature"] as? FileSystemState }
    }

    // Trigger initial navigation if path is not set
    LaunchedEffect(fsState?.currentPath) {
        if (fsState?.currentPath == null) {
            // In a real app, this would be the user's home or a bookmarked dir.
            // For now, we can leave it null or try a root path.
            // Let's assume onAction will handle an initial load.
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // --- Header ---
        Text(
            text = "File System",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = fsState?.currentPath ?: "No directory selected.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        HorizontalDivider()

        // --- Directory Listing ---
        if (fsState?.currentDirectoryListing?.isEmpty() == true) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Directory is empty or inaccessible.")
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(fsState?.currentDirectoryListing ?: emptyList(), key = { it.path }) { entry ->
                    FileRow(entry = entry, onClick = {
                        if (entry.isDirectory) {
                            val payload = buildJsonObject { put("path", entry.path) }
                            store.dispatch(Action("filesystem.NAVIGATE", payload, "filesystem.ui"))
                        }
                    })
                }
            }
        }
    }
}

@Composable
private fun FileRow(entry: FileEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
            contentDescription = if (entry.isDirectory) "Directory" else "File",
            tint = if (entry.isDirectory) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = entry.path.substringAfterLast('/'), // Simple name extraction
            style = MaterialTheme.typography.bodyLarge
        )
    }
}