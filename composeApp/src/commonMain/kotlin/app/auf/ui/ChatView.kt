package app.auf.ui

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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
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
import app.auf.core.StateManager
import app.auf.core.ActionBlock
import app.auf.core.AnchorBlock
import app.auf.core.AppRequestBlock
import app.auf.core.AppState
import app.auf.core.Author
import app.auf.core.ChatMessage
import app.auf.core.FileContentBlock
import app.auf.core.GatewayStatus
import app.auf.core.TextBlock
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Composable
fun MessageCard(message: ChatMessage, stateManager: StateManager) {
    var isCollapsed by remember { mutableStateOf(message.author == Author.SYSTEM && message.title != "Gateway Error" && message.title != "Graph Parsing Warning") }
    val clipboardManager = LocalClipboardManager.current
    var showMenu by remember { mutableStateOf(false) }

    // --- MODIFIED: Use theme colors instead of hardcoded values ---
    val cardColor = when {
        message.title == "Gateway Error" || message.title == "Graph Parsing Warning" -> MaterialTheme.colors.error.copy(alpha = 0.1f)
        message.author == Author.SYSTEM -> MaterialTheme.colors.surface
        else -> MaterialTheme.colors.surface
    }
    val borderColor = when {
        message.title == "Gateway Error" || message.title == "Graph Parsing Warning" -> MaterialTheme.colors.error.copy(alpha = 0.4f)
        message.author == Author.SYSTEM -> MaterialTheme.colors.onSurface.copy(alpha = 0.12f)
        else -> Color.Transparent // No border for user/ai messages
    }
    // --- END MODIFICATION ---

    val contentToCopy = when {
        message.rawContent != null -> message.rawContent
        else -> message.contentBlocks.filterIsInstance<TextBlock>().joinToString("\n") { it.text }
    }
    val showCopyButton = contentToCopy.isNotBlank()
    val titleForCopy = message.title ?: "untitled"
    val guardedCopyContent = "---COPY of ${titleForCopy}:---\n$contentToCopy\n---END OF COPY of ${titleForCopy}---"

    val formattedTimestamp = remember(message.timestamp) {
        stateManager.formatDisplayTimestamp(message.timestamp)
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        backgroundColor = cardColor,
        border = BorderStroke(1.dp, borderColor),
        elevation = if (message.author == Author.SYSTEM) 0.dp else 1.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f).clickable { isCollapsed = !isCollapsed },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = message.title ?: message.author.name,
                        fontWeight = FontWeight.Bold,
                        fontStyle = if (message.author == Author.SYSTEM) FontStyle.Italic else FontStyle.Normal,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "($formattedTimestamp)",
                        fontSize = 11.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showCopyButton) {
                        IconButton(onClick = {
                            clipboardManager.setText(AnnotatedString(guardedCopyContent))
                        }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Message Content", tint = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium))
                        }
                    }

                    Box {
                        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium))
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            if (message.author == Author.USER) {
                                DropdownMenuItem(onClick = {
                                    stateManager.rerunMessage(message.timestamp)
                                    showMenu = false
                                }) {
                                    Text("Rerun")
                                }
                            }

                            val deleteText = if (message.title?.contains("Error") == true || message.title?.contains("Warning") == true) "Dismiss" else "Delete"
                            DropdownMenuItem(onClick = {
                                stateManager.deleteMessage(message.timestamp)
                                showMenu = false
                            }) {
                                Text(deleteText)
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(visible = !isCollapsed) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        message.contentBlocks.forEach { block ->
                            when (block) {
                                is TextBlock -> RenderTextBlock(block)
                                is ActionBlock -> RenderActionBlock(block,
                                    onConfirm = { stateManager.executeActionFromMessage(message.timestamp) },
                                    onReject = { stateManager.rejectActionFromMessage(message.timestamp) }
                                )
                                is FileContentBlock -> RenderFileContentBlock(block)
                                is AppRequestBlock -> RenderAppRequestBlock(block)
                                is AnchorBlock -> RenderAnchorBlock(block)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RenderTextBlock(block: TextBlock) {
    Text(block.text, fontFamily = FontFamily.Default, fontSize = 14.sp)
}

@Composable
fun RenderActionBlock(block: ActionBlock, onConfirm: () -> Unit, onReject: () -> Unit) {
    // --- MODIFIED: Use theme colors ---
    val backgroundColor = if (block.isResolved) MaterialTheme.colors.onSurface.copy(alpha=0.1f) else MaterialTheme.colors.primary.copy(alpha = 0.1f)
    val borderColor = if (block.isResolved) MaterialTheme.colors.onSurface.copy(alpha=0.3f) else MaterialTheme.colors.primary
    val textColor = if (block.isResolved) MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium) else MaterialTheme.colors.primary
    // --- END MODIFICATION ---

    Card(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = backgroundColor,
        border = BorderStroke(1.dp, borderColor),
        elevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(block.summary, fontWeight = FontWeight.Bold, color = textColor)
            AnimatedVisibility(visible = !block.isResolved) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    block.actions.forEach { action ->
                        Text("- ${action.summary}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        OutlinedButton(onClick = onReject, colors = ButtonDefaults.outlinedButtonColors()) { Text("Reject") }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = onConfirm) { Text("Confirm") }
                    }
                }
            }
        }
    }
}

@Composable
fun RenderFileContentBlock(block: FileContentBlock) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.12f)),
        elevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(block.fileName, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Divider(modifier=Modifier.padding(vertical=8.dp))
            Text(block.content, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
    }
}

@Composable
fun RenderAppRequestBlock(block: AppRequestBlock) {
    // --- MODIFIED: Use theme colors ---
    Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colors.secondary.copy(alpha=0.2f)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Warning, contentDescription = "App Request", tint = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium))
        Spacer(Modifier.width(8.dp))
        Text(block.summary, fontStyle = FontStyle.Italic, color = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium))
    }
}

@Composable
fun RenderAnchorBlock(block: AnchorBlock) {
    val jsonPrettyPrinter = remember { Json { prettyPrint = true } }
    Card(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colors.onSurface.copy(alpha = 0.05f)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("State Anchor Created: ${block.anchorId}", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Divider(modifier=Modifier.padding(vertical=8.dp))
            Text(jsonPrettyPrinter.encodeToString(JsonObject.serializer(), block.content), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
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

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.weight(1f).padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(
                items = displayedMessages,
                key = { message -> "${message.timestamp}-${message.author}-${message.contentBlocks.firstOrNull()?.summary}" }
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
                // --- MODIFIED: Use theme colors for the button ---
                val systemButtonColors = if (appState.isSystemVisible) {
                    ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.primary)
                } else {
                    ButtonDefaults.outlinedButtonColors()
                }
                val systemButtonBorder = if (appState.isSystemVisible) null else BorderStroke(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.12f))

                Button(
                    onClick = { stateManager.toggleSystemMessageVisibility() },
                    colors = systemButtonColors,
                    border = systemButtonBorder,
                    elevation = ButtonDefaults.elevation(defaultElevation = 0.dp)
                ) {
                    Text("Show System")
                }
                // --- END MODIFICATION ---

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
                Text(
                    text = "Last Tx: ${it.promptTokenCount}p / ${it.candidatesTokenCount}o / ${it.totalTokenCount}t",
                    fontSize = 11.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium),
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
                    if (event.type == KeyEventType.KeyDown && (event.isCtrlPressed) && event.key == Key.Enter) {
                        sendMessageAction(); true
                    } else false
                },
                placeholder = { Text("Enter message... (Ctrl+Enter to send)") },
                enabled = !appState.isProcessing && appState.gatewayStatus == GatewayStatus.OK
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { if (appState.isProcessing) stateManager.cancelMessage() else sendMessageAction() },
                modifier = Modifier.height(56.dp),
                enabled = appState.gatewayStatus == GatewayStatus.OK,
                colors = if (appState.isProcessing) ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error) else ButtonDefaults.buttonColors()
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
}