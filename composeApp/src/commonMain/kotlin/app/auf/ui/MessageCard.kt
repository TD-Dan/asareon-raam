package app.auf.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.auf.core.*
import app.auf.model.Action
import app.auf.util.JsonProvider
import kotlinx.serialization.builtins.ListSerializer

/**
 * ## Mandate
 * This file contains the `MessageCard` Composable. It displays a single `ChatMessage`,
 * allows toggling between raw and compiled views, and shows compilation statistics.
 */
@Composable
fun MessageCard(message: ChatMessage, stateManager: StateManager) {
    var isCollapsed by remember(message.id) {
        mutableStateOf(
            message.author == Author.SYSTEM &&
                    message.title != "Gateway Error" &&
                    message.title != "Graph Parsing Warning"
        )
    }

    var showRaw by remember { mutableStateOf(false) }
    var showCompiled by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    var showMenu by remember { mutableStateOf(false) }

    val cardColors = if (message.author == Author.SYSTEM) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    }
    val elevation = if (message.author == Author.SYSTEM) 0.dp else 2.dp

    val contentToCopy = remember(message) {
        message.rawContent ?: message.contentBlocks.filterIsInstance<TextBlock>().joinToString("\n") { it.text }
    }
    val showCopyButton = contentToCopy.isNotBlank()
    val titleForCopy = message.title ?: message.author.name
    val guardedCopyContent = "---COPY of ${titleForCopy}:---\n$contentToCopy\n---END OF COPY of ${titleForCopy}---"

    val formattedTimestamp = remember(message.timestamp) {
        stateManager.formatDisplayTimestamp(message.timestamp)
    }

    val showCompiledToggle = remember(message.rawContent, message.compiledContent) {
        message.compiledContent != null && message.compiledContent != message.rawContent
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = cardColors,
        elevation = CardDefaults.cardElevation(defaultElevation = elevation)
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
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "($formattedTimestamp)",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showCompiledToggle) {
                        IconButton(onClick = { showCompiled = !showCompiled }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Sync, contentDescription = "View Compiled Content", tint = if (showCompiled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    if (message.rawContent != null) {
                        IconButton(onClick = { showRaw = !showRaw }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Code, contentDescription = "View Raw Content", tint = if (showRaw) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    if (showCopyButton) {
                        IconButton(onClick = { clipboardManager.setText(AnnotatedString(guardedCopyContent)) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Message Content", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(4.dp))
                    }

                    Box {
                        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            if (message.author == Author.USER) {
                                DropdownMenuItem(text = { Text("Rerun from here") }, onClick = { stateManager.rerunFromMessage(message.id); showMenu = false })
                            }
                            val deleteText = if (message.title?.contains("Error") == true) "Dismiss" else "Delete"
                            DropdownMenuItem(text = { Text(deleteText) }, onClick = { stateManager.deleteMessage(message.id); showMenu = false })
                        }
                    }
                }
            }

            AnimatedVisibility(visible = !isCollapsed) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    when {
                        showRaw && message.rawContent != null -> RenderContent("RAW CONTENT", message.rawContent)
                        showCompiled && message.compiledContent != null -> RenderContent("COMPILED CONTENT", message.compiledContent, message.compilationStats)
                        else -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                message.contentBlocks.forEach { block ->
                                    when (block) {
                                        is TextBlock -> RenderTextBlock(block)
                                        is CodeBlock -> RenderCodeBlock(
                                            block = block,
                                            onConfirm = { stateManager.executeActionFromMessage(message.timestamp) },
                                            onReject = { stateManager.rejectActionFromMessage(message.timestamp) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RenderContent(title: String, content: String, stats: CompilationStats? = null) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                stats?.let {
                    val reduction = if (it.originalCharCount > 0) {
                        ((it.originalCharCount - it.compiledCharCount).toDouble() / it.originalCharCount * 100).toInt()
                    } else {
                        0
                    }
                    Text(
                        text = "${it.originalCharCount} -> ${it.compiledCharCount} chars (-$reduction%)",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            HorizontalDivider(modifier=Modifier.padding(vertical=8.dp))
            Text(
                text = content,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun RenderTextBlock(block: TextBlock) {
    Text(block.text, fontFamily = FontFamily.Default, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
}

@Composable
fun RenderCodeBlock(block: CodeBlock, onConfirm: () -> Unit, onReject: () -> Unit) {
    if (block.language.lowercase() == "json") {
        val parsedActions = remember(block.content) {
            try {
                JsonProvider.appJson.decodeFromString(ListSerializer(Action.serializer()), block.content)
            } catch (e: Exception) {
                null
            }
        }

        if (parsedActions != null) {
            RenderActionableJsonBlock(block, parsedActions, onConfirm, onReject)
        } else {
            // It's JSON, but not an action manifest. Treat as generic.
            RenderGenericCodeBlock(block)
        }
    } else {
        RenderGenericCodeBlock(block)
    }
}

@Composable
private fun RenderActionableJsonBlock(block: CodeBlock, actions: List<Action>, onConfirm: () -> Unit, onReject: () -> Unit) {
    val isResolved = block.status != ActionStatus.PENDING
    val cardColors = when(block.status) {
        ActionStatus.PENDING -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ActionStatus.EXECUTED -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        ActionStatus.REJECTED -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    }
    val borderColor = when(block.status) {
        ActionStatus.PENDING -> MaterialTheme.colorScheme.primary
        ActionStatus.EXECUTED -> MaterialTheme.colorScheme.tertiary
        ActionStatus.REJECTED -> MaterialTheme.colorScheme.outlineVariant
    }
    val textColor = when(block.status) {
        ActionStatus.PENDING -> MaterialTheme.colorScheme.onPrimaryContainer
        ActionStatus.EXECUTED -> MaterialTheme.colorScheme.onTertiaryContainer
        ActionStatus.REJECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val titleText = when(block.status) {
        ActionStatus.PENDING -> "Action Manifest (${actions.size} actions)"
        ActionStatus.EXECUTED -> "Action Manifest - Executed"
        ActionStatus.REJECTED -> "Action Manifest - Rejected"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = cardColors,
        border = BorderStroke(1.dp, borderColor),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(titleText, fontWeight = FontWeight.Bold, color = textColor)
            AnimatedVisibility(visible = !isResolved) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    actions.forEach { action ->
                        Text("- ${action.summary}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = textColor)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        OutlinedButton(onClick = onReject) { Text("Reject") }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = onConfirm) { Text("Confirm") }
                    }
                }
            }
        }
    }
}

@Composable
private fun RenderGenericCodeBlock(block: CodeBlock) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(block.language, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            HorizontalDivider(modifier=Modifier.padding(vertical=8.dp))
            Text(block.content, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
    }
}