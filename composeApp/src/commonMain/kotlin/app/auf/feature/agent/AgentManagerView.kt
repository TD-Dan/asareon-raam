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
import app.auf.feature.gateway.GatewayState  //TODO: THIS IS A VIOLATION AND NEEDS TO BE FIXED
import app.auf.feature.session.SessionState  //TODO: THIS IS A VIOLATION AND NEEDS TO BE FIXED
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentManagerView(store: Store) {
    val appState by store.state.collectAsState()
    val agentState = remember(appState.featureStates) {
        appState.featureStates["agent"] as? AgentRuntimeState
    }
    val sessionState = remember(appState.featureStates) {
        appState.featureStates["session"] as? SessionState
    }
    val gatewayState = remember(appState.featureStates) {
        appState.featureStates["gateway"] as? GatewayState
    }
    var agentToDelete by remember { mutableStateOf<AgentInstance?>(null) }
    val editingAgentId = agentState?.editingAgentId

    LaunchedEffect(Unit) {
        // Ensure we have the latest list of models from the gateway.
        store.dispatch("ui.agentManager", Action("gateway.REQUEST_AVAILABLE_MODELS"))
    }

    // --- Confirmation Dialog ---
    agentToDelete?.let { agent ->
        AlertDialog(
            onDismissRequest = { agentToDelete = null },
            title = { Text("Delete Agent?") },
            text = { Text("Are you sure you want to permanently delete '${agent.name}'? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        val payload = buildJsonObject { put("agentId", agent.id) }
                        store.dispatch("ui.agentManager", Action("agent.DELETE", payload))
                        agentToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                Button(onClick = { agentToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent Manager") },
                actions = {
                    Button(onClick = {
                        val payload = buildJsonObject {
                            put("name", "New Agent")
                            put("personaId", "keel-20250914T142800Z")
                            put("modelProvider", "gemini")
                            put("modelName", "gemini-2.5-pro")
                            put("primarySessionId", null as String?)
                        }
                        store.dispatch("ui.agentManager", Action("agent.CREATE", payload))
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "Create New Agent")
                        Spacer(Modifier.width(8.dp))
                        Text("New Agent")
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
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(agentState.agents.values.toList(), key = { it.id }) { agent ->
                    val isEditing = agent.id == editingAgentId
                    AgentCard(
                        agent = agent,
                        isEditing = isEditing,
                        sessionState = sessionState,
                        gatewayState = gatewayState,
                        store = store,
                        onDeleteRequest = { agentToDelete = it },
                        onEditRequest = {
                            val payload = buildJsonObject { put("agentId", agent.id) }
                            store.dispatch("ui.agentManager", Action("agent.SET_EDITING", payload))
                        }
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
    sessionState: SessionState?,
    gatewayState: GatewayState?,
    store: Store,
    onDeleteRequest: (AgentInstance) -> Unit,
    onEditRequest: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Switch between read-only and editing views
            if (isEditing) {
                AgentEditorView(agent, sessionState, gatewayState, store)
            } else {
                AgentReadOnlyView(agent)
            }

            // --- Action Buttons (Common to both views) ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onEditRequest) {
                    Icon(
                        imageVector = if (isEditing) Icons.Default.Done else Icons.Default.Edit,
                        contentDescription = if (isEditing) "Done Editing" else "Edit Agent"
                    )
                }

                IconButton(onClick = { onDeleteRequest(agent) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Agent")
                }

                Spacer(Modifier.width(8.dp))

                Button(
                    onClick = {
                        val payload = buildJsonObject { put("agentId", agent.id) }
                        store.dispatch("ui.agentManager", Action("agent.TRIGGER_MANUAL_TURN", payload))
                    },
                    enabled = agent.status != AgentStatus.PROCESSING
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Trigger Turn")
                    Spacer(Modifier.width(4.dp))
                    Text("Trigger")
                }
            }
        }
    }
}

@Composable
private fun AgentReadOnlyView(agent: AgentInstance) {
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(agent.name, style = MaterialTheme.typography.titleLarge)
            Text("Session: ${agent.primarySessionId ?: "Not Subscribed"}", style = MaterialTheme.typography.bodyMedium)
            Text("Model: ${agent.modelProvider}/${agent.modelName}", style = MaterialTheme.typography.bodyMedium)
            Text("Status: ${agent.status}", style = MaterialTheme.typography.bodyMedium)

            if (agent.status == AgentStatus.ERROR && agent.errorMessage != null) {
                Text(
                    text = agent.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentEditorView(
    agent: AgentInstance,
    sessionState: SessionState?,
    gatewayState: GatewayState?,
    store: Store
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AgentNameEditor(agent, store)

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 3
        ) {
            Box(modifier = Modifier.weight(1f)) {
                SessionSelector(agent, sessionState, store)
            }
            Box(modifier = Modifier.weight(1f)) {
                ProviderSelector(agent, gatewayState, store)
            }
            Box(modifier = Modifier.weight(1f)) {
                ModelSelector(agent, gatewayState, store)
            }
        }
    }
}

@Composable
private fun AgentNameEditor(agent: AgentInstance, store: Store) {
    var agentNameInput by remember(agent.id) { mutableStateOf(agent.name) }

    // Debounced effect to save the name change automatically
    LaunchedEffect(agentNameInput) {
        if (agentNameInput != agent.name) {
            delay(1000) // 1-second debounce
            val payload = buildJsonObject {
                put("agentId", agent.id)
                put("name", agentNameInput)
            }
            store.dispatch("ui.agentManager", Action("agent.UPDATE_CONFIG", payload))
        }
    }

    OutlinedTextField(
        value = agentNameInput,
        onValueChange = { agentNameInput = it },
        label = { Text("Agent Name") },
        modifier = Modifier.fillMaxWidth()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionSelector(agent: AgentInstance, sessionState: SessionState?, store: Store) {
    val availableSessions = remember(sessionState) {
        sessionState?.sessions?.values?.sortedByDescending { it.createdAt } ?: emptyList()
    }
    var isExpanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = isExpanded, onExpandedChange = { isExpanded = !isExpanded }) {
        OutlinedTextField(
            value = availableSessions.find { it.id == agent.primarySessionId }?.name ?: "Not Subscribed",
            onValueChange = {},
            readOnly = true,
            label = { Text("Session") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isExpanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = isExpanded, onDismissRequest = { isExpanded = false }) {
            DropdownMenuItem(text = { Text("None (Unsubscribe)") }, onClick = {
                val payload = buildJsonObject {
                    put("agentId", agent.id); put("primarySessionId", null as String?)
                }
                store.dispatch("ui.agentManager", Action("agent.UPDATE_CONFIG", payload))
                isExpanded = false
            })
            availableSessions.forEach { session ->
                DropdownMenuItem(text = { Text(session.name) }, onClick = {
                    val payload = buildJsonObject {
                        put("agentId", agent.id); put("primarySessionId", session.id)
                    }
                    store.dispatch("ui.agentManager", Action("agent.UPDATE_CONFIG", payload))
                    isExpanded = false
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderSelector(agent: AgentInstance, gatewayState: GatewayState?, store: Store) {
    val availableProviders = remember(gatewayState) { gatewayState?.availableModels?.keys?.toList() ?: emptyList() }
    var isExpanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = isExpanded, onExpandedChange = { isExpanded = !isExpanded }) {
        OutlinedTextField(
            value = agent.modelProvider,
            onValueChange = {},
            readOnly = true,
            label = { Text("Provider") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isExpanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = isExpanded, onDismissRequest = { isExpanded = false }) {
            availableProviders.forEach { providerId ->
                DropdownMenuItem(text = { Text(providerId) }, onClick = {
                    val payload = buildJsonObject {
                        put("agentId", agent.id)
                        put("modelProvider", providerId)
                        // When provider changes, reset model to the first available to avoid invalid state.
                        put("modelName", gatewayState?.availableModels?.get(providerId)?.firstOrNull())
                    }
                    store.dispatch("ui.agentManager", Action("agent.UPDATE_CONFIG", payload))
                    isExpanded = false
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelector(agent: AgentInstance, gatewayState: GatewayState?, store: Store) {
    val availableModels = remember(gatewayState, agent.modelProvider) {
        gatewayState?.availableModels?.get(agent.modelProvider) ?: emptyList()
    }
    var isExpanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = isExpanded, onExpandedChange = { isExpanded = !isExpanded }) {
        OutlinedTextField(
            value = agent.modelName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Model") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isExpanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            enabled = availableModels.isNotEmpty()
        )
        ExposedDropdownMenu(expanded = isExpanded, onDismissRequest = { isExpanded = false }) {
            availableModels.forEach { modelName ->
                DropdownMenuItem(text = { Text(modelName) }, onClick = {
                    val payload = buildJsonObject {
                        put("agentId", agent.id); put("modelName", modelName)
                    }
                    store.dispatch("ui.agentManager", Action("agent.UPDATE_CONFIG", payload))
                    isExpanded = false
                })
            }
        }
    }
}