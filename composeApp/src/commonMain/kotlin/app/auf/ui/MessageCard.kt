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
import androidx.compose.material.icons.filled.Warning
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
import app.auf.core.ActionBlock
import app.auf.core.ActionStatus
import app.auf.core.AnchorBlock
import app.auf.core.AppRequestBlock
import app.auf.core.Author
import app.auf.core.ChatMessage
import app.auf.core.FileContentBlock
import app.auf.core.ParseErrorBlock
import app.auf.core.SentinelBlock
import app.auf.core.StateManager
import app.auf.core.TextBlock
import app.auf.model.Action
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * ## Mandate
 * This file contains the `MessageCard` Composable. Its responsibility is to display a single
 * `ChatMessage`. It is a "dumb" component that receives state and emits events.
 * It uses the message's `rawContent` as the source of truth for display and copy actions.
 *
 * @version 1.5
 * @since 2025-08-24
 */
@Composable
fun MessageCard(message: ChatMessage, stateManager: StateManager) {
    // MODIFICATION: Ensure ParseErrorBlock is not collapsed by default.
    val hasParseErrorBlock = message.contentBlocks.any { it is ParseErrorBlock }
    var isCollapsed by remember {
        mutableStateOf(
            message.author == Author.SYSTEM &&
                    !hasParseErrorBlock && // Don't collapse if it contains a ParseErrorBlock
                    message.title != "Gateway Error" &&
                    message.title != "Graph Parsing Warning"
        )
    }
    var showRaw by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    var showMenu by remember { mutableStateOf(false) }

    val cardColors = when {
        message.title == "Gateway Error" || message.title == "Graph Parsing Warning" || hasParseErrorBlock -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
        message.author == Author.SYSTEM -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
        else -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
    val borderColor = null
    val elevation = if (message.author == Author.SYSTEM) 0.dp else 2.dp

    // MODIFICATION: The logic for copying content is now unified and authoritative
    val contentToCopy = remember(message) {
        // Prioritize rawContent. If null, fall back to joining TextBlocks.
        // This should theoretically only occur for internal system messages if they aren't provided with rawContent.
        message.rawContent ?: message.contentBlocks.filterIsInstance<TextBlock>().joinToString("\n") { it.text }
    }
    val showCopyButton = contentToCopy.isNotBlank()
    val titleForCopy = message.title ?: message.author.name
    val guardedCopyContent = "---COPY of ${titleForCopy}:---\n$contentToCopy\n---END OF COPY of ${titleForCopy}---"

    val formattedTimestamp = remember(message.timestamp) {
        stateManager.formatDisplayTimestamp(message.timestamp)
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = cardColors,
        border = if (borderColor != null) BorderStroke(1.dp, color=borderColor) else null,
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
                    // Only show raw view if raw content exists for this message
                    if (message.rawContent != null) {
                        IconButton(onClick = { showRaw = !showRaw }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Code, contentDescription = "View Raw Content", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    if (showCopyButton) {
                        IconButton(onClick = {
                            clipboardManager.setText(AnnotatedString(guardedCopyContent))
                        }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Message Content", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(4.dp))
                    }

                    Box {
                        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            if (message.author == Author.USER) {
                                DropdownMenuItem(
                                    text = { Text("Rerun from here") },
                                    onClick = {
                                        stateManager.rerunFromMessage(message.id)
                                        showMenu = false
                                    }
                                )
                            }

                            val deleteText = if (message.title?.contains("Error") == true || message.title?.contains("Warning") == true) "Dismiss" else "Delete"
                            DropdownMenuItem(
                                text = { Text(deleteText) },
                                onClick = {
                                    stateManager.deleteMessage(message.id)
                                    showMenu = false
                                }
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = !isCollapsed) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    // MODIFICATION: RenderRawContent now takes the string directly
                    if (showRaw && message.rawContent != null) {
                        RenderRawContent(message.rawContent)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            message.contentBlocks.forEach { block ->
                                when (block) {
                                    is TextBlock -> RenderTextBlock(block)
                                    is ActionBlock -> RenderActionBlock(
                                        block,
                                        onConfirm = { stateManager.executeActionFromMessage(message.timestamp) },
                                        onReject = { stateManager.rejectActionFromMessage(message.timestamp) }
                                    )
                                    is FileContentBlock -> RenderFileContentBlock(block)
                                    is AppRequestBlock -> RenderAppRequestBlock(block)
                                    is AnchorBlock -> RenderAnchorBlock(block)
                                    is ParseErrorBlock -> RenderParseErrorBlock(block)
                                    is SentinelBlock -> RenderSentinelBlock(block)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// MODIFICATION: RenderRawContent now takes the string directly
@Composable
fun RenderRawContent(rawContent: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "RAW CONTENT",
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(modifier=Modifier.padding(vertical=8.dp))
            Text(
                text = rawContent,
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
fun RenderActionBlock(block: ActionBlock, onConfirm: () -> Unit, onReject: () -> Unit) {
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
        ActionStatus.PENDING -> block.summary
        ActionStatus.EXECUTED -> "${block.summary} - Executed"
        ActionStatus.REJECTED -> "${block.summary} - Rejected"
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
                    block.actions.forEach { action ->
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
fun RenderFileContentBlock(block: FileContentBlock) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(block.fileName, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            HorizontalDivider(modifier=Modifier.padding(vertical=8.dp))
            Text(block.content, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
    }
}

@Composable
fun RenderAppRequestBlock(block: AppRequestBlock) {
    Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Warning, contentDescription = "App Request", tint = MaterialTheme.colorScheme.onSecondaryContainer)
        Spacer(Modifier.width(8.dp))
        Text(block.summary, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
fun RenderAnchorBlock(block: AnchorBlock) {
    val jsonPrettyPrinter = remember { Json { prettyPrint = true } }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("State Anchor Created: ${block.anchorId}", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            HorizontalDivider(modifier=Modifier.padding(vertical=8.dp))
            Text(jsonPrettyPrinter.encodeToString(JsonObject.serializer(), block.content), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}

@Composable
fun RenderParseErrorBlock(block: ParseErrorBlock) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "PARSE ERROR: ${block.originalTag.uppercase()}", // Corrected to use originalTag
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.error)
            Text(
                text = "Error: ${block.errorMessage}",
                fontStyle = FontStyle.Italic,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "--- Raw Content ---",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
            )
            Text(
                text = block.rawContent,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
fun RenderSentinelBlock(block: SentinelBlock) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = "Parser Sentinel",
            tint = MaterialTheme.colorScheme.onSecondaryContainer
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = block.message,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontSize = 13.sp
        )
    }
}