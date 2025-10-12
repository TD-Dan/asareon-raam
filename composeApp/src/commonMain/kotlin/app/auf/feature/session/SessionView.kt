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
import app.auf.core.Action
import app.auf.core.Store
import app.auf.feature.agent.AgentState
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun SessionView(store: Store) {
    val appState by store.state.collectAsState()
    val sessionState = appState.featureStates["session"] as? SessionState
    // This is the correct integration point: The top-level view can access multiple states.
    val agentState = appState.featureStates["agent"] as? AgentState

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
        // --- Tab Bar and New Session Button ---
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (sessions.isNotEmpty()) {
                TabRow(
                    selectedTabIndex = activeTabIndex,
                    modifier = Modifier.weight(1f)
                ) {
                    sessions.forEach { session ->
                        SessionTab(
                            store = store,
                            session = session,
                            isSelected = session.id == activeSession?.id,
                            isEditing = session.id == sessionState?.editingSessionId
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f)) // Fills space if no tabs
            }

            IconButton(onClick = { store.dispatch("session.ui", Action("session.CREATE")) }) {
                Icon(Icons.Default.Add, contentDescription = "New Session")
            }
        }

        // --- Main Content ---
        if (activeSession == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No active session. Create one to begin.")
            }
        } else {
            LedgerPane(
                store = store,
                activeSession = activeSession,
                sessionState = sessionState,
                agentState = agentState, // Pass the agent state down
                modifier = Modifier.weight(1f)
            )
            MessageInput(
                onSend = { message ->
                    val payload = buildJsonObject {
                        put("session", activeSession.id)
                        put("agentId", "user")
                        put("message", message)
                    }
                    store.dispatch("session.ui", Action("session.POST", payload))
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionTab(store: Store, session: Session, isSelected: Boolean, isEditing: Boolean) {
    if (isEditing) {
        var text by remember(session.id) { mutableStateOf(session.name) }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.padding(4.dp).onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                    store.dispatch("session.ui", Action("session.UPDATE_CONFIG", buildJsonObject {
                        put("session", session.id)
                        put("name", text)
                    }))
                    return@onKeyEvent true
                }
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    store.dispatch("session.ui", Action("session.SET_EDITING_SESSION_NAME", buildJsonObject {
                        put("sessionId", null as String?)
                    }))
                    return@onKeyEvent true
                }
                false
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.labelLarge
        )
    } else {
        Tab(
            selected = isSelected,
            onClick = {
                val payload = buildJsonObject { put("session", session.id) }
                store.dispatch("session.ui", Action("session.SET_ACTIVE_TAB", payload))
            },
            modifier = Modifier.combinedClickable(
                onClick = {
                    val payload = buildJsonObject { put("session", session.id) }
                    store.dispatch("session.ui", Action("session.SET_ACTIVE_TAB", payload))
                },
                onDoubleClick = {
                    val payload = buildJsonObject { put("sessionId", session.id) }
                    store.dispatch("session.ui", Action("session.SET_EDITING_SESSION_NAME", payload))
                }
            )
        ) {
            Text(session.name, maxLines = 1, modifier = Modifier.padding(vertical = 12.dp))
        }
    }
}


@Composable
private fun LedgerPane(
    store: Store,
    activeSession: Session,
    sessionState: SessionState?,
    agentState: AgentState?, // Accept agent state
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(activeSession.ledger.size) {
        if (activeSession.ledger.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(activeSession.ledger.lastIndex)
            }
        }
    }

    LazyColumn(state = listState, modifier = modifier.padding(8.dp)) {
        items(activeSession.ledger, key = { it.id }) { entry ->
            // Perform the lookup here and pass the resulting string down.
            val agentName = remember(entry.agentId, agentState?.agents) {
                when {
                    entry.agentId == "user" -> "User"
                    entry.agentId.startsWith("system") -> "System"
                    else -> agentState?.agents?.get(entry.agentId)?.name ?: entry.agentId
                }
            }

            LedgerEntryCard(
                store = store,
                session = activeSession,
                entry = entry,
                agentName = agentName, // Pass the simple string
                isEditingThisMessage = sessionState?.editingMessageId == entry.id,
                editingContent = sessionState?.editingMessageContent
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MessageInput(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .weight(1f)
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && event.isCtrlPressed) {
                            if (text.isNotBlank()) {
                                onSend(text)
                                text = ""
                            }
                            return@onKeyEvent true
                        }
                        false
                    },
                placeholder = { Text("Enter message (Ctrl+Enter to send)...") }
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onSend(text)
                        text = ""
                    }
                },
                enabled = text.isNotBlank()
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}