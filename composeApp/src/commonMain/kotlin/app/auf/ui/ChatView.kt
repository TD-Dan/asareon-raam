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
import app.auf.StateManager
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
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun MessageCard(message: ChatMessage, stateManager: StateManager) {
    var isCollapsed by remember { mutableStateOf(message.author == Author.SYSTEM && message.title != "Gateway Error" && message.title != "Graph Parsing Warning") }
    val clipboardManager = LocalClipboardManager.current
    var showMenu by remember { mutableStateOf(false) }

    val cardColor = when {
        message.title == "Gateway Error" || message.title == "Graph Parsing Warning" -> Color.Red.copy(alpha = 0.1f)
        message.author == Author.USER -> Color.White
        message.author == Author.AI -> Color.White
        message.author == Author.SYSTEM -> Color.LightGray.copy(alpha = 0.2f)
        else -> Color.White
    }
    val borderColor = when {
        message.title == "Gateway Error" || message.title == "Graph Parsing Warning" -> Color.Red.copy(alpha = 0.4f)
        else -> Color.LightGray.copy(alpha = 0.5f)
    }

    val contentToCopy = when {
        message.rawContent != null -> message.rawContent
        else -> message.contentBlocks.filterIsInstance<TextBlock>().joinToString("\n") { it.text }
    }
    val showCopyButton = contentToCopy.isNotBlank()
    val titleForCopy = message.title ?: "untitled"
    val guardedCopyContent = "---COPY of ${titleForCopy}:---\n$contentToCopy\n---END OF COPY of ${titleForCopy}---"

    val formattedTimestamp = remember(message.timestamp) {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        sdf.format(Date(message.timestamp))
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        backgroundColor = cardColor,
        border = BorderStroke(1.dp, borderColor),
        elevation = 0.dp
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
                        color = Color.Gray
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showCopyButton) {
                        IconButton(onClick = {
                            clipboardManager.setText(AnnotatedString(guardedCopyContent))
                        }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Message Content", tint = Color.Gray)
                        }
                    }

                    Box {
                        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = Color.Gray)
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = if (block.isResolved) Color.LightGray.copy(alpha=0.3f) else Color(0xFFE8EAF6),
        border = BorderStroke(1.dp, if (block.isResolved) Color.Gray else Color(0xFF3F51B5)),
        elevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(block.summary, fontWeight = FontWeight.Bold, color = if (block.isResolved) Color.Gray else Color(0xFF303F9F))
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
        border = BorderStroke(1.dp, Color.Gray),
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
    Row(modifier = Modifier.fillMaxWidth().background(Color.Yellow.copy(alpha=0.2f)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Warning, contentDescription = "App Request", tint = Color.DarkGray)
        Spacer(Modifier.width(8.dp))
        Text(block.summary, fontStyle = FontStyle.Italic, color = Color.DarkGray)
    }
}

@Composable
fun RenderAnchorBlock(block: AnchorBlock) {
    val jsonPrettyPrinter = remember { Json { prettyPrint = true } }
    Card(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = Color.Black.copy(alpha = 0.05f)
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
            modifier = Modifier.weight(1f).padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(
                items = displayedMessages,
                key = { message -> "${message.timestamp}-${message.title}" }
            ) { message ->
                MessageCard(message = message, stateManager = stateManager)
            }
        }

        // --- FIX IS HERE: The generic error message banner has been removed. ---

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
                OutlinedButton(onClick = {
                    val fullPrompt = stateManager.getPromptAsString()
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
                colors = if (appState.isProcessing) ButtonDefaults.buttonColors(backgroundColor = Color.Red, contentColor = Color.White) else ButtonDefaults.buttonColors()
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