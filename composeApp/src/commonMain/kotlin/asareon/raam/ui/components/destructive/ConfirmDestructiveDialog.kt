package asareon.raam.ui.components.destructive

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Standard "are you sure?" dialog for destructive actions.
 *
 * Enforces a consistent layout across every Delete flow in the app:
 *  - Trash-can icon at the top, tinted error.
 *  - [title] headline (e.g. "Delete Agent?").
 *  - [message] body explaining what will be lost.
 *  - Confirm: [Button] with `errorContainer` / `onErrorContainer`.
 *    Default label "Delete"; override [confirmLabel] for "Clear" etc.
 *  - Cancel: [TextButton] (the M3 standard for dismiss in a confirmation).
 *
 * @param title short question headline.
 * @param message body text describing the consequence of confirming.
 * @param confirmLabel label on the destructive confirm button. Defaults to
 *   "Delete". Set to "Clear" for wipe-contents flows.
 * @param icon top icon. Defaults to the trash-can; [Icons.Default.ClearAll]
 *   or similar may be appropriate for Clear flows.
 */
@Composable
fun ConfirmDestructiveDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "Delete",
    icon: ImageVector = Icons.Default.Delete,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
