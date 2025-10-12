package app.auf.feature.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.auf.core.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentManagerView(store: Store) {
    val appState by store.state.collectAsState()
    val agentState = remember(appState.featureStates) {
        appState.featureStates["agent"] as? AgentRuntimeState
    }
    var agentToDelete by remember { mutableStateOf<AgentInstance?>(null) }
    val editingAgentId = agentState?.editingAgentId

    LaunchedEffect(Unit) {
        store.dispatch("ui.agentManager", Action("gateway.REQUEST_AVAILABLE_MODELS"))
    }

    agentToDelete?.let { agent ->
        AlertDialog(
            onDismissRequest = { agentToDelete = null },
            title = { Text("Delete Agent?") },
            text = { Text("Are you sure you want to permanently delete '${agent.name}'? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        store.dispatch("ui.agentManager", Action("agent.DELETE", buildJsonObject { put("agentId", agent.id) }))
                        agentToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { Button(onClick = { agentToDelete = null }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent Manager") },
                actions = {
                    Button(onClick = {
                        store.dispatch("ui.agentManager", Action("agent.CREATE", buildJsonObject {
                            put("name", "New Agent")
                            put("personaId", "keel-20250914T142800Z")
                            put("modelProvider", "gemini")
                            put("modelName", "gemini-2.5-pro")
                            put("primarySessionId", null as String?)
                        }))
                    }) {
                        Icon(Icons.Default.Add, "Create New Agent"); Spacer(Modifier.width(8.dp)); Text("New Agent")
                    }
                }
            )
        }
    ) { paddingValues ->
        if (agentState == null || agentState.agents.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(paddingValues), Alignment.Center) { Text("No agents configured.") }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(agentState.agents.values.toList(), key = { it.id }) { agent ->
                    AgentCard(
                        agent = agent,
                        isEditing = agent.id == editingAgentId,
                        agentState = agentState,
                        store = store,
                        onDeleteRequest = { agentToDelete = it },
                        onEditRequest = { store.dispatch("ui.agentManager", Action("agent.SET_EDITING", buildJsonObject { put("agentId", agent.id) })) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentCard(
    agent: AgentInstance,
    isEditing: Boolean,
    agentState: AgentRuntimeState,
    store: Store,
    onDeleteRequest: (AgentInstance) -> Unit,
    onEditRequest: () -> Unit
) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(Modifier.padding(16.dp), Arrangement.spacedBy(12.dp)) {
            if (isEditing) {
                AgentEditorView(agent, agentState, store)
            } else {
                AgentReadOnlyView(agent, agentState.sessionNames)
            }

            Row(Modifier.fillMaxWidth(), Arrangement.End, Alignment.CenterVertically) {
                if (!isEditing) {
                    IconButton(onClick = onEditRequest) {
                        Icon(Icons.Default.Edit, "Edit Agent")
                    }
                }
                IconButton(onClick = { onDeleteRequest(agent) }) {
                    Icon(Icons.Default.Delete, "Delete Agent")
                }
            }
        }
    }
}

@Composable
private fun AgentReadOnlyView(agent: AgentInstance, sessionNames: Map<String, String>) {
    val sessionName = agent.primarySessionId?.let { sessionNames[it] } ?: "Not Subscribed"
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(agent.name, style = MaterialTheme.typography.titleLarge)
            Text("Session: $sessionName", style = MaterialTheme.typography.bodyMedium)
            Text("Model: ${agent.modelProvider}/${agent.modelName}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentEditorView(
    agent: AgentInstance,
    agentState: AgentRuntimeState,
    store: Store
) {
    var agentNameInput by remember(agent.id) { mutableStateOf(agent.name) }
    val onSave = {
        store.dispatch("ui.agentManager", Action("agent.UPDATE_CONFIG", buildJsonObject {
            put("agentId", agent.id)
            put("name", agentNameInput)
        }))
        store.dispatch("ui.agentManager", Action("agent.SET_EDITING", buildJsonObject { put("agentId", null as String?) }))
    }
    val onCancel = {
        store.dispatch("ui.agentManager", Action("agent.SET_EDITING", buildJsonObject { put("agentId", null as String?) }))
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            OutlinedTextField(
                value = agentNameInput,
                onValueChange = { agentNameInput = it },
                label = { Text("Agent Name") },
                modifier = Modifier.weight(1f)
            )
            Row {
                IconButton(onClick = onCancel) { Icon(Icons.Default.Cancel, "Cancel Edit") }
                IconButton(onClick = onSave, enabled = agentNameInput.isNotBlank()) { Icon(Icons.Default.Save, "Save Name") }
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 3
        ) {
            Box(Modifier.weight(1f)) { SessionSelector(agent, agentState, store) }
            Box(Modifier.weight(1f)) { ProviderSelector(agent, agentState, store) }
            Box(Modifier.weight(1f)) { ModelSelector(agent, agentState, store) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionSelector(agent: AgentInstance, agentState: AgentRuntimeState, store: Store) {
    val availableSessions = remember(agentState.sessionNames) {
        agentState.sessionNames.entries.toList()
    }
    var isExpanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = isExpanded, onExpandedChange = { isExpanded = !isExpanded }) {
        OutlinedTextField(
            value = agentState.sessionNames[agent.primarySessionId] ?: "Not Subscribed",
            onValueChange = {}, readOnly = true, label = { Text("Session") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(isExpanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = isExpanded, onDismissRequest = { isExpanded = false }) {
            DropdownMenuItem(text = { Text("None (Unsubscribe)") }, onClick = {
                store.dispatch("ui.agentManager", Action("agent.UPDATE_CONFIG", buildJsonObject {
                    put("agentId", agent.id); put("primarySessionId", null as String?)
                }))
                isExpanded = false
            })
            availableSessions.forEach { (sessionId, sessionName) ->
                DropdownMenuItem(text = { Text(sessionName) }, onClick = {
                    store.dispatch("ui.agentManager", Action("agent.UPDATE_CONFIG", buildJsonObject {
                        put("agentId", agent.id); put("primarySessionId", sessionId)
                    }))
                    isExpanded = false
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderSelector(agent: AgentInstance, agentState: AgentRuntimeState, store: Store) {
    val availableProviders = remember(agentState.availableModels) { agentState.availableModels.keys.toList() }
    var isExpanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = isExpanded, onExpandedChange = { isExpanded = !isExpanded }) {
        OutlinedTextField(
            value = agent.modelProvider,
            onValueChange = {}, readOnly = true, label = { Text("Provider") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(isExpanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = isExpanded, onDismissRequest = { isExpanded = false }) {
            availableProviders.forEach { providerId ->
                DropdownMenuItem(text = { Text(providerId) }, onClick = {
                    store.dispatch("ui.agentManager", Action("agent.UPDATE_CONFIG", buildJsonObject {
                        put("agentId", agent.id)
                        put("modelProvider", providerId)
                        put("modelName", agentState.availableModels[providerId]?.firstOrNull())
                    }))
                    isExpanded = false
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelector(agent: AgentInstance, agentState: AgentRuntimeState, store: Store) {
    val availableModels = remember(agentState.availableModels, agent.modelProvider) {
        agentState.availableModels[agent.modelProvider] ?: emptyList()
    }
    var isExpanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = isExpanded, onExpandedChange = { isExpanded = !isExpanded }) {
        OutlinedTextField(
            value = agent.modelName,
            onValueChange = {}, readOnly = true, label = { Text("Model") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(isExpanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            enabled = availableModels.isNotEmpty()
        )
        ExposedDropdownMenu(expanded = isExpanded, onDismissRequest = { isExpanded = false }) {
            availableModels.forEach { modelName ->
                DropdownMenuItem(text = { Text(modelName) }, onClick = {
                    store.dispatch("ui.agentManager", Action("agent.UPDATE_CONFIG", buildJsonObject {
                        put("agentId", agent.id); put("modelName", modelName)
                    }))
                    isExpanded = false
                })
            }
        }
    }
}