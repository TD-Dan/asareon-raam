package app.auf.feature.session

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import app.auf.core.*
import app.auf.core.generated.ActionNames
import app.auf.feature.core.CoreState
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.launch
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Derives the ordered, optionally filtered list of sessions from the canonical sessionOrder.
 * Sessions not present in sessionOrder are appended at the end, sorted by createdAt descending.
 */
private fun deriveOrderedSessions(
    sessionsMap: Map<String, Session>,
    sessionOrder: List<String>,
    hideHidden: Boolean
): List<Session> {
    val ordered = sessionOrder.mapNotNull { sessionsMap[it] }
    val unordered = sessionsMap.values
        .filter { it.id !in sessionOrder }
        .sortedByDescending { it.createdAt }
    val all = ordered + unordered
    return if (hideHidden) all.filter { !it.isHidden } else all
}

@Composable
fun SessionView(store: Store, features: List<Feature>, platformDependencies: PlatformDependencies) {
    val appState by store.state.collectAsState()
    val sessionState = appState.featureStates["session"] as? SessionState
    val coreState = appState.featureStates["core"] as? CoreState

    val hideHidden = sessionState?.hideHiddenInViewer ?: true
    val sessions = remember(sessionState?.sessions, sessionState?.sessionOrder, hideHidden) {
        deriveOrderedSessions(
            sessionState?.sessions ?: emptyMap(),
            sessionState?.sessionOrder ?: emptyList(),
            hideHidden
        )
    }
    val activeSession = remember(sessionState?.activeSessionId, sessions) {
        sessions.find { it.id == sessionState?.activeSessionId }
    }
    val activeTabIndex = remember(activeSession, sessions) {
        sessions.indexOf(activeSession).coerceAtLeast(0)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (sessions.isNotEmpty()) {
                TabRow(selectedTabIndex = activeTabIndex, modifier = Modifier.weight(1f)) {
                    sessions.forEach { session ->
                        SessionTab(store, session, session.id == activeSession?.id, session.id == sessionState?.editingSessionId)
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            // Hidden filter toggle
            IconButton(onClick = { store.dispatch("session.ui", Action(ActionNames.SESSION_TOGGLE_HIDE_HIDDEN_IN_VIEWER)) }) {
                Icon(
                    imageVector = if (hideHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (hideHidden) "Show Hidden Sessions" else "Hide Hidden Sessions",
                    tint = if (hideHidden) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { store.dispatch("session.ui", Action(ActionNames.SESSION_CREATE)) }) {
                Icon(Icons.Default.Add, "New Session")
            }
        }

        if (activeSession == null) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No active session. Create one to begin.") }
        } else {
            LedgerPane(store, activeSession, sessionState, coreState, features, platformDependencies, Modifier.weight(1f))
            MessageInput(store, activeSession, platformDependencies) { message ->
                val activeUserId = coreState?.activeUserId ?: "user"
                store.dispatch("session.ui", Action(ActionNames.SESSION_POST, buildJsonObject {
                    put("session", activeSession.id); put("senderId", activeUserId); put("message", message)
                }))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionTab(store: Store, session: Session, isSelected: Boolean, isEditing: Boolean) {
    if (isEditing) {
        var text by remember(session.id) { mutableStateOf(session.name) }
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            modifier = Modifier.padding(4.dp).onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.Enter -> {
                            store.dispatch("session.ui", Action(ActionNames.SESSION_UPDATE_CONFIG, buildJsonObject {
                                put("session", session.id); put("name", text)
                            }))
                            return@onKeyEvent true
                        }
                        Key.Escape -> {
                            store.dispatch("session.ui", Action(ActionNames.SESSION_SET_EDITING_SESSION_NAME, buildJsonObject {
                                put("sessionId", null as String?)
                            }))
                            return@onKeyEvent true
                        }
                        else -> {}
                    }
                }
                false
            }, singleLine = true, textStyle = MaterialTheme.typography.labelLarge
        )
    } else {
        // Dim hidden sessions when visible (filter is off)
        val tabTextColor = if (session.isHidden) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else LocalContentColor.current
        Tab(
            selected = isSelected,
            onClick = { store.dispatch("session.ui", Action(ActionNames.SESSION_SET_ACTIVE_TAB, buildJsonObject { put("session", session.id) })) },
            modifier = Modifier.combinedClickable(
                onClick = { store.dispatch("session.ui", Action(ActionNames.SESSION_SET_ACTIVE_TAB, buildJsonObject { put("session", session.id) })) },
                onDoubleClick = { store.dispatch("session.ui", Action(ActionNames.SESSION_SET_EDITING_SESSION_NAME, buildJsonObject { put("sessionId", session.id) })) }
            )
        ) { Text(session.name, maxLines = 1, modifier = Modifier.padding(vertical = 12.dp), color = tabTextColor) }
    }
}

@Composable
private fun LedgerPane(
    store: Store,
    activeSession: Session,
    sessionState: SessionState?,
    coreState: CoreState?,
    features: List<Feature>,
    platformDependencies: PlatformDependencies,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val identityNames = sessionState?.identityNames ?: emptyMap()
    val activeUserId = coreState?.activeUserId

    val agentFeature = remember(features) { features.find { it.name == "agent" } }

    LaunchedEffect(activeSession.ledger.size) {
        if (activeSession.ledger.size > 1) {
            coroutineScope.launch {
                listState.animateScrollToItem(activeSession.ledger.size)
            }
        }
    }

    LazyColumn(state = listState, modifier = modifier.fillMaxSize().padding(8.dp)) {
        items(activeSession.ledger, key = { it.id }) { entry ->
            val isPartialView = entry.metadata?.get("render_as_partial")?.jsonPrimitive?.booleanOrNull ?: false

            if (isPartialView && agentFeature != null) {
                agentFeature.composableProvider?.PartialView(store, "agent.avatar", entry.senderId)
            } else {
                val senderName = remember(entry.senderId, identityNames) {
                    identityNames[entry.senderId] ?: entry.senderId
                }
                val isCurrentUserMessage = entry.senderId == activeUserId
                LedgerEntryCard(
                    store = store,
                    session = activeSession,
                    entry = entry,
                    senderName = senderName,
                    isCurrentUserMessage = isCurrentUserMessage,
                    isEditingThisMessage = sessionState?.editingMessageId == entry.id,
                    editingContent = sessionState?.editingMessageContent,
                    platformDependencies = platformDependencies
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MessageInput(store: Store, activeSession: Session, platformDependencies: PlatformDependencies, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxWidth(), shadowElevation = 8.dp) {
        Row(Modifier.padding(8.dp), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f).onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && (event.isCtrlPressed || event.isMetaPressed)) {
                        if (text.isNotBlank()) { onSend(text); text = "" }
                        return@onKeyEvent true
                    }
                    false
                },
                placeholder = { Text("Enter message (Ctrl+Enter to send)...") }
            )
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, "More options")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Copy Transcript") },
                        onClick = {
                            val transcript = activeSession.ledger.joinToString("\n\n") { entry ->
                                val timestamp = platformDependencies.formatIsoTimestamp(entry.timestamp)
                                val senderName = (store.state.value.featureStates["session"] as? SessionState)?.identityNames?.get(entry.senderId) ?: entry.senderId
                                "$senderName @ $timestamp:\n${entry.rawContent}"
                            }
                            store.dispatch("ui.session.input", Action(ActionNames.CORE_COPY_TO_CLIPBOARD, buildJsonObject { put("text", transcript) }))
                            menuExpanded = false
                        },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Clear Session") },
                        onClick = {
                            store.dispatch("session.ui", Action(ActionNames.SESSION_CLEAR, buildJsonObject { put("session", activeSession.id) }))
                            menuExpanded = false
                        },
                        leadingIcon = { Icon(Icons.Default.ClearAll, null) }
                    )
                }
            }
            IconButton(onClick = { if (text.isNotBlank()) { onSend(text); text = "" } }, enabled = text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send")
            }
        }
    }
}