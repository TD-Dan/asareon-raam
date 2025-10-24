package app.auf.feature.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.auf.core.*
import app.auf.core.generated.ActionNames
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun LedgerEntryCard(
    store: Store,
    session: Session,
    entry: LedgerEntry,
    agentName: String,
    isEditingThisMessage: Boolean,
    editingContent: String?,
) {
    // --- RENDER STANDARD MESSAGE CARD ---
    val uiState = remember(session.messageUiState, entry.id) {
        session.messageUiState[entry.id] ?: MessageUiState()
    }
    var showMenu by remember { mutableStateOf(false) }

    val cardColors = if (entry.senderId == "user") {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    } else {
        CardDefaults.cardColors()
    }

    Card(modifier = Modifier.fillMaxWidth(), colors = cardColors) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f).clickable(
                        enabled = !isEditingThisMessage,
                        onClick = {
                            store.dispatch("session.ui", Action(ActionNames.SESSION_TOGGLE_MESSAGE_COLLAPSED, buildJsonObject {
                                put("sessionId", session.id); put("messageId", entry.id)
                            }))
                        }
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = agentName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        store.dispatch("session.ui", Action(ActionNames.SESSION_TOGGLE_MESSAGE_RAW_VIEW, buildJsonObject {
                            put("sessionId", session.id); put("messageId", entry.id)
                        }))
                    }, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = "Toggle Raw Content",
                            tint = if (uiState.isRawView) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = {
                        entry.rawContent?.let {
                            store.dispatch("session.ui", Action(ActionNames.CORE_COPY_TO_CLIPBOARD, buildJsonObject {
                                put("text", it)
                            }))
                        }
                    }, modifier = Modifier.size(24.dp), enabled = entry.rawContent != null) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Message Content",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Box {
                        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            if (entry.rawContent != null) {
                                DropdownMenuItem(text = { Text("Edit") }, onClick = {
                                    store.dispatch("session.ui", Action(ActionNames.SESSION_SET_EDITING_MESSAGE, buildJsonObject {
                                        put("messageId", entry.id)
                                    }))
                                    showMenu = false
                                })
                            }
                            DropdownMenuItem(text = { Text("Delete") }, onClick = {
                                store.dispatch("session.ui", Action(ActionNames.SESSION_DELETE_MESSAGE, buildJsonObject {
                                    put("session", session.id); put("messageId", entry.id)
                                }))
                                showMenu = false
                            })
                        }
                    }
                }
            }

            // Content
            AnimatedVisibility(visible = !uiState.isCollapsed) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    when {
                        isEditingThisMessage -> MessageEditor(store, session, entry, editingContent)
                        uiState.isRawView -> RawContentView(entry.rawContent ?: "--- No Raw Content ---")
                        else -> ParsedContentView(entry.content, entry.rawContent)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageEditor(store: Store, session: Session, entry: LedgerEntry, editingContent: String?) {
    var text by remember(entry.id) { mutableStateOf(editingContent ?: entry.rawContent ?: "") }

    Column {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 120.dp),
            label = { Text("Editing Raw Content") }
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = {
                store.dispatch("session.ui", Action(ActionNames.SESSION_SET_EDITING_MESSAGE, buildJsonObject {
                    put("messageId", null as String?)
                }))
            }) {
                Text("Cancel")
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                store.dispatch("session.ui", Action(ActionNames.SESSION_UPDATE_MESSAGE, buildJsonObject {
                    put("session", session.id)
                    put("messageId", entry.id)
                    put("newContent", text)
                }))
            }) {
                Text("Save")
            }
        }
    }
}

@Composable
private fun ParsedContentView(content: List<ContentBlock>, rawContent: String?) {
    if (content.isEmpty()) {
        if (!rawContent.isNullOrBlank()) {
            // THE FIX: Fall back to rendering the raw content if parsing produced no blocks.
            RawContentView(rawContent)
        } else {
            // Handle UI-only entries gracefully
            Text(" ", style = MaterialTheme.typography.bodySmall) // Render a space to maintain card height
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content.forEach { block ->
                when (block) {
                    is ContentBlock.Text -> Text(block.text)
                    is ContentBlock.CodeBlock -> {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text(
                                text = block.code,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RawContentView(rawContent: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.medium
    ) {
        Text(
            text = rawContent,
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            modifier = Modifier.padding(8.dp)
        )
    }
}