package app.auf.feature.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.auf.core.Action
import app.auf.core.Store
import app.auf.core.generated.ActionRegistry
import app.auf.ui.components.CodeEditor
import app.auf.ui.components.fileDragSource
import app.auf.util.FileEntry
import app.auf.util.PlatformDependencies
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ═══════════════════════════════════════════════════════════════════════════
// WorkspacePane — right-side file browser + preview for the active session
// ═══════════════════════════════════════════════════════════════════════════
//
// Drop-in lives on the full SessionView area (not here) so users can drop
// files even when the workspace pane is closed. Drag-out lives here — each
// file list item is an OS drag source.
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun WorkspacePane(
    store: Store,
    session: Session,
    sessionState: SessionState,
    platformDependencies: PlatformDependencies,
    modifier: Modifier = Modifier
) {
    val files = sessionState.workspaceFiles[session.identity.localHandle] ?: emptyList()
    val selectedFile = sessionState.selectedWorkspaceFile
    val previewContent = sessionState.workspaceFilePreview

    Column(modifier = modifier.fillMaxHeight()) {
        // ── Header ────────────────────────────────────────────────────────
        WorkspacePaneHeader(store, session)

        HorizontalDivider()

        if (selectedFile != null && previewContent != null) {
            // ── File preview mode ─────────────────────────────────────────
            FilePreview(
                store = store,
                session = session,
                fileName = selectedFile,
                content = previewContent,
                modifier = Modifier.weight(1f)
            )
        } else if (selectedFile != null && previewContent == null) {
            // ── Loading state ─────────────────────────────────────────────
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Loading $selectedFile…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            // ── File list / empty state ───────────────────────────────────
            if (files.isEmpty()) {
                EmptyWorkspaceHint(modifier = Modifier.weight(1f))
            } else {
                FileList(
                    store = store,
                    session = session,
                    files = files,
                    platformDependencies = platformDependencies,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Header
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun WorkspacePaneHeader(store: Store, session: Session) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Files",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )

        // Refresh workspace file list
        IconButton(
            onClick = {
                store.dispatch("session", Action(
                    ActionRegistry.Names.SESSION_REFRESH_WORKSPACE,
                    buildJsonObject { put("session", session.identity.localHandle) }
                ))
            },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = "Refresh",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Open in OS file manager
        IconButton(
            onClick = {
                val uuid = session.identity.uuid ?: return@IconButton
                store.dispatch("session", Action(
                    ActionRegistry.Names.FILESYSTEM_OPEN_WORKSPACE_FOLDER,
                    buildJsonObject { put("path", "$uuid/workspace") }
                ))
            },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.FolderOpen,
                contentDescription = "Open in file manager",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Empty State
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun EmptyWorkspaceHint(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.outlineVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "No files yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Drop files here or agents\nwill create them",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// File List
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun FileList(
    store: Store,
    session: Session,
    files: List<FileEntry>,
    platformDependencies: PlatformDependencies,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(files, key = { it.path }) { file ->
            FileListItem(store, session, file, platformDependencies)
        }
    }
}

@Composable
private fun FileListItem(
    store: Store,
    session: Session,
    file: FileEntry,
    platformDependencies: PlatformDependencies
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val fileName = platformDependencies.getFileName(file.path)
    val extension = fileName.substringAfterLast('.', "").lowercase()
    val isPreviewable = extension in PREVIEWABLE_EXTENSIONS
    val uuid = session.identity.uuid

    // ── Resolve the absolute sandbox path for drag-out ────────────────
    val absolutePath = remember(uuid, fileName) {
        if (uuid != null) {
            platformDependencies.resolveAbsoluteSandboxPath(
                "session",
                "$uuid/workspace/$fileName"
            )
        } else null
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            // ── Drag-out: each file item is a drag source ─────────────
            .then(
                if (absolutePath != null) {
                    Modifier.fileDragSource(absolutePath, fileName)
                } else {
                    Modifier
                }
            )
            .clickable(enabled = isPreviewable) {
                store.dispatch("session", Action(
                    ActionRegistry.Names.SESSION_SELECT_WORKSPACE_FILE,
                    buildJsonObject {
                        put("session", session.identity.localHandle)
                        put("fileName", fileName)
                    }
                ))
            },
        color = if (isHovered) MaterialTheme.colorScheme.surfaceContainerHigh
        else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Drag handle hint — visible on hover
            if (isHovered && absolutePath != null) {
                Icon(
                    Icons.Default.DragIndicator,
                    contentDescription = "Drag to export",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
            }

            Icon(
                imageVector = iconForExtension(extension),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (file.lastModified != null) {
                    Text(
                        text = platformDependencies.formatDisplayTimestamp(file.lastModified),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Hover actions — delete
            if (isHovered) {
                var confirmDelete by remember { mutableStateOf(false) }

                if (confirmDelete) {
                    IconButton(
                        onClick = {
                            store.dispatch("session", Action(
                                ActionRegistry.Names.SESSION_DELETE_WORKSPACE_FILE,
                                buildJsonObject {
                                    put("session", session.identity.localHandle)
                                    put("fileName", fileName)
                                }
                            ))
                            confirmDelete = false
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.DeleteForever,
                            contentDescription = "Confirm delete",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    IconButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Delete file",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                LaunchedEffect(isHovered) {
                    if (!isHovered) confirmDelete = false
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// File Preview
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun FilePreview(
    store: Store,
    session: Session,
    fileName: String,
    content: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = {
                    store.dispatch("session", Action(
                        ActionRegistry.Names.SESSION_SELECT_WORKSPACE_FILE,
                        buildJsonObject { put("session", session.identity.localHandle) }
                    ))
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Back to file list",
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = fileName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    store.dispatch("session", Action(
                        ActionRegistry.Names.CORE_COPY_TO_CLIPBOARD,
                        buildJsonObject { put("text", content) }
                    ))
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copy contents",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider()

        CodeEditor(
            value = content,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(4.dp)
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Utilities
// ═══════════════════════════════════════════════════════════════════════════

private val PREVIEWABLE_EXTENSIONS = setOf(
    "txt", "md", "json", "xml", "yaml", "yml", "toml",
    "kt", "kts", "java", "py", "js", "ts", "html", "css",
    "sh", "bash", "zsh", "csv", "log", "ini", "cfg",
    "gradle", "properties", "svg"
)

private fun iconForExtension(ext: String) = when (ext) {
    "md", "txt", "log"          -> Icons.Default.Description
    "json", "xml", "yaml", "yml",
    "toml", "ini", "cfg",
    "properties"                -> Icons.Default.Settings
    "kt", "kts", "java", "py",
    "js", "ts", "sh", "bash",
    "zsh", "gradle"             -> Icons.Default.Code
    "html", "css", "svg"        -> Icons.Default.Language
    "csv"                       -> Icons.Default.TableChart
    "png", "jpg", "jpeg",
    "gif", "webp", "bmp"        -> Icons.Default.Image
    else                        -> Icons.Default.InsertDriveFile
}