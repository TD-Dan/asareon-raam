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
    // Get the persistent UI state for this specific message.
    val uiState = remember(session.messageUiState, entry.id) {
        session.messageUiState[entry.id] ?: MessageUiState()
    }

    var showMenu by remember { mutableStateOf(false) }

    val cardColors = if (entry.agentId == "user") {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    } else {
        CardDefaults.cardColors()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = cardColors
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // --- HEADER ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Clickable area to toggle collapsed state
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            enabled = !isEditingThisMessage, // Disable collapse while editing
                            onClick = {
                                store.dispatch("session.ui", Action("session.TOGGLE_MESSAGE_COLLAPSED", buildJsonObject {
                                    put("sessionId", session.id)
                                    put("messageId", entry.id)
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

                // Action Icons
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // View Raw Icon
                    IconButton(
                        onClick = {
                            store.dispatch("session.ui", Action("session.TOGGLE_MESSAGE_RAW_VIEW", buildJsonObject {
                                put("sessionId", session.id)
                                put("messageId", entry.id)
                            }))
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = "Toggle Raw Content",
                            tint = if (uiState.isRawView) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    // Copy Icon
                    IconButton(
                        onClick = {
                            store.dispatch("session.ui", Action("core.COPY_TO_CLIPBOARD", buildJsonObject {
                                put("text", entry.rawContent)
                            }))
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Message Content",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    // Kebab Menu for destructive/rare actions
                    Box {
                        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Edit") },
                                onClick = {
                                    store.dispatch("session.ui", Action("session.SET_EDITING_MESSAGE", buildJsonObject {
                                        put("messageId", entry.id)
                                    }))
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = {
                                    store.dispatch("session.ui", Action("session.DELETE_MESSAGE", buildJsonObject {
                                        put("session", session.id) // Use session ID for target
                                        put("messageId", entry.id)
                                    }))
                                    showMenu = false
                                }
                            )
                        }
                    }
                }
            }

            // --- CONTENT ---
            AnimatedVisibility(visible = !uiState.isCollapsed) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    when {
                        isEditingThisMessage -> MessageEditor(store, session, entry, editingContent)
                        uiState.isRawView -> RawContentView(entry.rawContent)
                        else -> ParsedContentView(entry.content)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageEditor(store: Store, session: Session, entry: LedgerEntry, editingContent: String?) {
    var text by remember(entry.id) { mutableStateOf(editingContent ?: entry.rawContent) }

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
                store.dispatch("session.ui", Action("session.SET_EDITING_MESSAGE", buildJsonObject {
                    put("messageId", null as String?)
                }))
            }) {
                Text("Cancel")
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                store.dispatch("session.ui", Action("session.UPDATE_MESSAGE", buildJsonObject {
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
private fun ParsedContentView(content: List<ContentBlock>) {
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