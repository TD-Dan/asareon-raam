package app.auf.feature.core

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ConfirmationDialog(
    request: ConfirmationDialogRequest,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(request.title) },
        text = { Text(request.text) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (request.isDestructive) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Text(request.confirmButtonText)
            }
        },
        dismissButton = {
            request.cancelButtonText?.let {
                TextButton(onClick = onDismiss) {
                    Text(it)
                }
            }
        }
    )
}

/**
 * A scrollable, selectable-text dialog for viewing partition content,
 * system prompts, or any large text block. Includes a copy button.
 *
 * See: §6.6 (Content Viewer Dialog) of the context architecture redesign doc.
 */
@Composable
fun ContentViewerDialog(
    request: ContentViewerDialogRequest,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(request.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        text = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                    .padding(8.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = request.content,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = if (request.isMonospace) FontFamily.Monospace else FontFamily.Default
                        ),
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onCopy(); onDismiss() }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy",
                    modifier = Modifier.size(16.dp).padding(end = 4.dp))
                Text(request.copyButtonText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(request.dismissButtonText)
            }
        }
    )
}

/**
 * Request data for the content viewer dialog.
 */
data class ContentViewerDialogRequest(
    val title: String,
    val content: String,
    val copyButtonText: String = "Copy",
    val dismissButtonText: String = "Close",
    val isMonospace: Boolean = true
)