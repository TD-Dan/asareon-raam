package app.auf.feature.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.auf.core.*
import app.auf.core.generated.ActionRegistry
import app.auf.ui.components.CodeEditor
import app.auf.ui.components.ColorPicker
import app.auf.ui.components.IconRegistry
import app.auf.ui.components.colorToHex
import app.auf.ui.components.hexToColor
import kotlinx.serialization.json.*

// ============================================================================
// Helpers for knowledgeGraphId, which lives in strategyConfig as a
// strategy-owned key.
// ============================================================================

/** Reads `knowledgeGraphId` from the agent's strategyConfig. Null if absent. */
private fun AgentInstance.getKnowledgeGraphId(): String? =
    strategyConfig["knowledgeGraphId"]
        ?.jsonPrimitive
        ?.contentOrNull

/** Returns a copy of this agent with `knowledgeGraphId` set/cleared in strategyConfig. */
private fun AgentInstance.withKnowledgeGraphId(kgId: String?): AgentInstance {
    val updatedConfig = buildJsonObject {
        strategyConfig.forEach { (k, v) -> put(k, v) }
        if (kgId != null) put("knowledgeGraphId", kgId) else put("knowledgeGraphId", JsonNull)
    }
    return copy(strategyConfig = updatedConfig)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentManagerView(store: Store, platformDependencies: app.auf.util.PlatformDependencies) {
    val appState by store.state.collectAsState()
    val agentState = remember(appState.featureStates) {
        appState.featureStates["agent"] as? AgentRuntimeState
    }

    LaunchedEffect(Unit) {
        store.dispatch("agent", Action(ActionRegistry.Names.GATEWAY_REQUEST_AVAILABLE_MODELS))
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("Agent Manager") })
                TabRow(selectedTabIndex = agentState?.activeManagerTab ?: 0) {
                    Tab(
                        selected = agentState?.activeManagerTab == 0,
                        onClick = { store.dispatch("agent", Action(ActionRegistry.Names.AGENT_SET_MANAGER_TAB, buildJsonObject { put("tabIndex", 0) })) },
                        text = { Text("Agents") },
                        icon = { Icon(Icons.Default.Group, "Agents") }
                    )
                    Tab(
                        selected = agentState?.activeManagerTab == 1,
                        onClick = { store.dispatch("agent", Action(ActionRegistry.Names.AGENT_SET_MANAGER_TAB, buildJsonObject { put("tabIndex", 1) })) },
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

// --- TAB 1: Agent List ---

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
            text = { Text("Are you sure you want to permanently delete '${agent.identity.name}'? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        store.dispatch("agent", Action(ActionRegistry.Names.AGENT_DELETE, buildJsonObject { put("agentId", agent.identity.uuid) }))
                        agentToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { Button(onClick = { agentToDelete = null }) { Text("Cancel") } }
        )
    }

    Column(Modifier.fillMaxSize()) {
        var globalShowExtendedInfo by remember { mutableStateOf(false) }

        Row(Modifier.fillMaxWidth().padding(16.dp), Arrangement.End, Alignment.CenterVertically) {
            // Global eye toggle — expand/collapse all agent details at once
            IconButton(onClick = { globalShowExtendedInfo = !globalShowExtendedInfo }) {
                Icon(
                    imageVector = if (globalShowExtendedInfo) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (globalShowExtendedInfo) "Collapse All Details" else "Expand All Details",
                    tint = if (globalShowExtendedInfo) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                store.dispatch("agent", Action(ActionRegistry.Names.AGENT_CREATE, buildJsonObject {
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
                items(agentState.agents.values.toList(), key = { it.identity.uuid ?: "" }) { agent ->
                    AgentCard(
                        agent = agent,
                        isEditing = agent.identityUUID == editingAgentId,
                        agentState = agentState,
                        store = store,
                        onDeleteRequest = { agentToDelete = it },
                        onEditRequest = { store.dispatch("agent", Action(ActionRegistry.Names.AGENT_SET_EDITING, buildJsonObject { put("agentId", agent.identity.uuid) })) },
                        platformDependencies = platformDependencies,
                        showExtendedInfo = globalShowExtendedInfo
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
    platformDependencies: app.auf.util.PlatformDependencies,
    showExtendedInfo: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        if (isEditing) {
            Column(Modifier.padding(16.dp)) {
                AgentEditorView(agent, agentState, store)
            }
        } else {
            AgentControlCard(
                agent = agent,
                agentState = agentState,
                sessionUUID = null,
                store = store,
                platformDependencies = platformDependencies,
                showExtendedInfoOverride = showExtendedInfo,
                showManagementActions = true,
                onEditRequest = onEditRequest,
                onCloneRequest = {
                    store.dispatch("agent", Action(ActionRegistry.Names.AGENT_CLONE, buildJsonObject {
                        put("agentId", agent.identity.uuid)
                    }))
                },
                onDeleteRequest = { onDeleteRequest(agent) }
            )
        }
    }
}

// =============================================================================
// Agent Editor — Draft Pattern
// All changes are local until Save. Cancel discards.
// =============================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentEditorView(
    agent: AgentInstance,
    agentState: AgentRuntimeState,
    store: Store
) {
    // Draft state: a local copy of the agent. Selectors mutate the draft, not the store.
    var draftAgent by remember(agent.identity.uuid) { mutableStateOf(agent) }
    var agentNameInput by remember(agent.identity.uuid) { mutableStateOf(agent.identity.name) }
    var autoWaitTimeInput by remember(agent.identity.uuid) { mutableStateOf(agent.autoWaitTimeSeconds.toString()) }
    var autoMaxWaitTimeInput by remember(agent.identity.uuid) { mutableStateOf(agent.autoMaxWaitTimeSeconds.toString()) }

    // Color: read from identity registry (authoritative), draft locally
    val identityRegistry = store.state.collectAsState().value.identityRegistry
    val currentIdentity = identityRegistry[agent.identity.handle]
    var draftColorHex by remember(agent.identity.uuid) { mutableStateOf(currentIdentity?.displayColor) }
    var showColorPicker by remember { mutableStateOf(false) }
    val draftColor: Color? = draftColorHex?.let { hexToColor(it) }

    // Icon: read from identity registry, draft locally
    var draftIconKey by remember(agent.identity.uuid) { mutableStateOf(currentIdentity?.displayIcon) }
    var draftIconEmoji by remember(agent.identity.uuid) { mutableStateOf(currentIdentity?.displayEmoji) }
    var showIconPicker by remember { mutableStateOf(false) }

    val onDraftChanged: (AgentInstance) -> Unit = { draftAgent = it }

    val onSave = {
        store.dispatch("agent", Action(ActionRegistry.Names.AGENT_UPDATE_CONFIG, buildJsonObject {
            put("agentId", agent.identity.uuid)
            put("name", agentNameInput)
            put("cognitiveStrategyId", draftAgent.cognitiveStrategyId.handle)
            put("modelProvider", draftAgent.modelProvider)
            put("modelName", draftAgent.modelName)
            put("subscribedSessionIds", buildJsonArray { draftAgent.subscribedSessionIds.forEach { add(it.uuid) } })
            // outputSessionId — strategy-owned primary session selection
            if (draftAgent.outputSessionId != null) put("outputSessionId", draftAgent.outputSessionId!!.uuid)
            else put("outputSessionId", null as String?)
            // strategyConfig holds strategy-specific operator configuration (e.g., knowledgeGraphId).
            // Sent as a generic JSON object — the CRUD logic stores it without inspecting contents.
            put("strategyConfig", draftAgent.strategyConfig)
            put("automaticMode", draftAgent.automaticMode)
            autoWaitTimeInput.toIntOrNull()?.let { put("autoWaitTimeSeconds", it) }
            autoMaxWaitTimeInput.toIntOrNull()?.let { put("autoMaxWaitTimeSeconds", it) }
            put("resources", Json.encodeToJsonElement(draftAgent.resources))
            // displayColor: include when explicitly set or cleared.
            // TODO: AgentFeature's AGENT_UPDATE_CONFIG handler must propagate this
            // field to core.UPDATE_IDENTITY so it persists in the identity registry.
            if (draftColorHex != null) put("displayColor", draftColorHex)
            else put("displayColor", null as String?)
            // Icon fields — always include to allow clearing
            if (draftIconKey != null) put("displayIcon", draftIconKey)
            else put("displayIcon", null as String?)
            if (draftIconEmoji != null) put("displayEmoji", draftIconEmoji)
            else put("displayEmoji", null as String?)
        }))
        store.dispatch("agent", Action(ActionRegistry.Names.AGENT_SET_EDITING, buildJsonObject { put("agentId", null as String?) }))
    }
    val onCancel = {
        store.dispatch("agent", Action(ActionRegistry.Names.AGENT_SET_EDITING, buildJsonObject { put("agentId", null as String?) }))
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.onKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                onCancel()
                true
            } else false
        }
    ) {

        // --- ROW 1: Identity (Name + Strategy) ---
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = agentNameInput,
                onValueChange = { agentNameInput = it },
                label = { Text("Agent Name") },
                modifier = Modifier.weight(1f)
            )
            Box(Modifier.weight(1f)) {
                StrategySelector(draftAgent, onDraftChanged)
            }
        }

        // --- ROW 1.5: Display Color ---
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Swatch preview
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(draftColor ?: MaterialTheme.colorScheme.primary)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            )
            OutlinedButton(onClick = { showColorPicker = !showColorPicker }) {
                Icon(Icons.Default.Palette, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (showColorPicker) "Hide color picker" else "Set color")
            }
            if (draftColorHex != null) {
                TextButton(onClick = { draftColorHex = null; showColorPicker = false }) {
                    Text("Reset to default")
                }
            }
        }
        AnimatedVisibility(visible = showColorPicker) {
            ColorPicker(
                initialColor = draftColor ?: MaterialTheme.colorScheme.primary,
                onConfirm = { color ->
                    draftColorHex = colorToHex(color)
                    showColorPicker = false
                },
                onCancel = {
                    showColorPicker = false
                }
            )
        }

        // --- ROW 1.6: Icon Picker ---
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Current icon preview
            val previewTint = draftColor ?: MaterialTheme.colorScheme.primary
            if (draftIconEmoji != null) {
                Text(draftIconEmoji!!, fontSize = 24.sp, color = previewTint, modifier = Modifier.size(32.dp), textAlign = TextAlign.Center)
            } else {
                val iconVector = IconRegistry.resolve(draftIconKey) ?: IconRegistry.defaultAgentIcon
                Icon(iconVector, contentDescription = null, modifier = Modifier.size(32.dp), tint = previewTint)
            }
            OutlinedButton(onClick = { showIconPicker = !showIconPicker }) {
                Icon(Icons.Default.EmojiEmotions, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (showIconPicker) "Hide icon picker" else "Set icon")
            }
            if (draftIconKey != null || draftIconEmoji != null) {
                TextButton(onClick = { draftIconKey = null; draftIconEmoji = null; showIconPicker = false }) {
                    Text("Reset to default")
                }
            }
        }
        AnimatedVisibility(visible = showIconPicker) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceDim, MaterialTheme.shapes.small)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Material Icons", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                // Icon grid — FlowRow of tinted icon buttons
                val iconTint = draftColor ?: MaterialTheme.colorScheme.primary
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconRegistry.agentIcons.forEach { key ->
                        val icon = IconRegistry.resolve(key) ?: return@forEach
                        val isSelected = draftIconKey == key && draftIconEmoji == null
                        IconButton(
                            onClick = { draftIconKey = key; draftIconEmoji = null },
                            modifier = Modifier.size(40.dp).then(
                                if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                                else Modifier
                            )
                        ) {
                            Icon(icon, contentDescription = key, tint = iconTint, modifier = Modifier.size(24.dp))
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Emoji input
                Text("Or paste an emoji", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = draftIconEmoji ?: "",
                        onValueChange = { input ->
                            if (input.isBlank()) {
                                draftIconEmoji = null
                            } else {
                                // Take first emoji/character cluster
                                draftIconEmoji = input.take(2) // Most emoji are 1-2 chars
                                draftIconKey = null
                            }
                        },
                        label = { Text("Emoji") },
                        singleLine = true,
                        modifier = Modifier.width(120.dp)
                    )
                    if (draftIconEmoji != null) {
                        Text(draftIconEmoji!!, fontSize = 28.sp, color = draftColor ?: MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        // --- ROW 2: Compute (Provider + Model) ---
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) { ProviderSelector(draftAgent, agentState, onDraftChanged) }
            Box(Modifier.weight(1f)) { ModelSelector(draftAgent, agentState, onDraftChanged) }
        }

        // --- ROW 3: Context (Subscriptions) — Unified multi-select for all strategies ---
        Row(Modifier.fillMaxWidth()) {
            MultiSessionSelector(draftAgent, agentState, onDraftChanged)
        }

        // --- ROW 4: Strategy-Specific Settings (polymorphic, driven by strategy.getConfigFields()) ---
        val currentStrategy = remember(draftAgent.cognitiveStrategyId) {
            if (CognitiveStrategyRegistry.getAll().isEmpty()) null
            else CognitiveStrategyRegistry.get(draftAgent.cognitiveStrategyId)
        }

        if (currentStrategy == null) {
            Text(
                "Strategy unavailable — registry not initialised.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        val configFields = remember(currentStrategy) { currentStrategy?.getConfigFields() ?: emptyList() }

        if (configFields.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(
                "Strategy Settings (${currentStrategy?.displayName ?: "Unknown"})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            configFields.forEach { field ->
                Row(Modifier.fillMaxWidth()) {
                    when (field.type) {
                        StrategyConfigFieldType.KNOWLEDGE_GRAPH ->
                            KnowledgeGraphSelector(draftAgent, agentState, onDraftChanged)
                        StrategyConfigFieldType.OUTPUT_SESSION ->
                            OutputSessionSelector(draftAgent, agentState, onDraftChanged)
                    }
                }
            }
        }

        // --- ROW 5: Resource Slots (Strategy-Driven) ---
        val resourceSlots = remember(currentStrategy) { currentStrategy?.getResourceSlots() ?: emptyList() }

        if (resourceSlots.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(
                "Resources",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        if (resourceSlots.size == 1) {
            val slot = resourceSlots.first()
            ResourceSlotSelector(
                label = slot.displayName,
                slotKey = slot.slotId,
                resourceType = slot.type,
                agentState = agentState,
                agent = draftAgent,
                onUpdate = onDraftChanged
            )
        } else if (resourceSlots.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                resourceSlots.forEach { slot ->
                    Box(Modifier.weight(1f)) {
                        ResourceSlotSelector(
                            label = slot.displayName,
                            slotKey = slot.slotId,
                            resourceType = slot.type,
                            agentState = agentState,
                            agent = draftAgent,
                            onUpdate = onDraftChanged
                        )
                    }
                }
            }
        }

        // --- ROW 6: Auto Mode ---
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text(
            "Automatic Operation",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Automatic Mode", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = draftAgent.automaticMode,
                onCheckedChange = { draftAgent = draftAgent.copy(automaticMode = it) }
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

        Row(
            Modifier.fillMaxWidth(),
            Arrangement.End,
            Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) { Icon(Icons.Default.Cancel, "Cancel Edit") }
            IconButton(onClick = onSave, enabled = agentNameInput.isNotBlank()) { Icon(Icons.Default.Save, "Save") }
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
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
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
                                onClick = { store.dispatch("agent", Action(ActionRegistry.Names.AGENT_SELECT_RESOURCE, buildJsonObject { put("resourceId", res.id) })) }
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
                store.dispatch("agent", Action(ActionRegistry.Names.AGENT_CREATE_RESOURCE, buildJsonObject {
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(
                if (isSelected) Modifier.background(MaterialTheme.colorScheme.surfaceContainer)
                else Modifier
            )
            .padding(start = if (isSelected) 0.dp else 4.dp)
            .padding(vertical = 8.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelected) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(36.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(Modifier.width(8.dp))
        }
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

    var showCloneDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(resource.name, style = MaterialTheme.typography.titleMedium)
                    // Rename Icon for non-built-ins
                    if (!resource.isBuiltIn) {
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = { showRenameDialog = true }, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Default.Edit, "Rename", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Text(resource.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row {
                if (!resource.isBuiltIn) {
                    Button(
                        onClick = { store.dispatch("agent", Action(ActionRegistry.Names.AGENT_DELETE_RESOURCE, buildJsonObject { put("resourceId", resource.id) })) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Delete, "Delete")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { store.dispatch("agent", Action(ActionRegistry.Names.AGENT_SAVE_RESOURCE, buildJsonObject {
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
                    // Clone Button for Built-ins
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
                store.dispatch("agent", Action(ActionRegistry.Names.AGENT_CREATE_RESOURCE, buildJsonObject {
                    put("name", name)
                    put("type", type.name)
                    put("initialContent", resource.content)
                }))
                showCloneDialog = false
            }
        )
    }

    if (showRenameDialog) {
        RenameResourceDialog(
            currentName = resource.name,
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName ->
                store.dispatch("agent", Action(ActionRegistry.Names.AGENT_RENAME_RESOURCE, buildJsonObject {
                    put("resourceId", resource.id)
                    put("newName", newName)
                }))
                showRenameDialog = false
            }
        )
    }
}

@Composable
private fun RenameResourceDialog(currentName: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Resource") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
        },
        confirmButton = {
            Button(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
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

// =============================================================================
// Selectors — Draft-Based (no direct store dispatch)
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MultiSessionSelector(agent: AgentInstance, agentState: AgentRuntimeState, onUpdate: (AgentInstance) -> Unit) {
    val availableSessions = agentState.subscribableSessionNames.entries.toList()
    var isExpanded by remember { mutableStateOf(false) }

    val selectionText = when (agent.subscribedSessionIds.size) {
        0 -> "Not Subscribed"
        1 -> agentState.subscribableSessionNames[agent.subscribedSessionIds.first()] ?: "1 Session"
        else -> "${agent.subscribedSessionIds.size} Sessions"
    }

    ExposedDropdownMenuBox(expanded = isExpanded, onExpandedChange = { isExpanded = !isExpanded }) {
        OutlinedTextField(
            value = selectionText,
            onValueChange = {},
            readOnly = true,
            label = { Text("Subscribed Sessions") },
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
                        onUpdate(agent.copy(subscribedSessionIds = newSelection))
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KnowledgeGraphSelector(agent: AgentInstance, agentState: AgentRuntimeState, onUpdate: (AgentInstance) -> Unit) {
    val currentKgId = agent.getKnowledgeGraphId()
    val availableGraphs = remember(agentState.knowledgeGraphNames, agentState.hkgReservedIds, currentKgId) {
        agentState.knowledgeGraphNames.entries.filter { (id, _) ->
            !agentState.hkgReservedIds.contains(id) || id == currentKgId
        }
    }
    var isExpanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = isExpanded, onExpandedChange = { isExpanded = !isExpanded }) {
        OutlinedTextField(
            value = currentKgId?.let { agentState.knowledgeGraphNames[it] } ?: "None",
            onValueChange = {}, readOnly = true, label = { Text("Knowledge Graph") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(isExpanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = isExpanded, onDismissRequest = { isExpanded = false }) {
            DropdownMenuItem(text = { Text("None") }, onClick = {
                onUpdate(agent.withKnowledgeGraphId(null))
                isExpanded = false
            })
            availableGraphs.forEach { (graphId, graphName) ->
                DropdownMenuItem(text = { Text(graphName) }, onClick = {
                    onUpdate(agent.withKnowledgeGraphId(graphId))
                    isExpanded = false
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutputSessionSelector(agent: AgentInstance, agentState: AgentRuntimeState, onUpdate: (AgentInstance) -> Unit) {
    val subscribedSessions = remember(agent.subscribedSessionIds, agentState.subscribableSessionNames) {
        agent.subscribedSessionIds.mapNotNull { id ->
            agentState.subscribableSessionNames[id]?.let { name -> id to name }
        }
    }
    // Show the explicitly set output session, or indicate the effective default
    val currentOutputName = when {
        agent.outputSessionId != null ->
            agentState.subscribableSessionNames[agent.outputSessionId] ?: agent.outputSessionId.uuid
        subscribedSessions.isNotEmpty() ->
            "${subscribedSessions.first().second} (default)"
        else -> "No sessions subscribed"
    }
    var isExpanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = isExpanded, onExpandedChange = { isExpanded = !isExpanded }) {
        OutlinedTextField(
            value = currentOutputName,
            onValueChange = {}, readOnly = true, label = { Text("Primary Session") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(isExpanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            enabled = subscribedSessions.isNotEmpty()
        )
        ExposedDropdownMenu(expanded = isExpanded, onDismissRequest = { isExpanded = false }) {
            subscribedSessions.forEach { (sessionId, sessionName) ->
                val isSelected = sessionId == agent.outputSessionId
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isSelected) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(sessionName)
                        }
                    },
                    onClick = {
                        onUpdate(agent.copy(outputSessionId = sessionId))
                        isExpanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderSelector(agent: AgentInstance, agentState: AgentRuntimeState, onUpdate: (AgentInstance) -> Unit) {
    val availableProviders = remember(agentState.availableModels) {
        // Only surface providers that have at least one model — this hides any
        // provider whose API key is blank (their listAvailableModels returns emptyList()).
        agentState.availableModels.filter { (_, models) -> models.isNotEmpty() }.keys.toList()
    }

    if (availableProviders.isEmpty()) {
        // Replace the entire selector with a clear, actionable message. A disabled
        // dropdown with helper text underneath is ambiguous — when nothing can be
        // selected there is no reason to show a selector widget at all.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceDim,
                    shape = MaterialTheme.shapes.small
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column {
                Text(
                    text = "No providers configured",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Use settings to configure API keys to enable models.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

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
                    onUpdate(agent.copy(
                        modelProvider = providerId,
                        modelName = agentState.availableModels[providerId]?.firstOrNull() ?: agent.modelName
                    ))
                    isExpanded = false
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelector(agent: AgentInstance, agentState: AgentRuntimeState, onUpdate: (AgentInstance) -> Unit) {
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
                    onUpdate(agent.copy(modelName = modelName))
                    isExpanded = false
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StrategySelector(agent: AgentInstance, onUpdate: (AgentInstance) -> Unit) {
    val strategies = remember { CognitiveStrategyRegistry.getAll() }
    var isExpanded by remember { mutableStateOf(false) }
    val currentStrategy = strategies.find { it.identityHandle == agent.cognitiveStrategyId }

    ExposedDropdownMenuBox(expanded = isExpanded, onExpandedChange = { isExpanded = !isExpanded }) {
        OutlinedTextField(
            value = currentStrategy?.displayName ?: agent.cognitiveStrategyId.handle,
            onValueChange = {}, readOnly = true, label = { Text("Strategy") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(isExpanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = isExpanded, onDismissRequest = { isExpanded = false }) {
            strategies.forEach { strategy ->
                DropdownMenuItem(text = { Text(strategy.displayName) }, onClick = {
                    // Reset cognitiveState to the new strategy's initial state on switch.
                    // Each strategy defines its own defaults — the core never inspects them.
                    val updated = agent.copy(
                        cognitiveStrategyId = strategy.identityHandle,
                        cognitiveState = strategy.getInitialState()
                    )
                    onUpdate(updated)
                    isExpanded = false
                })
            }
        }
    }
}

// =============================================================================
// Resource Slot Selector — Connects an Agent config slot to a System Resource
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResourceSlotSelector(
    label: String,
    slotKey: String,
    resourceType: AgentResourceType,
    agentState: AgentRuntimeState,
    agent: AgentInstance,
    onUpdate: (AgentInstance) -> Unit
) {
    val filteredResources = remember(agentState.resources, resourceType) {
        agentState.resources.filter { it.type == resourceType }
    }
    val selectedId = agent.resources[slotKey]
    val selectedName = filteredResources.find { it.id == selectedId?.uuid }?.name ?: "None"
    var isExpanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = isExpanded, onExpandedChange = { isExpanded = !isExpanded }) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(isExpanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = isExpanded, onDismissRequest = { isExpanded = false }) {
            DropdownMenuItem(text = { Text("None") }, onClick = {
                onUpdate(agent.copy(resources = agent.resources - slotKey))
                isExpanded = false
            })
            filteredResources.forEach { resource ->
                DropdownMenuItem(text = { Text(resource.name) }, onClick = {
                    onUpdate(agent.copy(resources = agent.resources + (slotKey to IdentityUUID(resource.id))))
                    isExpanded = false
                })
            }
        }
    }
}