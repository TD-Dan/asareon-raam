package app.auf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.auf.core.StateManager
import app.auf.core.AppState
import app.auf.core.GatewayStatus
import kotlinx.coroutines.launch

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
    val focusRequester = remember { FocusRequester() }

    val availableModels = appState.availableModels
    val selectedModel = appState.selectedModel
    val aiPersonas = appState.availableAiPersonas
    val selectedAiPersonaId = appState.aiPersonaId
    val selectedAiPersonaName = aiPersonas.find { it.id == selectedAiPersonaId }?.name ?: "None"

    val lastTransactionTokens = appState.chatHistory.lastOrNull { it.author == app.auf.core.Author.AI }?.usageMetadata

    val displayedMessages by remember(appState.isSystemVisible, appState.chatHistory, appState.activeHolons) {
        derivedStateOf {
            if (appState.isSystemVisible) {
                stateManager.getSystemContextForDisplay() + appState.chatHistory
            } else {
                appState.chatHistory
            }
        }
    }

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

    Column(modifier = modifier
        .fillMaxSize()
        .padding(16.dp)
        .background(MaterialTheme.colorScheme.surface)
    )
    {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.weight(1f).padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(
                items = displayedMessages,
                key = { message -> message.id }
            ) { message ->
                MessageCard(message = message, stateManager = stateManager)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val systemButton: @Composable () -> Unit = {
                    if (appState.isSystemVisible) {
                        Button(onClick = { stateManager.toggleSystemMessageVisibility() }) {
                            Text("Show System")
                        }
                    } else {
                        OutlinedButton(onClick = { stateManager.toggleSystemMessageVisibility() }) {
                            Text("Show System")
                        }
                    }
                }
                systemButton()


                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    val fullPrompt = stateManager.getPromptForClipboard()
                    val guardedPrompt = "---START OF COPY:---\n$fullPrompt\n--- END OF COPY---"
                    clipboardManager.setText(AnnotatedString(guardedPrompt))
                }) {
                    Text("Copy Prompt")
                }
            }


            lastTransactionTokens?.let {
                val promptTokens = it.promptTokenCount ?: 0
                val candidateTokens = it.candidatesTokenCount ?: 0
                val totalTokens = it.totalTokenCount ?: (promptTokens + candidateTokens)
                Text(
                    text = "Last Tx: ${promptTokens}p / ${candidateTokens}o / ${totalTokens}t",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = FontStyle.Italic
                )
            }

            var isModelSelectorExpanded by remember { mutableStateOf(false) }
            var isAgentSelectorExpanded by remember { mutableStateOf(false) }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Agent:", fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    Box {
                        Button(onClick = { isAgentSelectorExpanded = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) { Text(selectedAiPersonaName) }
                        DropdownMenu(expanded = isAgentSelectorExpanded, onDismissRequest = { isAgentSelectorExpanded = false }) {
                            DropdownMenuItem(text = { Text("None") }, onClick = { stateManager.selectAiPersona(null); isAgentSelectorExpanded = false })
                            aiPersonas.forEach { persona ->
                                DropdownMenuItem(text = { Text(persona.name) }, onClick = { stateManager.selectAiPersona(persona.id); isAgentSelectorExpanded = false })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Model:", fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    Box {
                        Button(
                            onClick = { isModelSelectorExpanded = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) { Text(selectedModel, maxLines = 1) }
                        DropdownMenu(expanded = isModelSelectorExpanded, onDismissRequest = { isModelSelectorExpanded = false }) {
                            availableModels.forEach { modelName ->
                                DropdownMenuItem(text = { Text(modelName) }, onClick = { stateManager.selectModel(modelName); isModelSelectorExpanded = false })
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
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                            if (event.isCtrlPressed) {
                                sendMessageAction()
                                return@onKeyEvent true // We've handled this event.
                            }
                        }
                        // For all other cases (including plain Enter, Shift+Enter, etc.),
                        // let the default handler run to allow newlines.
                        false
                    },
                placeholder = { Text("Enter message... (Ctrl+Enter to send, Enter for newline)") },
                enabled = !appState.isProcessing && appState.gatewayStatus == GatewayStatus.OK
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { if (appState.isProcessing) stateManager.cancelMessage() else sendMessageAction() },
                modifier = Modifier.height(56.dp),
                enabled = appState.gatewayStatus == GatewayStatus.OK,
                colors = if (appState.isProcessing) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.inversePrimary) else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                if (appState.isProcessing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop Request")
                        Spacer(Modifier.width(8.dp))
                        Text("Stop")
                    }
                } else {
                    Text("Send")
                }
            }
        }
    }

    // Request initial focus when the view is first composed.
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Request focus whenever the AI is done processing and the input field becomes enabled again.
    LaunchedEffect(appState.isProcessing) {
        if (!appState.isProcessing) {
            focusRequester.requestFocus()
        }
    }
}