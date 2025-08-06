package app.auf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ChatView(stateManager: StateManager, modifier: Modifier = Modifier) {
    val appState by stateManager.state.collectAsState()
    val chatHistory = appState.chatHistory
    val isProcessing = appState.isProcessing
    var userMessage by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current

    // --- Data for Control Panel ---
    val availableModels = appState.availableModels
    val selectedModel = appState.selectedModel
    val aiPersonas = appState.holonCatalogue.filter { it.type == "AI_Persona" }
    val selectedAiPersonaId = appState.aiPersonaId
    val selectedAiPersonaName = aiPersonas.find { it.id == selectedAiPersonaId }?.name ?: "None"

    var isModelSelectorExpanded by remember { mutableStateOf(false) }
    var isAgentSelectorExpanded by remember { mutableStateOf(false) }

    // --- NEW: Check if a manifest is pending user confirmation ---
    val isManifestPending = appState.pendingActionManifest != null

    val sendMessageAction = {
        if (userMessage.isNotBlank() && !isProcessing && !isManifestPending) {
            stateManager.sendMessage(userMessage)
            userMessage = ""
        }
    }
    val displayedHistory = if (appState.isSystemVisible) chatHistory else chatHistory.filter { it.author != Author.SYSTEM }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        // Message History
        LazyColumn(modifier = Modifier.weight(1f).padding(bottom = 8.dp)) {
            items(displayedHistory) { message ->
                // This entire message display block is unchanged and correct.
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val authorText = when (message.author) {
                            Author.SYSTEM -> "SYSTEM: ${message.title}"
                            else -> "${message.author}:"
                        }
                        val authorFontWeight = when (message.author) {
                            Author.SYSTEM, Author.USER -> FontWeight.Bold
                            else -> FontWeight.Normal
                        }
                        Text(
                            text = authorText,
                            fontWeight = authorFontWeight,
                            fontStyle = if (message.author == Author.SYSTEM) FontStyle.Italic else FontStyle.Normal,
                            fontSize = if (message.author == Author.SYSTEM) 12.sp else 14.sp,
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
                        Modifier.fillMaxWidth().background(Color.LightGray.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                            .border(1.dp, Color.LightGray.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    } else { Modifier.padding(start = 8.dp) }
                    Text(
                        text = message.content,
                        modifier = messageModifier,
                        fontStyle = if (message.author == Author.SYSTEM) FontStyle.Italic else FontStyle.Normal,
                        fontSize = if (message.author == Author.SYSTEM) 12.sp else 14.sp,
                        color = if (message.author == Author.SYSTEM) Color.DarkGray else Color.Unspecified
                    )
                }
            }
        }

        // --- NEW: Manifest Confirmation Panel ---
        if (isManifestPending) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                elevation = 4.dp,
                backgroundColor = Color(0xFFFFF8E1) // A light yellow to draw attention
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Confirm Action Manifest",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The AI has proposed changes to the knowledge graph. Review the proposal in the chat history. Do you want to execute these changes?",
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = { stateManager.rejectManifest() },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color.DarkGray, contentColor = Color.White)
                        ) { Text("Reject") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { stateManager.confirmManifest() },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF4CAF50)) // A positive green
                        ) { Text("Accept & Execute") }
                    }
                }
            }
        }

        // Control Panel Toolbar (unchanged)
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
                            DropdownMenuItem(onClick = { stateManager.selectAiPersona(null); isAgentSelectorExpanded = false }) { Text("None") }
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

        // --- MODIFIED: Input field and send button are now disabled when a manifest is pending ---
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = userMessage,
                onValueChange = { userMessage = it },
                modifier = Modifier.weight(1f).onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.Enter) {
                        sendMessageAction(); true
                    } else false
                },
                placeholder = { if (isManifestPending) Text("Confirm or reject the pending manifest to continue.") else Text("Type your message...") },
                enabled = !isProcessing && !isManifestPending // Disabled when processing OR when manifest is pending
            )
            Button(onClick = sendMessageAction, modifier = Modifier.padding(start = 8.dp), enabled = !isProcessing && !isManifestPending) {
                if (isProcessing) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(24.dp)) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                    }
                } else { Text("Send") }
            }
        }
    }
}