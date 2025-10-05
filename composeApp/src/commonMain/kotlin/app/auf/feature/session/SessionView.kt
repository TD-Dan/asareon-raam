package app.auf.feature.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.auf.core.Action
import app.auf.core.Store
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun SessionView(store: Store) {
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
        if (sessions.isNotEmpty()) {
            TabRow(selectedTabIndex = activeTabIndex) {
                sessions.forEach { session ->
                    Tab(
                        selected = session.id == activeSession?.id,
                        onClick = {
                            val payload = buildJsonObject { put("sessionId", session.id) }
                            store.dispatch("session.ui", Action("session.SET_ACTIVE_TAB", payload))
                        },
                        text = { Text(session.name, maxLines = 1) }
                    )
                }
            }
        }

        if (activeSession == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No active session. Go to the Session Manager to create or select one.")
            }
        } else {
            LedgerPane(
                ledger = activeSession.ledger,
                modifier = Modifier.weight(1f)
            )
            MessageInput(
                onSend = { message ->
                    val payload = buildJsonObject {
                        put("sessionId", activeSession.id)
                        put("agentId", "user")
                        put("message", message)
                    }
                    store.dispatch("session.ui", Action("session.POST", payload))
                }
            )
        }
    }
}

@Composable
private fun LedgerPane(ledger: List<LedgerEntry>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(ledger.size) {
        if (ledger.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(ledger.lastIndex)
            }
        }
    }

    LazyColumn(state = listState, modifier = modifier.padding(8.dp)) {
        items(ledger, key = { it.id }) { entry ->
            LedgerEntryCard(entry)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun LedgerEntryCard(entry: LedgerEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (entry.agentId == "user") {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = entry.agentId,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    entry.content.forEach { block ->
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
                modifier = Modifier.weight(1f),
                placeholder = { Text("Enter message...") }
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