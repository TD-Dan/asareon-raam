package app.auf.feature.filesystem

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.auf.core.Action
import app.auf.core.Feature
import app.auf.core.Store
import app.auf.util.PlatformDependencies
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ## Mandate
 * To act as the sole, auditable gateway to the user's file system for the AUF App.
 *
 * This feature provides a transactional buffer for all file I/O operations, enforcing
 * the constitutional `DIRECTIVE_ALIGNMENT_AND_RATIFICATION` through a "staging" and
 * "commit" model. No file is ever touched without being part of an explicit,
 * user-approved transaction. It also enforces a strict security sandbox via a
 * user-configurable path whitelist.
 */
class FileSystemFeature(
    private val platformDependencies: PlatformDependencies
) : Feature {
    override val name: String = "FileSystemFeature"
    override val composableProvider: Feature.ComposableProvider = FileSystemComposableProvider()

    // The reducer and onAction handlers will be implemented in subsequent tasks (TSK-FS-005).

    inner class FileSystemComposableProvider : Feature.ComposableProvider {
        override val viewKey: String = "feature.filesystem.main"

        @Composable
        override fun RibbonButton(store: Store, isActive: Boolean) {
            val payload = buildJsonObject { put("key", viewKey) }
            IconButton(onClick = { store.dispatch(Action("core.SET_ACTIVE_VIEW", payload, "filesystem.ui")) }) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = "File System Browser",
                    tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        @Composable
        override fun StageContent(store: Store) {
            // This is a placeholder that will be fully implemented in TSK-FS-006.
            Text("File System Browser Placeholder")
        }
    }
}