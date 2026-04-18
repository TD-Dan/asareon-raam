package asareon.raam.feature.agent.ui

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
import asareon.raam.core.*
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.feature.agent.AgentInstance
import asareon.raam.feature.agent.AgentResource
import asareon.raam.feature.agent.AgentResourceType
import asareon.raam.feature.agent.AgentRuntimeState
import asareon.raam.feature.agent.CognitiveStrategy
import asareon.raam.feature.agent.CognitiveStrategyRegistry
import asareon.raam.feature.agent.StrategyConfigField
import asareon.raam.feature.agent.StrategyConfigFieldType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import asareon.raam.ui.components.CodeEditor
import asareon.raam.ui.components.destructive.ConfirmDestructiveDialog
import asareon.raam.ui.components.destructive.DangerDropdownMenuItem
import asareon.raam.ui.components.footer.FooterActionEmphasis
import asareon.raam.ui.components.footer.FooterButton
import asareon.raam.ui.components.footer.ViewFooter
import asareon.raam.ui.components.identity.IdentityDraft
import asareon.raam.ui.components.identity.IdentityFieldsSection
import asareon.raam.ui.components.identity.toDraft
import asareon.raam.ui.components.topbar.HeaderAction
import asareon.raam.ui.components.topbar.HeaderActionEmphasis
import asareon.raam.ui.components.topbar.HeaderLeading
import asareon.raam.ui.components.topbar.RaamTopBarHeader
import asareon.raam.ui.theme.spacing
import asareon.raam.util.PlatformDependencies
import kotlinx.serialization.json.*
import kotlinx.serialization.json.put

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

@Composable
fun AgentManagerView(store: Store, platformDependencies: PlatformDependencies) {
    val appState by store.state.collectAsState()
    val agentState = remember(appState.featureStates) {
        appState.featureStates["agent"] as? AgentRuntimeState
    }

    LaunchedEffect(Unit) {
        store.dispatch("agent", Action(ActionRegistry.Names.GATEWAY_REQUEST_AVAILABLE_MODELS))
    }

    // System Resources is a secondary destination, not a peer tab — the
    // existing activeManagerTab state (0 = Agents, 1 = Resources) still drives
    // which subview is showing, so state rehydration after restart keeps working.
    val showingResources = agentState?.activeManagerTab == 1
    val setTab: (Int) -> Unit = { index ->
        store.dispatch(
            "agent",
            Action(
                ActionRegistry.Names.AGENT_SET_MANAGER_TAB,
                buildJsonObject { put("tabIndex", index) },
            ),
        )
    }

    // Hoisted from AgentListView so the header's expand/collapse toggle and
    // the agent cards below share the same state.
    var globalShowExtendedInfo by remember { mutableStateOf(false) }

    var editTarget by remember { mutableStateOf<AgentEditTarget?>(null) }

    // Cross-view edit request: the session-context agent card dispatches
    // CORE_SET_ACTIVE_VIEW + AGENT_SET_EDITING(uuid) to reach this view with
    // a specific agent primed for edit. Consume that signal here, then clear
    // it so reopening the manager later doesn't re-open the editor.
    val pendingEditId = agentState?.editingAgentId
    LaunchedEffect(pendingEditId) {
        if (pendingEditId != null && editTarget == null) {
            editTarget = AgentEditTarget.Edit(pendingEditId)
            store.dispatch("agent", Action(
                ActionRegistry.Names.AGENT_SET_EDITING,
                buildJsonObject { put("agentId", pendingEditId.uuid) },
            ))
        }
    }

    if (editTarget != null && agentState != null) {
        AgentEditorView(
            store = store,
            target = editTarget!!,
            agentState = agentState,
            onClose = { editTarget = null },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        if (!showingResources) {
            RaamTopBarHeader(
                title = "Agents",
                leading = HeaderLeading.Back(onClick = {
                    store.dispatch("core", Action(ActionRegistry.Names.CORE_SHOW_DEFAULT_VIEW))
                }),
                actions = listOf(
                    HeaderAction(
                        id = "create-agent",
                        label = "Create Agent",
                        icon = Icons.Default.Add,
                        priority = 30,
                        emphasis = HeaderActionEmphasis.Create,
                        onClick = { editTarget = AgentEditTarget.Create },
                    ),
                    HeaderAction(
                        id = "view-resources",
                        label = "System Resources",
                        icon = Icons.Default.SettingsSystemDaydream,
                        priority = 20,
                        emphasis = HeaderActionEmphasis.Prominent,
                        onClick = { setTab(1) },
                    ),
                    HeaderAction(
                        id = "toggle-agent-details",
                        label = if (globalShowExtendedInfo) "Collapse all details"
                            else "Expand all details",
                        icon = if (globalShowExtendedInfo) Icons.Default.VisibilityOff
                            else Icons.Default.Visibility,
                        priority = 10,
                        onClick = { globalShowExtendedInfo = !globalShowExtendedInfo },
                    ),
                ),
            )
            Box(Modifier.fillMaxSize()) {
                if (agentState == null) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Loading...") }
                } else {
                    AgentListView(
                        agentState = agentState,
                        store = store,
                        platformDependencies = platformDependencies,
                        showExtendedInfo = globalShowExtendedInfo,
                        onEditRequest = { agent ->
                            agent.identity.uuid?.let { editTarget = AgentEditTarget.Edit(IdentityUUID(it)) }
                        },
                    )
                }
            }
        } else {
            RaamTopBarHeader(
                title = "System Resources",
                subtitle = "Agents",
                leading = HeaderLeading.Back(onClick = { setTab(0) }),
            )
            Box(Modifier.fillMaxSize()) {
                if (agentState == null) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Loading...") }
                } else {
                    AgentResourcesView(agentState, store)
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
    platformDependencies: PlatformDependencies,
    showExtendedInfo: Boolean,
    onEditRequest: (AgentInstance) -> Unit,
) {
    var agentToDelete by remember { mutableStateOf<AgentInstance?>(null) }

    agentToDelete?.let { agent ->
        ConfirmDestructiveDialog(
            title = "Delete Agent?",
            message = "Permanently delete '${agent.identity.name}'? This action cannot be undone.",
            onConfirm = {
                store.dispatch(
                    "agent",
                    Action(
                        ActionRegistry.Names.AGENT_DELETE,
                        buildJsonObject { put("agentId", agent.identity.uuid) },
                    ),
                )
                agentToDelete = null
            },
            onDismiss = { agentToDelete = null },
        )
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
                    agentState = agentState,
                    store = store,
                    onDeleteRequest = { agentToDelete = it },
                    onEditRequest = { onEditRequest(agent) },
                    platformDependencies = platformDependencies,
                    showExtendedInfo = showExtendedInfo
                )
            }
        }
    }
}

@Composable
private fun AgentCard(
    agent: AgentInstance,
    agentState: AgentRuntimeState,
    store: Store,
    onDeleteRequest: (AgentInstance) -> Unit,
    onEditRequest: () -> Unit,
    platformDependencies: PlatformDependencies,
    showExtendedInfo: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
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

// =============================================================================
// Agent Editor — Full-view, draft-pattern, IdentityFieldsSection + ViewFooter
// All changes are local until Save. Cancel prompts to discard if dirty.
// =============================================================================

/**
 * Which agent the full-view editor is targeting. [Create] for a fresh agent,
 * [Edit] wraps the UUID of an existing one.
 */
private sealed interface AgentEditTarget {
    data object Create : AgentEditTarget
    data class Edit(val uuid: IdentityUUID) : AgentEditTarget
}

/** Defaults used when the editor opens in Create mode. */
private fun defaultDraftAgent(): AgentInstance = AgentInstance(
    identity = Identity(
        uuid = null,
        localHandle = "",
        handle = "",
        name = "",
        parentHandle = "agent",
    ),
    modelProvider = "gemini",
    modelName = "gemini-pro",
    cognitiveStrategyId = CognitiveStrategyRegistry.getDefault().identityHandle,
    cognitiveState = CognitiveStrategyRegistry.getDefault().getInitialState(),
    strategyConfig = JsonObject(emptyMap()),
    subscribedSessionIds = emptyList(),
    automaticMode = false,
    autoWaitTimeSeconds = 5,
    autoMaxWaitTimeSeconds = 30,
    contextBudgetChars = 50_000,
    contextMaxBudgetChars = 150_000,
    contextMaxPartialChars = 20_000,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentEditorView(
    store: Store,
    target: AgentEditTarget,
    agentState: AgentRuntimeState,
    onClose: () -> Unit,
) {
    // Resolve the source agent (existing for Edit, defaults for Create).
    val sourceAgent: AgentInstance = when (target) {
        is AgentEditTarget.Create -> remember(target) { defaultDraftAgent() }
        is AgentEditTarget.Edit -> agentState.agents[target.uuid] ?: run {
            // Target was deleted while the editor was open — bail out cleanly.
            LaunchedEffect(Unit) { onClose() }
            return
        }
    }

    // Initial identity draft: for Edit, read from the identity registry (authoritative);
    // for Create, the source agent's (default) identity already holds the draft shape.
    val identityRegistry = store.state.collectAsState().value.identityRegistry
    val initialIdentity = remember(target) {
        when (target) {
            is AgentEditTarget.Create -> sourceAgent.identity
            is AgentEditTarget.Edit -> identityRegistry[sourceAgent.identity.handle] ?: sourceAgent.identity
        }
    }

    var identityDraft by remember(target) { mutableStateOf(initialIdentity.toDraft()) }
    var draftAgent by remember(target) { mutableStateOf(sourceAgent) }

    var autoWaitTimeInput by remember(target) { mutableStateOf(sourceAgent.autoWaitTimeSeconds.toString()) }
    var autoMaxWaitTimeInput by remember(target) { mutableStateOf(sourceAgent.autoMaxWaitTimeSeconds.toString()) }
    var budgetOptimalTokensInput by remember(target) { mutableStateOf((sourceAgent.contextBudgetChars / 4).toString()) }
    var budgetMaxTokensInput by remember(target) { mutableStateOf((sourceAgent.contextMaxBudgetChars / 4).toString()) }
    var budgetMaxPartialTokensInput by remember(target) { mutableStateOf((sourceAgent.contextMaxPartialChars / 4).toString()) }

    val initialDraftAgent = remember(target) { sourceAgent }
    val initialIdentityDraft = remember(target) { initialIdentity.toDraft() }
    val initialAutoWait = remember(target) { sourceAgent.autoWaitTimeSeconds.toString() }
    val initialAutoMaxWait = remember(target) { sourceAgent.autoMaxWaitTimeSeconds.toString() }
    val initialBudgetOptimal = remember(target) { (sourceAgent.contextBudgetChars / 4).toString() }
    val initialBudgetMax = remember(target) { (sourceAgent.contextMaxBudgetChars / 4).toString() }
    val initialBudgetMaxPartial = remember(target) { (sourceAgent.contextMaxPartialChars / 4).toString() }

    val dirty = identityDraft != initialIdentityDraft ||
        draftAgent != initialDraftAgent ||
        autoWaitTimeInput != initialAutoWait ||
        autoMaxWaitTimeInput != initialAutoMaxWait ||
        budgetOptimalTokensInput != initialBudgetOptimal ||
        budgetMaxTokensInput != initialBudgetMax ||
        budgetMaxPartialTokensInput != initialBudgetMaxPartial
    // Save is gated only by name validity — the agent form has so many fields
    // that a strict dirty gate risks false negatives (e.g., JsonObject reference
    // churn on strategy config). `dirty` still drives the discard confirmation.
    val canSave = identityDraft.name.isNotBlank()

    var showDiscardDialog by remember { mutableStateOf(false) }
    val tryClose = { if (dirty) showDiscardDialog = true else onClose() }

    val onDraftChanged: (AgentInstance) -> Unit = { draftAgent = it }

    val onSave = save@{
        val payload = buildJsonObject {
            when (target) {
                is AgentEditTarget.Edit -> put("agentId", target.uuid.uuid)
                is AgentEditTarget.Create -> { /* agentId is assigned by the feature */ }
            }
            put("name", identityDraft.name)
            put("cognitiveStrategyId", draftAgent.cognitiveStrategyId.handle)
            put("modelProvider", draftAgent.modelProvider)
            put("modelName", draftAgent.modelName)
            put("subscribedSessionIds", buildJsonArray { draftAgent.subscribedSessionIds.forEach { add(it.uuid) } })
            if (draftAgent.outputSessionId != null) put("outputSessionId", draftAgent.outputSessionId!!.uuid)
            else put("outputSessionId", null as String?)
            put("strategyConfig", draftAgent.strategyConfig)
            put("automaticMode", draftAgent.automaticMode)
            autoWaitTimeInput.toIntOrNull()?.let { put("autoWaitTimeSeconds", it) }
            autoMaxWaitTimeInput.toIntOrNull()?.let { put("autoMaxWaitTimeSeconds", it) }
            budgetOptimalTokensInput.toIntOrNull()?.let { put("contextBudgetChars", it * 4) }
            budgetMaxTokensInput.toIntOrNull()?.let { put("contextMaxBudgetChars", it * 4) }
            budgetMaxPartialTokensInput.toIntOrNull()?.let { put("contextMaxPartialChars", it * 4) }
            put("resources", Json.encodeToJsonElement(draftAgent.resources))
            put("displayColor", identityDraft.displayColor)
            put("displayIcon", identityDraft.displayIcon)
            put("displayEmoji", identityDraft.displayEmoji)
        }
        val actionName = when (target) {
            is AgentEditTarget.Create -> ActionRegistry.Names.AGENT_CREATE
            is AgentEditTarget.Edit -> ActionRegistry.Names.AGENT_UPDATE_CONFIG
        }
        store.dispatch("agent", Action(actionName, payload))
        onClose()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    tryClose(); true
                } else false
            }
    ) {
        RaamTopBarHeader(
            title = when (target) {
                is AgentEditTarget.Create -> "New Agent"
                is AgentEditTarget.Edit -> initialIdentity.name.ifBlank { "Edit Agent" }
            },
            subtitle = "Agents",
            leading = HeaderLeading.Back(onClick = { tryClose() }),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = MaterialTheme.spacing.screenEdge,
                    vertical = MaterialTheme.spacing.itemGap,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IdentityFieldsSection(
                draft = identityDraft,
                onDraftChange = { identityDraft = it },
                nameLabel = "Agent Name",
            )

            // --- Strategy selector (identity's sibling at the top of the form) ---
            StrategySelector(draftAgent, onDraftChanged)

            // --- Compute (Provider + Model) ---
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { ProviderSelector(draftAgent, agentState, onDraftChanged) }
                Box(Modifier.weight(1f)) { ModelSelector(draftAgent, agentState, onDraftChanged) }
            }

            // --- Subscriptions ---
            Row(Modifier.fillMaxWidth()) {
                MultiSessionSelector(draftAgent, agentState, onDraftChanged)
            }

            // --- Strategy-specific settings ---
            val currentStrategy = remember(draftAgent.cognitiveStrategyId) {
                if (CognitiveStrategyRegistry.getAll().isEmpty()) null
                else CognitiveStrategyRegistry.get(draftAgent.cognitiveStrategyId)
            }

            if (currentStrategy == null) {
                Text(
                    "Strategy unavailable — registry not initialised.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            val configFields = remember(currentStrategy) { currentStrategy?.getConfigFields() ?: emptyList() }
            if (configFields.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    "Strategy Settings (${currentStrategy?.displayName ?: "Unknown"})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                configFields.forEach { field ->
                    Row(Modifier.fillMaxWidth()) {
                        when (field.type) {
                            StrategyConfigFieldType.KNOWLEDGE_GRAPH ->
                                KnowledgeGraphSelector(draftAgent, agentState, onDraftChanged)
                            StrategyConfigFieldType.OUTPUT_SESSION ->
                                OutputSessionSelector(draftAgent, agentState, onDraftChanged, field, currentStrategy)
                        }
                    }
                }
            }

            // --- Resource slots ---
            val resourceSlots = remember(currentStrategy) { currentStrategy?.getResourceSlots() ?: emptyList() }
            if (resourceSlots.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    "Resources",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp),
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
                    onUpdate = onDraftChanged,
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
                                onUpdate = onDraftChanged,
                            )
                        }
                    }
                }
            }

            // --- Automatic Mode ---
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(
                "Automatic Operation",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Automatic Mode", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = draftAgent.automaticMode,
                    onCheckedChange = { draftAgent = draftAgent.copy(automaticMode = it) },
                )
            }
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = autoWaitTimeInput,
                    onValueChange = { autoWaitTimeInput = it.filter { c -> c.isDigit() } },
                    label = { Text("Auto Wait (s)") },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = autoMaxWaitTimeInput,
                    onValueChange = { autoMaxWaitTimeInput = it.filter { c -> c.isDigit() } },
                    label = { Text("Max Wait (s)") },
                    modifier = Modifier.weight(1f),
                )
            }

            // --- Context Budget ---
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(
                "Context Budget",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                "Controls the agent's context window size. Values are approximate tokens (~4 chars/token). " +
                    "The system auto-collapses partitions when the maximum is exceeded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = budgetOptimalTokensInput,
                    onValueChange = { budgetOptimalTokensInput = it.filter { c -> c.isDigit() } },
                    label = { Text("Optimal (~tokens)") },
                    supportingText = { Text("Soft target for best coherence") },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = budgetMaxTokensInput,
                    onValueChange = { budgetMaxTokensInput = it.filter { c -> c.isDigit() } },
                    label = { Text("Maximum (~tokens)") },
                    supportingText = { Text("Hard ceiling — auto-collapse fires") },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = budgetMaxPartialTokensInput,
                    onValueChange = { budgetMaxPartialTokensInput = it.filter { c -> c.isDigit() } },
                    label = { Text("Max Partial (~tokens)") },
                    supportingText = { Text("Single partition truncation limit") },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        ViewFooter {
            FooterButton(FooterActionEmphasis.Cancel, "Cancel", onClick = { tryClose() })
            FooterButton(
                emphasis = FooterActionEmphasis.Confirm,
                label = if (target is AgentEditTarget.Create) "Create" else "Save",
                onClick = { onSave() },
                enabled = canSave,
            )
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard changes?") },
            text = { Text("Your unsaved edits will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onClose()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("Keep editing") }
            },
        )
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
    var showDeleteDialog by remember { mutableStateOf(false) }
    var menuExpanded by remember(resource.id) { mutableStateOf(false) }

    if (showDeleteDialog) {
        ConfirmDestructiveDialog(
            title = "Delete Resource?",
            message = "Permanently delete '${resource.name}'? This action cannot be undone.",
            onConfirm = {
                store.dispatch(
                    "agent",
                    Action(
                        ActionRegistry.Names.AGENT_DELETE_RESOURCE,
                        buildJsonObject { put("resourceId", resource.id) },
                    ),
                )
                showDeleteDialog = false
            },
            onDismiss = { showDeleteDialog = false },
        )
    }

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

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!resource.isBuiltIn) {
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
                    Spacer(Modifier.width(4.dp))
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DangerDropdownMenuItem(
                                label = "Delete Resource",
                                onClick = {
                                    menuExpanded = false
                                    showDeleteDialog = true
                                },
                            )
                        }
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
private fun OutputSessionSelector(
    agent: AgentInstance,
    agentState: AgentRuntimeState,
    onUpdate: (AgentInstance) -> Unit,
    field: StrategyConfigField,
    strategy: CognitiveStrategy?
) {
    val isAutoManaged = strategy?.hasAutoManagedOutputSession == true

    if (isAutoManaged) {
        // Auto-managed private session — read-only info display.
        // The session is created by ensureInfrastructure and linked via SESSION_CREATED handler.
        val sessionStatus = when {
            agent.outputSessionId != null -> {
                // Linked — resolve display name from subscribable names or identity registry fallback.
                // Private sessions are excluded from subscribableSessionNames, so we show the UUID
                // with a "linked" indicator. The session name would need registry lookup at runtime.
                agentState.subscribableSessionNames[agent.outputSessionId]
                    ?: "${agent.outputSessionId.uuid.take(12)}… (private)"
            }
            else -> null
        }

        Column(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = sessionStatus ?: "Not yet created",
                onValueChange = {},
                readOnly = true,
                label = { Text(field.displayName) },
                enabled = false,
                trailingIcon = {
                    Icon(
                        imageVector = if (sessionStatus != null) Icons.Default.Lock else Icons.Default.HourglassEmpty,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = if (sessionStatus != null) "Auto-managed. Linked and active."
                else "Auto-managed. Will be created when the agent is activated.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
        }
    } else {
        // Standard editable dropdown — operator selects from subscribed sessions.
        val subscribedSessions = remember(agent.subscribedSessionIds, agentState.subscribableSessionNames) {
            agent.subscribedSessionIds.mapNotNull { id ->
                agentState.subscribableSessionNames[id]?.let { name -> id to name }
            }
        }
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
                onValueChange = {}, readOnly = true, label = { Text(field.displayName) },
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
                    // Clear outputSessionId when switching to an auto-managed strategy —
                    // the stale pointer from the previous strategy would block
                    // ensureInfrastructure from creating the private session.
                    val updated = agent.copy(
                        cognitiveStrategyId = strategy.identityHandle,
                        cognitiveState = strategy.getInitialState(),
                        outputSessionId = if (strategy.hasAutoManagedOutputSession) null else agent.outputSessionId
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