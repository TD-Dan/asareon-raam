package app.auf.feature.session

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import app.auf.core.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun SessionView(store: Store, features: List<Feature>) {
    val appState by store.state.collectAsState()
    val sessionState = appState.featureStates["session"] as? SessionState

    val sessions = remember(sessionState?.sessions) {
        sessionState?.sessions?.values?.sortedByDescending { it.createdAt } ?: emptyList()
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
            IconButton(onClick = { store.dispatch("session.ui", Action("session.CREATE")) }) {
                Icon(Icons.Default.Add, "New Session")
            }
        }

        if (activeSession == null) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No active session. Create one to begin.") }
        } else {
            LedgerPane(store, activeSession, sessionState, features, Modifier.weight(1f))
            MessageInput { message ->
                store.dispatch("session.ui", Action("session.POST", buildJsonObject {
                    put("session", activeSession.id); put("agentId", "user"); put("message", message)
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
                            store.dispatch("session.ui", Action("session.UPDATE_CONFIG", buildJsonObject {
                                put("session", session.id); put("name", text)
                            }))
                            return@onKeyEvent true
                        }
                        Key.Escape -> {
                            store.dispatch("session.ui", Action("session.SET_EDITING_SESSION_NAME", buildJsonObject {
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
        Tab(
            selected = isSelected,
            onClick = { store.dispatch("session.ui", Action("session.SET_ACTIVE_TAB", buildJsonObject { put("session", session.id) })) },
            modifier = Modifier.combinedClickable(
                onClick = { store.dispatch("session.ui", Action("session.SET_ACTIVE_TAB", buildJsonObject { put("session", session.id) })) },
                onDoubleClick = { store.dispatch("session.ui", Action("session.SET_EDITING_SESSION_NAME", buildJsonObject { put("sessionId", session.id) })) }
            )
        ) { Text(session.name, maxLines = 1, modifier = Modifier.padding(vertical = 12.dp)) }
    }
}

@Composable
private fun LedgerPane(
    store: Store,
    activeSession: Session,
    sessionState: SessionState?,
    features: List<Feature>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val agentNames = sessionState?.agentNames ?: emptyMap()
    val lastAgentId = activeSession.ledger.lastOrNull()?.agentId?.takeIf { it != "user" && !it.startsWith("system") }

    LaunchedEffect(activeSession.ledger.size) {
        if (activeSession.ledger.size > 1) {
            coroutineScope.launch {
                listState.animateScrollToItem(activeSession.ledger.size)
            }
        }
    }

    Column(modifier = modifier) {
        LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(8.dp)) {
            items(activeSession.ledger, key = { it.id }) { entry ->
                val agentName = remember(entry.agentId, agentNames) {
                    when {
                        entry.agentId == "user" -> "User"
                        entry.agentId.startsWith("system") -> "System"
                        else -> agentNames[entry.agentId] ?: entry.agentId // Fallback to ID
                    }
                }
                LedgerEntryCard(store, activeSession, entry, agentName, sessionState?.editingMessageId == entry.id, sessionState?.editingMessageContent)
                Spacer(Modifier.height(8.dp))
            }
        }
        if (lastAgentId != null) {
            Box(modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 8.dp)) {
                features.forEach { feature ->
                    feature.composableProvider?.PartialView(store = store, partId = lastAgentId, context = activeSession)
                }
            }
        }
    }
}

@Composable
private fun MessageInput(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
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
            IconButton(onClick = { if (text.isNotBlank()) { onSend(text); text = "" } }, enabled = text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send")
            }
        }
    }
}