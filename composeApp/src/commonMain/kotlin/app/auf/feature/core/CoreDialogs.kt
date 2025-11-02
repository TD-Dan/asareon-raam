package app.auf.feature.core

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
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
            Button(onClick = onConfirm) {
                Text(request.confirmButtonText)
            }
        }
    )
}