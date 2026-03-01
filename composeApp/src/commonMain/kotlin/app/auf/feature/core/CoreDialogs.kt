package app.auf.feature.core

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

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
                onClick = onConfirm, // THE FIX: This will now dispatch the new response action.
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
