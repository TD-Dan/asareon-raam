package app.auf

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowRight
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
import kotlinx.coroutines.launch

@Composable
private fun StandardMessage(
    message: ChatMessage
) {
    var isExpanded by remember { mutableStateOf(message.author != Author.SYSTEM || message.actionManifest != null) }
    val clipboardManager = LocalClipboardManager.current

    val cardColor = when (message.author) {
        Author.USER -> Color.White
        Author.AI -> Color.White
        Author.SYSTEM -> Color.LightGray.copy(alpha = 0.2f)
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        backgroundColor = cardColor,
        border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.5f)),
        elevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f).clickable { isExpanded = !isExpanded },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ArrowDropDown else Icons.Default.ArrowRight,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = message.title ?: message.author.name,
                        fontWeight = FontWeight.Bold,
                        fontStyle = if (message.author == Author.SYSTEM) FontStyle.Italic else FontStyle.Normal,
                        fontSize = 14.sp
                    )
                }
                IconButton(onClick = {
                    clipboardManager.setText(AnnotatedString(message.content))
                }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Message Content", tint = Color.Gray)
                }
            }
            AnimatedVisibility(visible = isExpanded) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(message.content, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
            }
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
                    OutlinedButton(onClick = onReject, colors = ButtonDefaults.outlinedButtonColors()) { Text("Reject") }
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
    var userMessage by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current

    val availableModels = appState.availableModels
    val selectedModel = appState.selectedModel
    val aiPersonas = appState.availableAiPersonas
    val selectedAiPersonaId = appState.aiPersonaId
    val selectedAiPersonaName = aiPersonas.find { it.id == selectedAiPersonaId }?.name ?: "None"

    val lastTransactionTokens = appState.chatHistory.lastOrNull { it.author == Author.AI }?.usageMetadata

    val systemContextPreview = if (appState.isSystemVisible) {
        remember(appState.contextualHolonIds, appState.aiPersonaId, appState.activeHolons) {
            stateManager.getSystemContextPreview()
        }
    } else emptyList()

    val displayedMessages = systemContextPreview + appState.chatHistory

    LaunchedEffect(displayedMessages.size) {
        coroutineScope.launch {
            if (displayedMessages.isNotEmpty()) {
                lazyListState.animateScrollToItem(index = displayedMessages.size - 1)
            }
        }
    }

    val sendMessageAction = {
        if (userMessage.isNotBlank() && !appState.isProcessing) {
            stateManager.sendMessage(userMessage)
            userMessage = ""
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.weight(1f).padding(bottom = 8.dp)
        ) {
            items(displayedMessages) { message: ChatMessage ->
                if (message.actionManifest != null) {
                    ActionableMessageCard(
                        message = message,
                        onConfirm = { stateManager.executeActionFromMessage(message.timestamp) },
                        onReject = { stateManager.rejectActionFromMessage(message.timestamp) }
                    )
                } else {
                    StandardMessage(message)
                }
            }
        }

        appState.errorMessage?.let { error ->
            Text(
                text = error,
                color = Color.Red,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().background(Color.Red.copy(alpha=0.1f)).padding(8.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val systemButtonColors = if (appState.isSystemVisible) {
                    ButtonDefaults.buttonColors(backgroundColor = Color.DarkGray, contentColor = Color.White)
                } else {
                    ButtonDefaults.buttonColors(backgroundColor = Color.LightGray.copy(alpha = 0.4f), contentColor = Color.Black)
                }
                Button(onClick = { stateManager.toggleSystemMessageVisibility() }, colors = systemButtonColors) {
                    Text("Show System")
                }

                Spacer(Modifier.width(8.dp))
                IconButton(onClick = {
                    val startTag = "--START OF COPIED PROMPT TRANSCRIPT--"
                    val endTag = "--END OF COPIED PROMPT TRANSCRIPT--"
                    val systemText = stateManager.getSystemContextPreview().joinToString("\n\n") {
                        "--- START OF FILE ${it.title} ---\n${it.content}"
                    }
                    val historyText = appState.chatHistory.joinToString("\n\n") {
                        val authorTag = if(it.author == Author.AI) "[model]" else "[user]"
                        "$authorTag ${it.content}"
                    }
                    val fullPrompt = "$startTag\n\n$systemText\n\n$historyText\n\n$endTag"
                    clipboardManager.setText(AnnotatedString(fullPrompt))
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Full Prompt")
                }
            }

            lastTransactionTokens?.let {
                Text(
                    text = "Last Tx: ${it.promptTokenCount}p / ${it.candidatesTokenCount}o / ${it.totalTokenCount}t",
                    fontSize = 11.sp,
                    color = Color.Gray,
                    fontStyle = FontStyle.Italic
                )
            }


            var isModelSelectorExpanded by remember { mutableStateOf(false) }
            var isAgentSelectorExpanded by remember { mutableStateOf(false) }

            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Agent:", fontSize = 12.sp)
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
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Model:", fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    Box {
                        Button(onClick = { isModelSelectorExpanded = true }) { Text(selectedModel, maxLines = 1) }
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
            OutlinedTextField(
                value = userMessage,
                onValueChange = { userMessage = it },
                modifier = Modifier.weight(1f).onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && (event.isCtrlPressed || event.isShiftPressed) && event.key == Key.Enter) {
                        sendMessageAction(); true
                    } else false
                },
                placeholder = { Text("Enter message... (Shift+Enter or Ctrl+Enter to send)") },
                enabled = !appState.isProcessing && appState.gatewayStatus == GatewayStatus.OK
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = sendMessageAction, modifier = Modifier.height(56.dp), enabled = !appState.isProcessing && appState.gatewayStatus == GatewayStatus.OK) {
                // --- FIX: Corrected the variable name ---
                if (appState.isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                } else { Text("Send") }
            }
        }
    }
}