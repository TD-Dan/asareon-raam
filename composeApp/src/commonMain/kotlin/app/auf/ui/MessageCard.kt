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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import app.auf.feature.session.LedgerEntry
import app.auf.service.AufTextParser

/**
 * ## Mandate
 * Renders a single entry from the session transcript. It is responsible for parsing the
 * raw content string from the LedgerEntry into displayable ContentBlocks.
 */
@Composable
fun MessageCard(
    entry: LedgerEntry,
    stateManager: StateManager
) {
    var showRaw by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    var showMenu by remember { mutableStateOf(false) }
    var isCollapsed by remember { mutableStateOf(false) }

    // Each card gets its own parser instance.
    val parser = remember { AufTextParser() }
    val contentBlocks = remember(entry.content) { parser.parse(entry.content) }

    val authorName = remember(entry.agentId) {
        when {
            entry.agentId == "USER" -> "USER"
            entry.agentId == "CORE" -> "CORE"
            else -> "AI" // Default for any other agentId
        }
    }

    val cardColors = when(authorName) {
        "CORE" -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
        else -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    }

    val elevation = if (authorName == "CORE") 0.dp else 2.dp

    val guardedCopyContent = "---COPY of ${authorName} entry:---\n${entry.content}\n---END OF COPY---"

    val formattedTimestamp = remember(entry.timestamp) {
        stateManager.formatDisplayTimestamp(entry.timestamp)
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
                        text = authorName,
                        fontWeight = FontWeight.Bold,
                        fontStyle = if (authorName == "CORE") FontStyle.Italic else FontStyle.Normal,
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
                    IconButton(onClick = { showRaw = !showRaw }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Code, contentDescription = "View Raw Content", tint = if (showRaw) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(4.dp))

                    IconButton(onClick = { clipboardManager.setText(AnnotatedString(guardedCopyContent)) }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Message Content", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(4.dp))

                    Box {
                        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            // TODO: Implement Delete and Rerun actions for LedgerEntries
                            DropdownMenuItem(text = { Text("Delete (NYI)") }, onClick = { /* stateManager.deleteMessage(entry.id) */ showMenu = false })
                        }
                    }
                }
            }

            AnimatedVisibility(visible = !isCollapsed) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    if (showRaw) {
                        RenderRawContent("RAW CONTENT", entry.content)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            contentBlocks.forEach { block ->
                                when (block) {
                                    is TextBlock -> RenderTextBlock(block)
                                    is CodeBlock -> RenderGenericCodeBlock(block)
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
fun RenderRawContent(title: String, content: String) {
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