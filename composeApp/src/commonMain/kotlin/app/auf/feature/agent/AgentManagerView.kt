package app.auf.feature.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.auf.core.Action
import app.auf.core.Store
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentManagerView(store: Store) {
    val appState by store.state.collectAsState()
    val agentState = remember(appState.featureStates) {
        appState.featureStates["agent"] as? AgentRuntimeState
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent Manager") },
                actions = {
                    IconButton(onClick = {
                        // For this minimal implementation, we create a hardcoded dummy agent.
                        // A future implementation would use a dialog to get these details.
                        val payload = buildJsonObject {
                            put("name", "My First Agent")
                            put("personaId", "keel-20250914T142800Z") // Dummy value
                            put("modelProvider", "gemini")
                            put("modelName", "gemini-2.5-pro") // A sensible default
                            put("primarySessionId", null as String?) // User will need to configure this
                        }
                        store.dispatch("ui.agentManager", Action("agent.CREATE", payload))
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "Create New Agent")
                    }
                }
            )
        }
    ) { paddingValues ->
        if (agentState == null || agentState.agents.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                Text("No agents configured. Click '+' to create one.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(agentState.agents.values.toList()) { agent ->
                    AgentCard(agent, store)
                }
            }
        }
    }
}

@Composable
private fun AgentCard(agent: AgentInstance, store: Store) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(agent.name, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("Model: ${agent.modelProvider}/${agent.modelName}", style = MaterialTheme.typography.bodySmall)
            Text("Session: ${agent.primarySessionId ?: "Not Subscribed"}", style = MaterialTheme.typography.bodySmall)
            Text("Status: ${agent.status}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        val payload = buildJsonObject { put("agentId", agent.id) }
                        store.dispatch("ui.agentManager", Action("agent.DELETE", payload))
                    },
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Agent")
                }

                Spacer(Modifier.width(8.dp))

                // The manual trigger button, which is disabled if the agent is busy.
                Button(
                    onClick = {
                        val payload = buildJsonObject { put("agentId", agent.id) }
                        store.dispatch("ui.agentManager", Action("agent.TRIGGER_MANUAL_TURN", payload))
                    },
                    // As per our hardened plan, only IDLE agents can be triggered.
                    // A 'reset' from ERROR state can be added later.
                    enabled = agent.status == AgentStatus.IDLE
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Trigger Turn")
                    Spacer(Modifier.width(8.dp))
                    Text("Trigger Turn")
                }
            }
        }
    }
}