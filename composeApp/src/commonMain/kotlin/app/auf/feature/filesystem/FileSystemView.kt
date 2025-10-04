package app.auf.feature.filesystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.auf.core.Action
import app.auf.core.Store
import app.auf.util.FileEntry
import app.auf.util.PlatformDependencies
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun FileSystemView(
    store: Store,
    platformDependencies: PlatformDependencies
) {
    // --- BUG FIX ---
    // Collect the entire app state flow. This ensures that whenever the store emits
    // a new state, this composable will be recomposed.
    val appState by store.state.collectAsState()
    // Derive the feature-specific state within the composable body.
    val fsState = appState.featureStates["FileSystemFeature"] as? FileSystemState
    // --- END BUG FIX ---

    val parentPath = fsState?.currentPath?.let { platformDependencies.getParentDirectory(it) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // --- Header ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "File System",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )
            // --- FEATURE: Up Button ---
            IconButton(
                onClick = {
                    parentPath?.let {
                        val payload = buildJsonObject { put("path", it) }
                        store.dispatch(Action("filesystem.NAVIGATE", payload, "filesystem.ui"))
                    }
                },
                enabled = parentPath != null
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Go Up")
            }
            // --- FEATURE: Select Folder Button ---
            IconButton(
                onClick = { store.dispatch(Action("filesystem.SELECT_DIRECTORY_UI", null, "filesystem.ui")) }
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = "Select Folder")
            }
        }

        Text(
            text = fsState?.currentPath ?: "Loading...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        HorizontalDivider()

        // --- Error Panel ---
        fsState?.error?.let { errorMessage ->
            ErrorPanel(errorMessage)
        }

        // --- Directory Listing ---
        if (fsState?.error == null) {
            if (fsState?.currentDirectoryListing?.isEmpty() == true && fsState?.currentPath != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Directory is empty.")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(fsState?.currentDirectoryListing ?: emptyList(), key = { it.path }) { entry ->
                        FileRow(
                            entry = entry,
                            pathSeparator = platformDependencies.pathSeparator,
                            onClick = {
                                if (entry.isDirectory) {
                                    val payload = buildJsonObject { put("path", entry.path) }
                                    store.dispatch(Action("filesystem.NAVIGATE", payload, "filesystem.ui"))
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorPanel(message: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(16.dp)
        )
    }
}


@Composable
private fun FileRow(
    entry: FileEntry,
    pathSeparator: Char,
    onClick: () -> Unit
) {
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
            text = entry.path.substringAfterLast(pathSeparator),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}