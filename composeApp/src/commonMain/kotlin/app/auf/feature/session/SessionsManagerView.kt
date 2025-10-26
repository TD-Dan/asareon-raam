package app.auf.feature.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.auf.core.*
import app.auf.core.generated.ActionNames
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun SessionsManagerView(store: Store) {
    val appState by store.state.collectAsState()
    val sessionState = appState.featureStates["session"] as? SessionState
    val sessions = remember(sessionState?.sessions) {
        sessionState?.sessions?.values?.sortedByDescending { it.createdAt } ?: emptyList()
    }
    val editingSessionId = sessionState?.editingSessionId

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // --- Header ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Session Manager", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = { store.dispatch("session.ui", Action(ActionNames.SESSION_CREATE)) }) {
                Icon(Icons.Default.Add, contentDescription = "Create New Session", modifier = Modifier.padding(end = 8.dp))
                Text("New Session")
            }
        }
        HorizontalDivider()

        // --- Session List ---
        if (sessions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No sessions found. Create one to begin.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sessions, key = { it.id }) { session ->
                    if (session.id == editingSessionId) {
                        SessionManagerCardEditor(session, store)
                    } else {
                        SessionManagerCard(session, store)
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionManagerCard(session: Session, store: Store) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(session.name, style = MaterialTheme.typography.titleMedium)
                Text("ID: ${session.id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Messages: ${session.ledger.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Edit Button
            IconButton(onClick = {
                val payload = buildJsonObject { put("sessionId", session.id) }
                store.dispatch("session.ui", Action(ActionNames.SESSION_SET_EDITING_SESSION_NAME, payload))
            }) {
                Icon(Icons.Default.Edit, contentDescription = "Edit Session Name")
            }
            // ADDITION: Clone Button
            IconButton(onClick = {
                store.dispatch("session.ui", Action(ActionNames.SESSION_CLONE, buildJsonObject { put("session", session.id) }))
            }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Clone Session")
            }
            // Delete Button
            IconButton(onClick = {
                val payload = buildJsonObject { put("session", session.id) }
                store.dispatch("session.ui", Action(ActionNames.SESSION_DELETE, payload))
            }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete Session")
            }
        }
    }
}

@Composable
private fun SessionManagerCardEditor(session: Session, store: Store) {
    var name by remember(session.id) { mutableStateOf(session.name) }

    val cancelAction = {
        val payload = buildJsonObject { put("sessionId", null as String?) }
        store.dispatch("session.ui", Action(ActionNames.SESSION_SET_EDITING_SESSION_NAME, payload))
    }
    val saveAction = {
        val payload = buildJsonObject {
            put("session", session.id)
            put("name", name)
        }
        store.dispatch("session.ui", Action(ActionNames.SESSION_UPDATE_CONFIG, payload))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Session Name") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = cancelAction) {
                    Icon(Icons.Default.Cancel, "Cancel Edit")
                }
                IconButton(onClick = saveAction) {
                    Icon(Icons.Default.Save, "Save Name")
                }
            }
        }
    }
}