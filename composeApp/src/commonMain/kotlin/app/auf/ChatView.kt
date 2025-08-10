package app.auf

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

@Composable
private fun StandardMessage(
    message: ChatMessage,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        backgroundColor = when (message.author) {
            Author.USER -> Color.White
            Author.AI -> Color.White
            Author.SYSTEM -> Color.LightGray.copy(alpha = 0.2f)
        },
        border = BorderStroke(1.dp, Color.LightGray),
        elevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message.title ?: message.author.name,
                    fontWeight = FontWeight.Bold,
                    fontStyle = if (message.author == Author.SYSTEM) FontStyle.Italic else FontStyle.Normal,
                    fontSize = 14.sp
                )
                IconButton(onClick = { clipboardManager.setText(AnnotatedString(message.content)) }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(message.content, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ActionableMessageCard(
    message: ChatMessage,
    onConfirm: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        backgroundColor = Color(0xFFE8EAF6),
        border = BorderStroke(1.dp, Color(0xFF3F51B5)),
        elevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(message.title ?: "System Action", fontWeight = FontWeight.Bold, color = Color(0xFF303F9F))
            Spacer(modifier = Modifier.height(8.dp))
            Text(message.content)
            Spacer(modifier = Modifier.height(12.dp))
            AnimatedVisibility(visible = !message.isActionResolved) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = onReject, colors = ButtonDefaults.buttonColors(backgroundColor = Color.Gray)) { Text("Reject") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onConfirm) { Text("Confirm") }
                }
            }
        }
    }
}


@Composable
fun ChatView(
    appState: AppState,
    stateManager: StateManager,
    modifier: Modifier = Modifier
) {
    val chatHistory = appState.chatHistory
    val isProcessing = appState.isProcessing
    var userMessage by remember { mutableState of("") }
    val clipboardManager = LocalClipboardManager.current

    val availableModels = appState.availableModels
    val selectedModel = appState.selectedModel
    // --- MODIFIED: Use the new list of available personas ---
    val aiPersonas = appState.availableAiPersonas
    val selectedAiPersonaId = appState.aiPersonaId
    val selectedAiPersonaName = aiPersonas.find { it.id == selectedAiPersonaId }?.name ?: "None"

    var isModelSelectorExpanded by remember { mutableStateOf(false) }
    var isAgentSelectorExpanded by remember { mutableStateOf(false) }

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

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(
                onClick = { stateManager.toggleSystemMessageVisibility() },
                colors = ButtonDefaults.buttonColors(backgroundColor = Color.LightGray.copy(alpha = 0.4f))
            ) { Text(if (appState.isSystemVisible) "Hide System" else "Show System") }

            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Active Agent:", fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    Box {
                        Button(onClick = { isAgentSelectorExpanded = true }) { Text(selectedAiPersonaName) }
                        DropdownMenu(expanded = isAgentSelectorExpanded, onDismissRequest = { isAgentSelectorExpanded = false }) {
                            // --- MODIFIED: Populate from the definitive list ---
                            aiPersonas.forEach { persona ->
                                DropdownMenuItem(onClick = { stateManager.selectAiPersona(persona.id); isAgentSelectorExpanded = false }) { Text(persona.name) }
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Model:", fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    Box {
                        Button(onClick = { isModelSelectorExpanded = true }) { Text(selectedModel) }
                        DropdownMenu(expanded = isModelSelectorExpanded, onDismissRequest = { isModelSelectorExpanded = false }) {
                            availableModels.forEach { modelName ->
                                DropdownMenuItem(onClick = { stateManager.selectModel(modelName); isModelSelectorExpanded = false }) { Text(modelName) }
                            }
                        }
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
                enabled = !isProcessing && appState.gatewayStatus == GatewayStatus.OK
            )
            Button(onClick = sendMessageAction, modifier = Modifier.padding(start = 8.dp), enabled = !isProcessing && appState.gatewayStatus == GatewayStatus.OK) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else { Text("Send") }
            }
        }
    }
}