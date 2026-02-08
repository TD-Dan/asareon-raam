package app.auf.feature.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
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
import app.auf.util.PlatformDependencies
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerEntryCard(
    store: Store,
    session: Session,
    entry: LedgerEntry,
    senderName: String,
    isCurrentUserMessage: Boolean,
    isEditingThisMessage: Boolean,
    editingContent: String?,
    platformDependencies: PlatformDependencies
) {
    val uiState = remember(session.messageUiState, entry.id) {
        session.messageUiState[entry.id] ?: MessageUiState()
    }
    var showMenu by remember { mutableStateOf(false) }

    val cardColors = if (isCurrentUserMessage) {
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
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = senderName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    // Display the formatted timestamp
                    Text(
                        text = platformDependencies.formatDisplayTimestamp(entry.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Lock indicator
                    if (entry.isLocked) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Locked",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text("Toggle Raw Content") } },
                        state = remember { TooltipState() }
                    ) {
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
                    }
                    Spacer(Modifier.width(4.dp))
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text("Copy Message Content") } },
                        state = remember { TooltipState() }
                    ) {
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
                    }
                    Spacer(Modifier.width(4.dp))
                    Box {
                        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            // Lock / Unlock
                            DropdownMenuItem(
                                text = { Text(if (entry.isLocked) "Unlock" else "Lock") },
                                onClick = {
                                    store.dispatch("session.ui", Action(ActionNames.SESSION_TOGGLE_MESSAGE_LOCKED, buildJsonObject {
                                        put("sessionId", session.id); put("messageId", entry.id)
                                    }))
                                    showMenu = false
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (entry.isLocked) Icons.Default.LockOpen else Icons.Default.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )
                            // Edit (disabled when locked)
                            if (entry.rawContent != null) {
                                DropdownMenuItem(
                                    text = { Text("Edit") },
                                    onClick = {
                                        store.dispatch("session.ui", Action(ActionNames.SESSION_SET_EDITING_MESSAGE, buildJsonObject {
                                            put("messageId", entry.id)
                                        }))
                                        showMenu = false
                                    },
                                    enabled = !entry.isLocked
                                )
                            }
                            // Delete (disabled when locked)
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = {
                                    store.dispatch("session.ui", Action(ActionNames.SESSION_DELETE_MESSAGE, buildJsonObject {
                                        put("session", session.id); put("messageId", entry.id)
                                    }))
                                    showMenu = false
                                },
                                enabled = !entry.isLocked
                            )
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
                        else -> ParsedContentView(store, entry.content, entry.rawContent)
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
private fun ParsedContentView(store: Store, content: List<ContentBlock>, rawContent: String?) {
    if (content.isEmpty()) {
        if (!rawContent.isNullOrBlank()) {
            RawContentView(rawContent)
        } else {
            Text(" ", style = MaterialTheme.typography.bodySmall)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content.forEach { block ->
                when (block) {
                    is ContentBlock.Text -> Text(block.text)
                    is ContentBlock.CodeBlock -> {
                        val isAufAction = block.language.startsWith("auf_")
                        val backgroundColor = if (isAufAction) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                        val headerLabel = if (isAufAction) {
                            // "auf_session.POST" → "Action 'session.POST'"
                            "Action '${block.language.removePrefix("auf_")}'"
                        } else {
                            block.language
                        }
                        val clipboardText = if (isAufAction) {
                            // Copy the full fenced code block as-is
                            "```${block.language}\n${block.code}\n```"
                        } else {
                            // Copy just the code content
                            block.code
                        }

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = backgroundColor,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                // Header row: label + copy button
                                if (headerLabel.isNotBlank()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = headerLabel,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isAufAction) {
                                                MaterialTheme.colorScheme.onSecondaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                        IconButton(
                                            onClick = {
                                                store.dispatch("session.ui", Action(ActionNames.CORE_COPY_TO_CLIPBOARD, buildJsonObject {
                                                    put("text", clipboardText)
                                                }))
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = "Copy Code Block",
                                                modifier = Modifier.size(16.dp),
                                                tint = if (isAufAction) {
                                                    MaterialTheme.colorScheme.onSecondaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                }
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                }
                                // Code content
                                Text(
                                    text = block.code,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
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
        //TODO: change this to a monospace font
        Text(
            text = rawContent,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(8.dp)
        )
    }
}