package app.auf

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// --- CORRECTED: Added the missing import for BorderStroke ---
import androidx.compose.foundation.BorderStroke

// --- HELPER COMPOSABLES ---

@Composable
private fun StandardMessage(
    message: ChatMessage,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val authorText = when (message.author) {
                Author.SYSTEM -> "SYSTEM: ${message.title}"
                Author.USER -> "USER:"
                Author.AI -> "AI:"
            }
            Text(
                text = authorText,
                fontWeight = FontWeight.Bold,
                fontStyle = if (message.author == Author.SYSTEM) FontStyle.Italic else FontStyle.Normal,
                fontSize = 14.sp,
                color = if (message.author == Author.SYSTEM) Color.Gray else Color.Unspecified
            )
            IconButton(
                onClick = { clipboardManager.setText(AnnotatedString(message.content)) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(Icons.Default.ContentCopy, "Copy", tint = Color.Gray, modifier = Modifier.size(16.dp))
            }
        }
        val messageModifier = if (message.author == Author.SYSTEM) {
            Modifier.fillMaxWidth()
                .background(Color.LightGray.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                .border(1.dp, Color.LightGray.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        } else {
            Modifier.padding(start = 8.dp)
        }
        Text(
            text = message.content,
            modifier = messageModifier,
            fontSize = 14.sp,
            color = if (message.author == Author.SYSTEM) Color.DarkGray else Color.Unspecified
        )
    }
}

@Composable
private fun ActionableMessageCard(
    message: ChatMessage,
    onConfirm: () -> Unit,
    onReject: () -> Unit
) {
    var detailsVisible by remember { mutableStateOf(false) }
    val isResolved = message.isActionResolved

    val cardBackgroundColor = when {
        isResolved && message.actionManifest != null -> Color(0xFFE8F5E9)
        isResolved -> Color.LightGray.copy(alpha = 0.3f)
        else -> Color(0xFFFFF8E1)
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        elevation = 4.dp,
        backgroundColor = cardBackgroundColor,
        border = if (!isResolved) BorderStroke(1.dp, Color(0xFFFFC107)) else null
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = message.title ?: "Confirm Action Manifest",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(message.content, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            message.actionManifest?.forEach { action ->
                Text(
                    text = "• ${action.summary}",
                    fontSize = 13.sp,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Spacer(Modifier.height(16.dp))

            AnimatedVisibility(visible = detailsVisible) {
                Column(
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.05f), RoundedCornerShape(4.dp))
                        .padding(8.dp)
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    message.actionManifest?.forEachIndexed { index, action ->
                        val contentToShow = when(action) {
                            is CreateHolon -> action.content
                            is UpdateHolonContent -> action.newContent
                            is CreateFile -> action.content
                        }
                        Text("Action ${index + 1}: ${action::class.simpleName}", fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(4.dp))
                        Text(contentToShow, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        if (index < message.actionManifest.size - 1) {
                            Divider(modifier = Modifier.padding(vertical = 8.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { detailsVisible = !detailsVisible },
                    modifier = Modifier.height(36.dp)
                ) { Text(if (detailsVisible) "Hide Details" else "View Details") }

                Spacer(Modifier.weight(1f))

                if (!isResolved) {
                    Button(
                        onClick = onReject,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color.DarkGray, contentColor = Color.White)
                    ) { Text("Reject") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF4CAF50))
                    ) { Text("Accept & Execute") }
                } else {
                    Text("Action Resolved", fontStyle = FontStyle.Italic, color = Color.Gray)
                }
            }
        }
    }
}

// --- MAIN COMPOSABLE ---

@Composable
fun ChatView(stateManager: StateManager, modifier: Modifier = Modifier) {
    val appState by stateManager.state.collectAsState()
    val chatHistory = appState.chatHistory
    val isProcessing = appState.isProcessing
    var userMessage by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current

    val sendMessageAction = {
        if (userMessage.isNotBlank() && !isProcessing) {
            stateManager.sendMessage(userMessage)
            userMessage = ""
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        LazyColumn(modifier = Modifier.weight(1f).padding(bottom = 8.dp)) {
            items(chatHistory) { message ->
                val shouldShow = appState.isSystemVisible || message.author != Author.SYSTEM || message.actionManifest != null
                if (shouldShow) {
                    if (message.actionManifest != null) {
                        ActionableMessageCard(
                            message = message,
                            onConfirm = { stateManager.executeActionFromMessage(message.timestamp) },
                            onReject = { stateManager.rejectActionFromMessage(message.timestamp) }
                        )
                    } else {
                        StandardMessage(message, clipboardManager)
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = userMessage,
                onValueChange = { userMessage = it },
                modifier = Modifier.weight(1f).onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.Enter) {
                        sendMessageAction(); true
                    } else false
                },
                placeholder = { Text("Type your message...") },
                enabled = !isProcessing
            )
            Button(onClick = sendMessageAction, modifier = Modifier.padding(start = 8.dp), enabled = !isProcessing) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else { Text("Send") }
            }
        }
    }
}