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
import app.auf.core.generated.ActionNames
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentManagerView(store: Store, platformDependencies: app.auf.util.PlatformDependencies) {
    val appState by store.state.collectAsState()
    val agentState = remember(appState.featureStates) {
        appState.featureStates["agent"] as? AgentRuntimeState
    }
    var agentToDelete by remember { mutableStateOf<AgentInstance?>(null) }
    val editingAgentId = agentState?.editingAgentId

    LaunchedEffect(Unit) {
        store.dispatch("ui.agentManager", Action(ActionNames.GATEWAY_REQUEST_AVAILABLE_MODELS))
    }

    agentToDelete?.let { agent ->
        AlertDialog(
            onDismissRequest = { agentToDelete = null },
            title = { Text("Delete Agent?") },
            text = { Text("Are you sure you want to permanently delete '${agent.name}'? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        store.dispatch("ui.agentManager", Action(ActionNames.AGENT_DELETE, buildJsonObject { put("agentId", agent.id) }))
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
                        store.dispatch("ui.agentManager", Action(ActionNames.AGENT_CREATE, buildJsonObject {
                            put("name", "New Agent")
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
                        onEditRequest = { store.dispatch("ui.agentManager", Action(ActionNames.AGENT_SET_EDITING, buildJsonObject { put("agentId", agent.id) })) },
                        platformDependencies = platformDependencies
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
    onEditRequest: () -> Unit,
    platformDependencies: app.auf.util.PlatformDependencies
) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(Modifier.padding(16.dp), Arrangement.spacedBy(12.dp)) {
            if (isEditing) {
                AgentEditorView(agent, agentState, store)
            } else {
                AgentReadOnlyView(agent, agentState, store, platformDependencies)
            }

            HorizontalDivider()
            if (!isEditing) {
                Row(Modifier.fillMaxWidth(), Arrangement.End, Alignment.CenterVertically) {
                    IconButton(onClick = onEditRequest) {
                        Icon(Icons.Default.Edit, "Edit Agent")
                    }
                    IconButton(onClick = { store.dispatch("ui.agentManager", Action(ActionNames.AGENT_CLONE, buildJsonObject { put("agentId", agent.id) })) }) {
                        Icon(Icons.Default.ContentCopy, "Clone Agent")
                    }
                    IconButton(onClick = { onDeleteRequest(agent) }) {
                        Icon(Icons.Default.Delete, "Delete Agent")
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentReadOnlyView(
    agent: AgentInstance,
    agentState: AgentRuntimeState,
    store: Store,
    platformDependencies: app.auf.util.PlatformDependencies
) {
    val sessionName = agent.subscribedSessionIds.firstOrNull()?.let { agentState.sessionNames[it] } ?: "Not Subscribed"
    val hkgName = agent.knowledgeGraphId?.let { agentState.knowledgeGraphNames[it] } ?: "No HKG"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AgentControlCard(agent, store, platformDependencies)
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Subscribed: $sessionName" + if (agent.subscribedSessionIds.size > 1) " (+${agent.subscribedSessionIds.size - 1} more)" else "", style = MaterialTheme.typography.bodyMedium)
                Text("Knowledge Graph: $hkgName", style = MaterialTheme.typography.bodyMedium)
                Text("Model: ${agent.modelProvider}/${agent.modelName}", style = MaterialTheme.typography.bodyMedium)
            }
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
    var autoWaitTimeInput by remember(agent.id) { mutableStateOf(agent.autoWaitTimeSeconds.toString()) }
    var autoMaxWaitTimeInput by remember(agent.id) { mutableStateOf(agent.autoMaxWaitTimeSeconds.toString()) }

    val onSave = {
        store.dispatch("ui.agentManager", Action(ActionNames.AGENT_UPDATE_CONFIG, buildJsonObject {
            put("agentId", agent.id)
            put("name", agentNameInput)
            autoWaitTimeInput.toIntOrNull()?.let { put("autoWaitTimeSeconds", it) }
            autoMaxWaitTimeInput.toIntOrNull()?.let { put("autoMaxWaitTimeSeconds", it) }
        }))
        store.dispatch("ui.agentManager", Action(ActionNames.AGENT_SET_EDITING, buildJsonObject { put("agentId", null as String?) }))
    }
    val onCancel = {
        store.dispatch("ui.agentManager", Action(ActionNames.AGENT_SET_EDITING, buildJsonObject { put("agentId", null as String?) }))
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            OutlinedTextField(
                value = agentNameInput,
                onValueChange = { agentNameInput = it },
                label = { Text("Agent Name") },
                modifier = Modifier.weight(1f)
            )
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 2
        ) {
            Box(Modifier.weight(1f)) {
                if (agent.knowledgeGraphId == null) {
                    SingleSessionSelector(agent, agentState, store)
                } else {
                    MultiSessionSelector(agent, agentState, store)
                }
            }
            Box(Modifier.weight(1f)) { KnowledgeGraphSelector(agent, agentState, store) }
            Box(Modifier.weight(1f)) { ProviderSelector(agent, agentState, store) }
            Box(Modifier.weight(1f)) { ModelSelector(agent, agentState, store) }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Automatic Mode", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = agent.automaticMode,
                onCheckedChange = {
                    store.dispatch("ui.agentManager", Action(ActionNames.AGENT_TOGGLE_AUTOMATIC_MODE, buildJsonObject {
                        put("agentId", agent.id)
                    }))
                }
            )
        }

        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = autoWaitTimeInput,
                onValueChange = { autoWaitTimeInput = it.filter { c -> c.isDigit() } },
                label = { Text("Auto Wait (s)") },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = autoMaxWaitTimeInput,
                onValueChange = { autoMaxWaitTimeInput = it.filter { c -> c.isDigit() } },
                label = { Text("Max Wait (s)") },
                modifier = Modifier.weight(1f)
            )
        }
        Row (
            Modifier.fillMaxWidth(),
            Arrangement.End,
            Alignment.CenterVertically
        ){
            IconButton(onClick = onCancel) { Icon(Icons.Default.Cancel, "Cancel Edit") }
            IconButton(onClick = onSave, enabled = agentNameInput.isNotBlank()) { Icon(Icons.Default.Save, "Save Name") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingleSessionSelector(agent: AgentInstance, agentState: AgentRuntimeState, store: Store) {
    // *** NEW: Filter out private cognitive sessions ***
    val availableSessions = remember(agentState.sessionNames) {
        agentState.sessionNames.entries.toList().filter { !it.value.startsWith("p-cognition:") }
    }
    var isExpanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = isExpanded, onExpandedChange = { isExpanded = !isExpanded }) {
        OutlinedTextField(
            value = agent.subscribedSessionIds.firstOrNull()?.let { agentState.sessionNames[it] } ?: "Not Subscribed",
            onValueChange = {}, readOnly = true, label = { Text("Session") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(isExpanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = isExpanded, onDismissRequest = { isExpanded = false }) {
            DropdownMenuItem(text = { Text("None (Unsubscribe)") }, onClick = {
                store.dispatch("ui.agentManager", Action(ActionNames.AGENT_UPDATE_CONFIG, buildJsonObject {
                    put("agentId", agent.id)
                    put("subscribedSessionIds", buildJsonArray {})
                }))
                isExpanded = false
            })
            availableSessions.forEach { (sessionId, sessionName) ->
                DropdownMenuItem(text = { Text(sessionName) }, onClick = {
                    store.dispatch("ui.agentManager", Action(ActionNames.AGENT_UPDATE_CONFIG, buildJsonObject {
                        put("agentId", agent.id)
                        put("subscribedSessionIds", buildJsonArray { add(sessionId) })
                    }))
                    isExpanded = false
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MultiSessionSelector(agent: AgentInstance, agentState: AgentRuntimeState, store: Store) {
    // *** NEW: Filter out private cognitive sessions ***
    val availableSessions = remember(agentState.sessionNames) {
        agentState.sessionNames.entries.toList().filter { !it.value.startsWith("p-cognition:") }
    }
    var isExpanded by remember { mutableStateOf(false) }

    val selectionText = when (agent.subscribedSessionIds.size) {
        0 -> "Not Subscribed"
        1 -> agentState.sessionNames[agent.subscribedSessionIds.first()] ?: "1 Session"
        else -> "${agent.subscribedSessionIds.size} Sessions"
    }

    ExposedDropdownMenuBox(expanded = isExpanded, onExpandedChange = { isExpanded = !isExpanded }) {
        OutlinedTextField(
            value = selectionText,
            onValueChange = {},
            readOnly = true,
            label = { Text("Subscriptions") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(isExpanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        DropdownMenu(
            expanded = isExpanded,
            onDismissRequest = { isExpanded = false },
            modifier = Modifier.exposedDropdownSize(true)
        ) {
            availableSessions.forEach { (sessionId, sessionName) ->
                val isSelected = agent.subscribedSessionIds.contains(sessionId)
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = isSelected, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text(sessionName)
                        }
                    },
                    onClick = {
                        val newSelection = if (isSelected) {
                            agent.subscribedSessionIds - sessionId
                        } else {
                            agent.subscribedSessionIds + sessionId
                        }
                        store.dispatch("ui.agentManager", Action(ActionNames.AGENT_UPDATE_CONFIG, buildJsonObject {
                            put("agentId", agent.id)
                            put("subscribedSessionIds", buildJsonArray {
                                newSelection.forEach { add(it) }
                            })
                        }))
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KnowledgeGraphSelector(agent: AgentInstance, agentState: AgentRuntimeState, store: Store) {
    val availableGraphs = remember(agentState.knowledgeGraphNames) {
        agentState.knowledgeGraphNames.entries.toList()
    }
    var isExpanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = isExpanded, onExpandedChange = { isExpanded = !isExpanded }) {
        OutlinedTextField(
            value = agentState.knowledgeGraphNames[agent.knowledgeGraphId] ?: "None",
            onValueChange = {}, readOnly = true, label = { Text("Knowledge Graph") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(isExpanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = isExpanded, onDismissRequest = { isExpanded = false }) {
            DropdownMenuItem(text = { Text("None") }, onClick = {
                store.dispatch("ui.agentManager", Action(ActionNames.AGENT_UPDATE_CONFIG, buildJsonObject {
                    put("agentId", agent.id); put("knowledgeGraphId", null as String?)
                }))
                isExpanded = false
            })
            availableGraphs.forEach { (graphId, graphName) ->
                DropdownMenuItem(text = { Text(graphName) }, onClick = {
                    store.dispatch("ui.agentManager", Action(ActionNames.AGENT_UPDATE_CONFIG, buildJsonObject {
                        put("agentId", agent.id); put("knowledgeGraphId", graphId)
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
            value = if (availableProviders.isEmpty()) "No providers found" else agent.modelProvider,
            onValueChange = {}, readOnly = true, label = { Text("Provider") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(isExpanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            enabled = availableProviders.isNotEmpty()
        )
        ExposedDropdownMenu(expanded = isExpanded, onDismissRequest = { isExpanded = false }) {
            availableProviders.forEach { providerId ->
                DropdownMenuItem(text = { Text(providerId) }, onClick = {
                    store.dispatch("ui.agentManager", Action(ActionNames.AGENT_UPDATE_CONFIG, buildJsonObject {
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
            value = if (availableModels.isEmpty() && agent.modelProvider.isNotBlank()) "No models found" else agent.modelName,
            onValueChange = {}, readOnly = true, label = { Text("Model") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(isExpanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            enabled = availableModels.isNotEmpty()
        )
        ExposedDropdownMenu(expanded = isExpanded, onDismissRequest = { isExpanded = false }) {
            availableModels.forEach { modelName ->
                DropdownMenuItem(text = { Text(modelName) }, onClick = {
                    store.dispatch("ui.agentManager", Action(ActionNames.AGENT_UPDATE_CONFIG, buildJsonObject {
                        put("agentId", agent.id); put("modelName", modelName)
                    }))
                    isExpanded = false
                })
            }
        }
    }
}