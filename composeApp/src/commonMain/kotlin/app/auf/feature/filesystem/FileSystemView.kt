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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
    platformDependencies: PlatformDependencies // THE FIX: Accept the dependency.
) {
    val fsState by remember {
        derivedStateOf { store.state.value.featureStates["FileSystemFeature"] as? FileSystemState }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // --- Header ---
        Text(
            text = "File System",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
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
                            pathSeparator = platformDependencies.pathSeparator, // THE FIX: Pass the primitive down.
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
    pathSeparator: Char, // THE FIX: Accept the character as an argument.
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
            text = entry.path.substringAfterLast(pathSeparator), // THE FIX: Use the passed-in character.
            style = MaterialTheme.typography.bodyLarge
        )
    }
}