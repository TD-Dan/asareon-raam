package app.auf.feature.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Token
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.auf.core.Action
import app.auf.core.IdentityUUID
import app.auf.core.Store
import app.auf.core.generated.ActionRegistry
import app.auf.ui.components.hslToColor
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * Context Manager UI — replaces PreviewContextView (§6).
 *
 * Three tabs:
 * - Tab 0: Context Management — budget bar + partition cards (instant reassembly)
 * - Tab 1: API Preview — assembled system prompt + token estimate (debounced)
 * - Tab 2: Raw JSON Payload — raw gateway request JSON (debounced)
 */

private fun formatTokenCount(count: Int): String =
    count.toString().reversed().chunked(3).joinToString(",").reversed()

private const val GOLDEN_ANGLE = 137.508f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageContextView(store: Store) {
    val appState by store.state.collectAsState()
    val agentState = appState.featureStates["agent"] as? AgentRuntimeState
    val agentId = agentState?.managingContextForAgentId
    val agent = agentId?.let { agentState.agents[it] }
    val statusInfo = agentId?.let { agentState.agentStatuses[it] }
    val managedContext = statusInfo?.managedContext
    val managedPartitions = statusInfo?.managedPartitions

    if (agent == null || managedContext == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No managed context available for agent.")
        }
        return
    }

    val onDiscard = {
        store.dispatch("agent", Action(ActionRegistry.Names.AGENT_DISCARD_MANAGED_CONTEXT,
            buildJsonObject { put("agentId", agent.identity.uuid) }))
    }
    val onExecute = {
        store.dispatch("agent", Action(ActionRegistry.Names.AGENT_EXECUTE_MANAGED_TURN,
            buildJsonObject { put("agentId", agent.identity.uuid) }))
    }

    // Local state for content viewer dialog
    var viewingContent by remember { mutableStateOf<Pair<String, String>?>(null) } // title → content

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Context: ${agent.identity.name}") },
                navigationIcon = {
                    IconButton(onClick = onDiscard) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar {
                // Token estimate
                val tokens = statusInfo.managedContextEstimatedTokens
                    ?: managedPartitions?.totalChars?.let { it / ContextDelimiters.CHARS_PER_TOKEN }
                if (tokens != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Icon(Icons.Default.Token, contentDescription = "Tokens",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text("~${formatTokenCount(tokens)} tokens",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onDiscard,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                    Text("Cancel")
                }
                Spacer(Modifier.width(16.dp))
                Button(onClick = onExecute) { Text("Execute Turn") }
            }
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            var selectedTab by remember { mutableStateOf(0) }
            val tabs = listOf("Context Management", "API Preview", "Raw JSON Payload")

            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) })
                }
            }

            when (selectedTab) {
                0 -> ContextManagementPane(
                    agentId = agentId,
                    partitions = managedPartitions?.partitions ?: managedContext.partitions,
                    collapseResult = managedPartitions?.collapseResult ?: managedContext.collapseResult,
                    softBudgetChars = managedPartitions?.softBudgetChars ?: managedContext.softBudgetChars,
                    maxBudgetChars = managedPartitions?.maxBudgetChars ?: managedContext.maxBudgetChars,
                    store = store,
                    onViewContent = { title, content -> viewingContent = title to content }
                )
                1 -> ApiPreviewPane(managedContext, statusInfo, store)
                2 -> RawJsonPane(statusInfo, store)
            }
        }
    }

    // Content viewer dialog (local state per §6.6 Phase 1 alternative)
    viewingContent?.let { (title, content) ->
        ContentViewerDialog(
            title = title,
            content = content,
            onDismiss = { viewingContent = null },
            onCopy = {
                store.dispatch("agent", Action(ActionRegistry.Names.CORE_COPY_TO_CLIPBOARD,
                    buildJsonObject { put("text", content) }))
            }
        )
    }
}

// =============================================================================
// Tab 0: Context Management — Budget Bar + Partition Cards
// =============================================================================

@Composable
private fun ContextManagementPane(
    agentId: IdentityUUID,
    partitions: List<ContextCollapseLogic.ContextPartition>,
    collapseResult: ContextCollapseLogic.CollapseResult,
    softBudgetChars: Int,
    maxBudgetChars: Int,
    store: Store,
    onViewContent: (String, String) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Budget bar
        item {
            BudgetBar(
                partitions = partitions,
                totalChars = collapseResult.totalChars,
                softBudgetChars = softBudgetChars,
                maxBudgetChars = maxBudgetChars
            )
        }

        // Persistence notice (§6.7)
        item {
            Text(
                "Collapse settings persist across turns for this agent.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        // Partition cards — top-level only (children rendered inside groups)
        val topLevel = partitions.filter { it.parentKey == null }
        items(topLevel, key = { it.key }) { partition ->
            val children = partitions.filter { it.parentKey == partition.key }
            PartitionCard(
                partition = partition,
                children = children,
                allPartitions = partitions,
                agentId = agentId,
                store = store,
                onViewContent = onViewContent,
                depth = 0
            )
        }
    }
}

// =============================================================================
// Budget Bar (§6.2)
// =============================================================================

@Composable
private fun BudgetBar(
    partitions: List<ContextCollapseLogic.ContextPartition>,
    totalChars: Int,
    softBudgetChars: Int,
    maxBudgetChars: Int
) {
    val topLevel = partitions.filter { it.parentKey == null }
    val totalTokens = totalChars / ContextDelimiters.CHARS_PER_TOKEN
    val softTokens = softBudgetChars / ContextDelimiters.CHARS_PER_TOKEN
    val maxTokens = maxBudgetChars / ContextDelimiters.CHARS_PER_TOKEN

    val utilizationPct = if (maxBudgetChars > 0) (totalChars.toFloat() / maxBudgetChars * 100).roundToInt() else 0
    val isOverSoft = totalChars > softBudgetChars

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isOverSoft) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("~${formatTokenCount(totalTokens)} tokens",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("$utilizationPct% of max (~${formatTokenCount(maxTokens)})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))

            // Stacked segment bar — golden-angle hue rotation (§6.2)
            if (topLevel.isNotEmpty() && totalChars > 0) {
                Row(
                    Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))
                ) {
                    topLevel.forEachIndexed { index, partition ->
                        val weight = partition.effectiveCharCount.toFloat() / totalChars
                        if (weight > 0.005f) {
                            val hue = (index * GOLDEN_ANGLE) % 360f
                            Box(
                                Modifier
                                    .weight(weight)
                                    .fillMaxHeight()
                                    .background(hslToColor(hue, 0.55f, 0.55f))
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))

                // Soft budget marker
                val softPct = if (maxBudgetChars > 0) softBudgetChars.toFloat() / maxBudgetChars else 0.5f
                Text("Optimal: ~${formatTokenCount(softTokens)} tokens",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// =============================================================================
// Partition Card (§6.3)
// =============================================================================

@Composable
private fun PartitionCard(
    partition: ContextCollapseLogic.ContextPartition,
    children: List<ContextCollapseLogic.ContextPartition>,
    allPartitions: List<ContextCollapseLogic.ContextPartition>,
    agentId: IdentityUUID,
    store: Store,
    onViewContent: (String, String) -> Unit,
    depth: Int
) {
    val tokens = partition.effectiveCharCount / ContextDelimiters.CHARS_PER_TOKEN
    val isCollapsed = partition.state == CollapseState.COLLAPSED
    val isProtected = !partition.isAutoCollapsible

    val stateBadge = when {
        isProtected -> "PROTECTED"
        isCollapsed -> "COLLAPSED"
        else -> "EXPANDED"
    }

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            // Header row: key + token count
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    partition.key,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text("~${formatTokenCount(tokens)} tokens",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))

            // Controls row: toggle + view content
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Toggle button or plain label (§6.3)
                if (!isProtected && partition.isAutoCollapsible) {
                    TextButton(
                        onClick = {
                            val actionName = if (isCollapsed)
                                ActionRegistry.Names.AGENT_CONTEXT_UNCOLLAPSE
                            else
                                ActionRegistry.Names.AGENT_CONTEXT_COLLAPSE
                            store.dispatch("agent", Action(actionName, buildJsonObject {
                                put("agentId", agentId.uuid)
                                put("partitionKey", partition.key)
                            }))
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            if (isCollapsed) Icons.Default.UnfoldMore else Icons.Default.UnfoldLess,
                            contentDescription = stateBadge,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (isCollapsed) "COLLAPSED ▸" else "EXPANDED ▾",
                            style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    Text(stateBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp))
                }

                // View content button
                TextButton(onClick = {
                    val content = if (isCollapsed) partition.collapsedContent else partition.fullContent
                    onViewContent(partition.key, content)
                }) {
                    Text("View Content", style = MaterialTheme.typography.labelSmall)
                }
            }

            // Children (only when parent is expanded and has children)
            if (!isCollapsed && children.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                children.forEach { child ->
                    val grandchildren = allPartitions.filter { it.parentKey == child.key }
                    PartitionCard(
                        partition = child,
                        children = grandchildren,
                        allPartitions = allPartitions,
                        agentId = agentId,
                        store = store,
                        onViewContent = onViewContent,
                        depth = depth + 1
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

// =============================================================================
// Tab 1: API Preview
// =============================================================================

@Composable
private fun ApiPreviewPane(
    managedContext: ContextAssemblyResult,
    statusInfo: AgentStatusInfo,
    store: Store
) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Token estimate card
            statusInfo.managedContextEstimatedTokens?.let { estimate ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Token, contentDescription = "Tokens",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Text("Estimated input: ${formatTokenCount(estimate)} tokens",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            }

            // System prompt
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("SYSTEM PROMPT",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.Bold)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        SelectionContainer {
                            Text(managedContext.systemPrompt,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Button(onClick = {
                store.dispatch("agent", Action(ActionRegistry.Names.CORE_COPY_TO_CLIPBOARD,
                    buildJsonObject { put("text", managedContext.systemPrompt) }))
            }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy",
                    modifier = Modifier.padding(end = 8.dp))
                Text("Copy All")
            }
        }
    }
}

// =============================================================================
// Tab 2: Raw JSON Payload
// =============================================================================

@Composable
private fun RawJsonPane(statusInfo: AgentStatusInfo, store: Store) {
    val rawJson = statusInfo.managedContextRawJson

    Column(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(16.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                .padding(1.dp)
        ) {
            if (rawJson != null) {
                SelectionContainer {
                    Text(
                        text = rawJson,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxSize().padding(12.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Waiting for gateway preview...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = {
                    rawJson?.let {
                        store.dispatch("agent", Action(ActionRegistry.Names.CORE_COPY_TO_CLIPBOARD,
                            buildJsonObject { put("text", it) }))
                    }
                },
                enabled = rawJson != null
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy",
                    modifier = Modifier.padding(end = 8.dp))
                Text("Copy All")
            }
        }
    }
}

// =============================================================================
// Content Viewer Dialog (§6.6 — local dialog state)
// =============================================================================

@Composable
private fun ContentViewerDialog(
    title: String,
    content: String,
    onDismiss: () -> Unit,
    onCopy: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                    .padding(8.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onCopy(); onDismiss() }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy",
                    modifier = Modifier.size(16.dp).padding(end = 4.dp))
                Text("Copy")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}