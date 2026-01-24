package app.auf.feature.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.auf.core.*
import app.auf.core.generated.ActionNames
import app.auf.feature.agent.strategies.VanillaStrategy
import app.auf.ui.components.CodeEditor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentManagerView(store: Store, platformDependencies: app.auf.util.PlatformDependencies) {
    val appState by store.state.collectAsState()
    val agentState = remember(appState.featureStates) {
        appState.featureStates["agent"] as? AgentRuntimeState
    }

    LaunchedEffect(Unit) {
        store.dispatch("ui.agentManager", Action(ActionNames.GATEWAY_REQUEST_AVAILABLE_MODELS))
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("Agent Manager") })
                TabRow(selectedTabIndex = agentState?.activeManagerTab ?: 0) {
                    Tab(
                        selected = agentState?.activeManagerTab == 0,
                        onClick = { store.dispatch("ui.agentManager", Action(ActionNames.AGENT_SET_MANAGER_TAB, buildJsonObject { put("tabIndex", 0) })) },
                        text = { Text("Agents") },
                        icon = { Icon(Icons.Default.Group, "Agents") }
                    )
                    Tab(
                        selected = agentState?.activeManagerTab == 1,
                        onClick = { store.dispatch("ui.agentManager", Action(ActionNames.AGENT_SET_MANAGER_TAB, buildJsonObject { put("tabIndex", 1) })) },
                        text = { Text("System Resources") },
                        icon = { Icon(Icons.Default.SettingsSystemDaydream, "Resources") }
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(Modifier.padding(paddingValues).fillMaxSize()) {
            if (agentState == null) {
                Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Loading...") }
            } else {
                when (agentState.activeManagerTab) {
                    0 -> AgentListView(agentState, store, platformDependencies)
                    1 -> AgentResourcesView(agentState, store)
                }
            }
        }
    }
}

// --- TAB 1: Agent List (Original Functionality) ---

@Composable
private fun AgentListView(
    agentState: AgentRuntimeState,
    store: Store,
    platformDependencies: app.auf.util.PlatformDependencies
) {
    var agentToDelete by remember { mutableStateOf<AgentInstance?>(null) }
    val editingAgentId = agentState.editingAgentId

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

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), Arrangement.End) {
            Button(onClick = {
                store.dispatch("ui.agentManager", Action(ActionNames.AGENT_CREATE, buildJsonObject {
                    put("name", "New Agent")
                }))
            }) {
                Icon(Icons.Default.Add, "Create"); Spacer(Modifier.width(8.dp)); Text("New Agent")
            }
        }

        if (agentState.agents.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No agents configured.") }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
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
    val privateSessionName = agent.privateSessionId?.let { agentState.sessionNames[it] } ?: agent.privateSessionId ?: "None"
    val statusInfo = agentState.agentStatuses[agent.id] ?: AgentStatusInfo()

    var showInternals by remember { mutableStateOf(false) }
    // Only consider Sovereign if HKG is assigned.
    val isSovereign = agent.knowledgeGraphId != null

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AgentControlCard(agent, statusInfo, store, platformDependencies)

        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Subscribed: $sessionName" + if (agent.subscribedSessionIds.size > 1) " (+${agent.subscribedSessionIds.size - 1} more)" else "", style = MaterialTheme.typography.bodyMedium)

                // [LOGIC] Only show Sovereign details if actually Sovereign (has HKG)
                if (isSovereign) {
                    Text("Knowledge Graph: $hkgName", style = MaterialTheme.typography.bodyMedium)
                    Text("Private Session: ${agent.privateSessionId ?: "None"} ($privateSessionName)", style = MaterialTheme.typography.bodyMedium)
                }

                Text("Model: ${agent.modelProvider}/${agent.modelName}", style = MaterialTheme.typography.bodyMedium)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Strategy: ${agent.cognitiveStrategyId}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = { showInternals = !showInternals },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            if (showInternals) Icons.Default.ExpandLess else Icons.Default.Info,
                            contentDescription = "Inspect State",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        if (showInternals) {
            val prettyJson = remember(agent.cognitiveState) {
                val json = Json { prettyPrint = true }
                json.encodeToString(agent.cognitiveState)
            }

            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(
                    "Cognitive State (NVRAM)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
                CodeEditor(
                    value = prettyJson,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.height(150.dp)
                )
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

    // [LOGIC] Determine if we should show Sovereign options
    val isVanilla = agent.cognitiveStrategyId == VanillaStrategy.id

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

        // --- ROW 1: Identity (Name + Strategy) ---
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = agentNameInput,
                onValueChange = { agentNameInput = it },
                label = { Text("Agent Name") },
                modifier = Modifier.weight(1f)
            )
            Box(Modifier.weight(1f)) {
                StrategySelector(agent, store)
            }
        }

        // --- ROW 2: Compute (Provider + Model) ---
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) { ProviderSelector(agent, agentState, store) }
            Box(Modifier.weight(1f)) { ModelSelector(agent, agentState, store) }
        }

        // --- ROW 3: Context (Subscriptions) ---
        Row(Modifier.fillMaxWidth()) {
            if (agent.knowledgeGraphId == null) {
                SingleSessionSelector(agent, agentState, store)
            } else {
                MultiSessionSelector(agent, agentState, store)
            }
        }

        // --- ROW 4: Sovereign Specifics (Knowledge Graph) ---
        // [LOGIC] Only show if Strategy is NOT Vanilla
        if (!isVanilla) {
            Row(Modifier.fillMaxWidth()) {
                KnowledgeGraphSelector(agent, agentState, store)
            }
        }

        // --- ROW 5: Auto Mode ---
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

// --- TAB 2: System Resources ---

@Composable
private fun AgentResourcesView(
    agentState: AgentRuntimeState,
    store: Store
) {
    var showCreateDialog by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxSize()) {
        // Left Pane: Resource List
        Column(
            modifier = Modifier
                .weight(0.3f)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Resources", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Default.Add, "New Resource")
                }
            }

            HorizontalDivider()

            // Group resources by type
            val grouped = agentState.resources.groupBy { it.type }

            LazyColumn(Modifier.fillMaxSize()) {
                AgentResourceType.entries.forEach { type ->
                    val resources = grouped[type] ?: emptyList()
                    if (resources.isNotEmpty() || type == AgentResourceType.SYSTEM_INSTRUCTION) {
                        item {
                            Text(
                                text = type.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp)
                            )
                        }
                        items(resources) { res ->
                            ResourceListItem(
                                resource = res,
                                isSelected = res.id == agentState.editingResourceId,
                                onClick = { store.dispatch("ui.resources", Action(ActionNames.AGENT_SELECT_RESOURCE, buildJsonObject { put("resourceId", res.id) })) }
                            )
                        }
                    }
                }
            }
        }

        VerticalDivider()

        // Right Pane: Editor
        Box(Modifier.weight(0.7f).fillMaxHeight()) {
            val selectedResource = agentState.resources.find { it.id == agentState.editingResourceId }
            if (selectedResource != null) {
                ResourceEditor(selectedResource, store)
            } else {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("Select a resource to edit.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateResourceDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, type, content ->
                store.dispatch("ui.resources", Action(ActionNames.AGENT_CREATE_RESOURCE, buildJsonObject {
                    put("name", name)
                    put("type", type.name)
                    if (content != null) put("initialContent", content)
                }))
                showCreateDialog = false
            }
        )
    }
}

@Composable
private fun ResourceListItem(resource: AgentResource, isSelected: Boolean, onClick: () -> Unit) {
    val bgColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(bgColor)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                resource.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
            if (resource.isBuiltIn) {
                Text("Built-in", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Composable
private fun ResourceEditor(resource: AgentResource, store: Store) {
    var content by remember(resource.id) { mutableStateOf(resource.content) }
    val hasChanges by remember(resource.id, content) { derivedStateOf { content != resource.content } }

    // State for Clone Dialog
    var showCloneDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column {
                Text(resource.name, style = MaterialTheme.typography.titleMedium)
                Text(resource.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row {
                if (!resource.isBuiltIn) {
                    Button(
                        onClick = { store.dispatch("ui.resources", Action(ActionNames.AGENT_DELETE_RESOURCE, buildJsonObject { put("resourceId", resource.id) })) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Delete, "Delete")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { store.dispatch("ui.resources", Action(ActionNames.AGENT_SAVE_RESOURCE, buildJsonObject {
                            put("resourceId", resource.id)
                            put("content", content)
                        })) },
                        enabled = hasChanges
                    ) {
                        Icon(Icons.Default.Save, "Save")
                        Spacer(Modifier.width(4.dp))
                        Text("Save")
                    }
                } else {
                    // [UPDATED] Clone Workflow
                    FilledTonalButton(onClick = { showCloneDialog = true }) {
                        Icon(Icons.Default.CopyAll, "Clone to Edit")
                        Spacer(Modifier.width(4.dp))
                        Text("Clone")
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        CodeEditor(
            value = content,
            onValueChange = { content = it },
            readOnly = resource.isBuiltIn,
            modifier = Modifier.weight(1f)
        )
    }

    if (showCloneDialog) {
        CreateResourceDialog(
            onDismiss = { showCloneDialog = false },
            initialName = "${resource.name} (Copy)",
            initialType = resource.type,
            lockType = true,
            onConfirm = { name, type, _ ->
                store.dispatch("ui.resources", Action(ActionNames.AGENT_CREATE_RESOURCE, buildJsonObject {
                    put("name", name)
                    put("type", type.name)
                    put("initialContent", resource.content) // Pass built-in content to new resource
                }))
                showCloneDialog = false
            }
        )
    }
}

@Composable
private fun CreateResourceDialog(
    onDismiss: () -> Unit,
    initialName: String = "",
    initialType: AgentResourceType = AgentResourceType.SYSTEM_INSTRUCTION,
    lockType: Boolean = false,
    onConfirm: (String, AgentResourceType, String?) -> Unit
) {
    val nameState = remember { mutableStateOf(initialName) }
    val typeState = remember { mutableStateOf(initialType) }
    val expandedState = remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (lockType) "Clone Resource" else "Create Resource") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = nameState.value,
                    onValueChange = { nameState.value = it },
                    label = { Text("Resource Name") },
                    singleLine = true
                )

                Box {
                    OutlinedTextField(
                        value = typeState.value.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Type") },
                        trailingIcon = if (!lockType) {
                            { IconButton(onClick = { expandedState.value = true }) { Icon(Icons.Default.ArrowDropDown, "Select Type") } }
                        } else null,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !lockType) { expandedState.value = true },
                        enabled = !lockType
                    )
                    if (!lockType) {
                        DropdownMenu(expanded = expandedState.value, onDismissRequest = { expandedState.value = false }) {
                            AgentResourceType.entries.forEach { t ->
                                DropdownMenuItem(
                                    text = { Text(t.name) },
                                    onClick = { typeState.value = t; expandedState.value = false }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(nameState.value, typeState.value, null) }, enabled = nameState.value.isNotBlank()) {
                Text(if (lockType) "Clone" else "Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// --- Sub-Composables for AgentEditorView ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingleSessionSelector(agent: AgentInstance, agentState: AgentRuntimeState, store: Store) {
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
    val availableGraphs = remember(agentState.knowledgeGraphNames, agentState.hkgReservedIds, agent.knowledgeGraphId) {
        agentState.knowledgeGraphNames.entries.filter { (id, _) ->
            !agentState.hkgReservedIds.contains(id) || id == agent.knowledgeGraphId
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StrategySelector(agent: AgentInstance, store: Store) {
    val strategies = remember { CognitiveStrategyRegistry.getAll() }
    var isExpanded by remember { mutableStateOf(false) }
    val currentStrategy = strategies.find { it.id == agent.cognitiveStrategyId }

    ExposedDropdownMenuBox(expanded = isExpanded, onExpandedChange = { isExpanded = !isExpanded }) {
        OutlinedTextField(
            value = currentStrategy?.displayName ?: agent.cognitiveStrategyId,
            onValueChange = {}, readOnly = true, label = { Text("Strategy") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(isExpanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = isExpanded, onDismissRequest = { isExpanded = false }) {
            strategies.forEach { strategy ->
                DropdownMenuItem(text = { Text(strategy.displayName) }, onClick = {
                    // [LOGIC] If switching to Vanilla, enforce HKG cleansing side-effect
                    val extraUpdates = if (strategy.id == VanillaStrategy.id) {
                        mapOf("knowledgeGraphId" to null)
                    } else {
                        emptyMap()
                    }

                    store.dispatch("ui.agentManager", Action(ActionNames.AGENT_UPDATE_CONFIG, buildJsonObject {
                        put("agentId", agent.id)
                        put("cognitiveStrategyId", strategy.id)
                        extraUpdates.forEach { (k, v) -> put(k, v as String?) }
                    }))
                    isExpanded = false
                })
            }
        }
    }
}