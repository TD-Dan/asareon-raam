package app.auf.feature.session

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.auf.core.Action
import app.auf.core.Store
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun SessionsManagerView(store: Store) {
    val appState by store.state.collectAsState()
    val sessionState = appState.featureStates["session"] as? SessionState
    val sessions = remember(sessionState?.sessions) {
        sessionState?.sessions?.values?.sortedByDescending { it.createdAt } ?: emptyList()
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // --- Header ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Session Manager", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = { store.dispatch("session.ui", Action("session.CREATE")) }) {
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
                    SessionManagerCard(session, store)
                }
            }
        }
    }
}

@Composable
private fun SessionManagerCard(session: Session, store: Store) {
    val payload = remember(session.id) {
        buildJsonObject { put("sessionId", session.id) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(session.name, style = MaterialTheme.typography.titleMedium)
                Text("ID: ${session.id}", style = MaterialTheme.typography.bodySmall)
                Text("Messages: ${session.ledger.size}", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { store.dispatch("session.ui", Action("session.DELETE", payload)) }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete Session")
            }
        }
    }
}