package app.auf.feature.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.auf.core.*
import app.auf.core.generated.ActionRegistry
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
    platformDependencies: PlatformDependencies,
    showSenderInfo: Boolean = true,
    senderColor: Color? = null
) {
    val uiState = remember(session.messageUiState, entry.id) {
        session.messageUiState[entry.id] ?: MessageUiState()
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    // Track dropdown open state so chrome stays visible when menu is open
    // (cursor moves to popup layer, losing hover on the card itself).
    var isMenuOpen by remember { mutableStateOf(false) }
    val showChrome = isHovered || isMenuOpen || isEditingThisMessage

    val accentColor = senderColor
        ?: if (isCurrentUserMessage) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant

    val senderNameColor = senderColor
        ?: if (isCurrentUserMessage) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface

    // Timestamp is always hover-only. showChrome gates all hover state.

    val collapsedPreview = remember(entry.content, entry.rawContent) {
        val firstBlock = entry.content.firstOrNull()
        val hasMultipleBlocks = entry.content.size > 1
        when (firstBlock) {
            is ContentBlock.Text -> {
                val firstLine = firstBlock.text.lineSequence().firstOrNull() ?: ""
                val hasMoreLines = firstBlock.text.contains('\n')
                val truncated = firstLine.length > 80 || hasMoreLines || hasMultipleBlocks
                firstLine.take(80) + if (truncated) "…" else ""
            }
            is ContentBlock.CodeBlock -> if (firstBlock.language.startsWith("auf_")) {
                "Action '${firstBlock.language.removePrefix("auf_")}'"
            } else {
                val firstLine = firstBlock.code.lineSequence().firstOrNull() ?: ""
                val hasMoreLines = firstBlock.code.contains('\n')
                val truncated = firstLine.length > 80 || hasMoreLines || hasMultipleBlocks
                firstLine.take(80) + if (truncated) "…" else ""
            }
            null -> {
                val raw = entry.rawContent ?: ""
                val firstLine = raw.lineSequence().firstOrNull() ?: ""
                val truncated = firstLine.length > 80 || raw.contains('\n')
                firstLine.take(80) + if (truncated) "…" else ""
            }
        }
    }

    val cardBgColor = MaterialTheme.colorScheme.surfaceContainerLow

    Card(
        modifier = Modifier.fillMaxWidth().hoverable(interactionSource),
        colors = CardDefaults.cardColors(containerColor = cardBgColor)
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // ── Left accent bar ──────────────────────────────────────────
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(accentColor)
            )

            Column(
                modifier = Modifier.weight(1f).padding(
                    start = 16.dp, end = 16.dp, bottom = 16.dp,
                    top = if (showSenderInfo) 0.dp else 8.dp
                )
            ) {
                if (showSenderInfo) {
                    // ── First-in-run: stable header, no pop ──────────────
                    // Box reserves 48dp so chrome overlay never changes height.
                    // Timestamp is always laid out; transparent when not hovered.
                    Box(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().clickable(
                                enabled = !isEditingThisMessage,
                                onClick = { dispatchToggleCollapsed(store, session, entry) }
                            )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = senderName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = senderNameColor
                                )
                                if (entry.isLocked) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = "Locked",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (uiState.isCollapsed && collapsedPreview.isNotBlank()) {
                                    Text(
                                        text = collapsedPreview,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                }
                            }
                            // Timestamp: always present for layout stability.
                            // Transparent when not hovered — no vertical pop.
                            Text(
                                text = platformDependencies.formatDisplayTimestamp(entry.timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (showChrome) MaterialTheme.colorScheme.onSurfaceVariant
                                else Color.Transparent
                            )
                        }

                        // Chrome overlay — floats top-right, no layout impact
                        if (showChrome) {
                            ChromeOverlay(store, session, entry, uiState, cardBgColor) { isMenuOpen = it }
                        }
                    }

                    // Content gap
                    Spacer(Modifier.height(8.dp))

                } else if (showChrome) {
                    // ── Consecutive on hover: full header pops in ────────
                    // This is the ONE allowed case of visual popping.
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().clickable(
                                enabled = !isEditingThisMessage,
                                onClick = { dispatchToggleCollapsed(store, session, entry) }
                            )
                        ) {
                            Text(
                                text = senderName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = senderNameColor
                            )
                            Text(
                                text = platformDependencies.formatDisplayTimestamp(entry.timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        ChromeOverlay(store, session, entry, uiState, cardBgColor) { isMenuOpen = it }
                    }
                }
                // ── Consecutive at rest: no header at all ────────────
                // (neither branch above matches — content starts directly)

                // ── Content ──────────────────────────────────────────────
                AnimatedVisibility(visible = !uiState.isCollapsed) {
                    SelectionContainer {
                        Column {
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
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Helpers
// ═══════════════════════════════════════════════════════════════════════════

private fun dispatchToggleCollapsed(store: Store, session: Session, entry: LedgerEntry) {
    store.dispatch("session", Action(
        ActionRegistry.Names.SESSION_TOGGLE_MESSAGE_COLLAPSED,
        buildJsonObject {
            put("sessionId", session.identity.localHandle)
            put("messageId", entry.id)
        }
    ))
}

/**
 * Hover-only action icons overlay. Designed to float inside a Box with
 * Alignment.TopEnd so it never shifts the base layer layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChromeOverlay(
    store: Store,
    session: Session,
    entry: LedgerEntry,
    uiState: MessageUiState,
    cardBgColor: Color,
    onMenuStateChanged: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.background(cardBgColor),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("Toggle Raw Content") } },
            state = remember { TooltipState() }
        ) {
            IconButton(onClick = {
                store.dispatch("session", Action(
                    ActionRegistry.Names.SESSION_TOGGLE_MESSAGE_RAW_VIEW,
                    buildJsonObject {
                        put("sessionId", session.identity.localHandle)
                        put("messageId", entry.id)
                    }
                ))
            }) {
                Icon(
                    imageVector = Icons.Default.Code,
                    contentDescription = "Toggle Raw Content",
                    tint = if (uiState.isRawView) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("Copy Message Content") } },
            state = remember { TooltipState() }
        ) {
            IconButton(
                onClick = {
                    entry.rawContent?.let {
                        store.dispatch("session", Action(
                            ActionRegistry.Names.CORE_COPY_TO_CLIPBOARD,
                            buildJsonObject { put("text", it) }
                        ))
                    }
                },
                enabled = entry.rawContent != null
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy Message Content",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        var showMenu by remember { mutableStateOf(false) }
        // Sync menu open/close to parent so hover chrome stays visible
        // while dropdown is open (cursor leaves the card for popup layer).
        LaunchedEffect(showMenu) { onMenuStateChanged(showMenu) }
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "More options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(if (entry.isLocked) "Unlock" else "Lock") },
                    onClick = {
                        store.dispatch("session", Action(
                            ActionRegistry.Names.SESSION_TOGGLE_MESSAGE_LOCKED,
                            buildJsonObject {
                                put("sessionId", session.identity.localHandle)
                                put("messageId", entry.id)
                            }
                        ))
                        showMenu = false
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = if (entry.isLocked) Icons.Default.LockOpen
                            else Icons.Default.Lock,
                            contentDescription = null
                        )
                    }
                )
                if (entry.rawContent != null) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = {
                            store.dispatch("session", Action(
                                ActionRegistry.Names.SESSION_SET_EDITING_MESSAGE,
                                buildJsonObject { put("messageId", entry.id) }
                            ))
                            showMenu = false
                        },
                        enabled = !entry.isLocked
                    )
                }
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        store.dispatch("session", Action(
                            ActionRegistry.Names.SESSION_DELETE_MESSAGE,
                            buildJsonObject {
                                put("session", session.identity.localHandle)
                                put("messageId", entry.id)
                            }
                        ))
                        showMenu = false
                    },
                    enabled = !entry.isLocked
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Message Editor
// ═══════════════════════════════════════════════════════════════════════════

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
                store.dispatch("session", Action(
                    ActionRegistry.Names.SESSION_SET_EDITING_MESSAGE,
                    buildJsonObject { put("messageId", null as String?) }
                ))
            }) {
                Text("Cancel")
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                store.dispatch("session", Action(
                    ActionRegistry.Names.SESSION_UPDATE_MESSAGE,
                    buildJsonObject {
                        put("session", session.identity.localHandle)
                        put("messageId", entry.id)
                        put("newContent", text)
                    }
                ))
            }) {
                Text("Save")
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Parsed Content View
// ═══════════════════════════════════════════════════════════════════════════

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
                    is ContentBlock.Text -> Text(
                        text = block.text,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    is ContentBlock.CodeBlock -> {
                        val isAufAction = block.language.startsWith("auf_")
                        val headerLabel = if (isAufAction) {
                            "Action '${block.language.removePrefix("auf_")}'"
                        } else {
                            block.language
                        }
                        val clipboardText = if (isAufAction) {
                            "```${block.language}\n${block.code}\n```"
                        } else {
                            block.code
                        }

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceDim,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
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
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                        IconButton(
                                            onClick = {
                                                store.dispatch("session", Action(
                                                    ActionRegistry.Names.CORE_COPY_TO_CLIPBOARD,
                                                    buildJsonObject { put("text", clipboardText) }
                                                ))
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = "Copy Code Block",
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                }
                                Text(
                                    text = block.code,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Raw Content View
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun RawContentView(rawContent: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceDim,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        Text(
            text = rawContent,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(8.dp)
        )
    }
}