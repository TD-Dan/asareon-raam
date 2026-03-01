package app.auf.feature.agent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Token
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.auf.core.Action
import app.auf.core.Store
import app.auf.core.generated.ActionRegistry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Formats a token count for display. Examples: "1,234", "12,345".
 */
private fun formatTokenCount(count: Int): String {
    return count.toString().reversed().chunked(3).joinToString(",").reversed()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentContextView(store: Store) {
    val appState by store.state.collectAsState()
    val agentState = appState.featureStates["agent"] as? AgentRuntimeState
    val agent = agentState?.viewingContextForAgentId?.let { agentState.agents[it] }

    // REF: Slice 3 - Get preview data from StatusInfo
    val statusInfo = agentState?.viewingContextForAgentId?.let { agentState.agentStatuses[it] }
    val previewData = statusInfo?.stagedPreviewData

    if (agent == null || previewData == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No preview data available for agent.")
        }
        return
    }

    val onDiscard = {
        store.dispatch("ui.contextView", Action(ActionRegistry.Names.AGENT_DISCARD_PREVIEW, buildJsonObject { put("agentId", agent.identity.uuid) }))
    }
    val onExecute = {
        store.dispatch("ui.contextView", Action(ActionRegistry.Names.AGENT_EXECUTE_PREVIEWED_TURN, buildJsonObject { put("agentId", agent.identity.uuid) }))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Preview Turn: ${agent.identity.name}") },
                navigationIcon = {
                    IconButton(onClick = onDiscard) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar {
                // NEW: Show estimated token count in the bottom bar if available
                previewData.estimatedInputTokens?.let { estimate ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Icon(
                            Icons.Default.Token,
                            contentDescription = "Token estimate",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "~${formatTokenCount(estimate)} input tokens",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onDiscard, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                    Text("Cancel")
                }
                Spacer(Modifier.width(16.dp))
                Button(onClick = onExecute) {
                    Text("Execute Turn")
                }
            }
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            var selectedTab by remember { mutableStateOf(0) }
            val tabs = listOf("Logical Context", "Raw JSON Payload")

            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            when (selectedTab) {
                0 -> LogicalContextPane(previewData, store)
                1 -> RawJsonPane(previewData, store)
            }
        }
    }
}

@Composable
private fun LogicalContextPane(previewData: StagedPreviewData, store: Store) {
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // NEW: Token estimate info card at top
            previewData.estimatedInputTokens?.let { estimate ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Token,
                                contentDescription = "Token estimate",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = "Estimated input: ${formatTokenCount(estimate)} tokens",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }

            // FIX: Display the system prompt if it exists.
            previewData.agnosticRequest.systemPrompt?.let { systemPrompt ->
                item {
                    OutlinedCard(modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                text = "SYSTEM PROMPT",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                                fontWeight = FontWeight.Bold
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                thickness = DividerDefaults.Thickness,
                                color = DividerDefaults.color
                            )
                            SelectionContainer {
                                Text(text = systemPrompt, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            items(previewData.agnosticRequest.contents) { message ->
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = "ROLE: ${message.role.uppercase()}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            thickness = DividerDefaults.Thickness,
                            color = DividerDefaults.color
                        )
                        SelectionContainer {
                            Text(text = message.content, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Button(onClick = {
                val fullText = buildString {
                    previewData.agnosticRequest.systemPrompt?.let {
                        append("--- SYSTEM PROMPT ---\n")
                        append(it)
                        append("\n\n")
                    }
                    previewData.agnosticRequest.contents.forEach { message ->
                        append("--- ROLE: ${message.role.uppercase()} ---\n")
                        append(message.content)
                        append("\n\n")
                    }
                }
                store.dispatch("ui.contextView", Action(ActionRegistry.Names.CORE_COPY_TO_CLIPBOARD, buildJsonObject { put("text", fullText.trim()) }))
            }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy All", modifier = Modifier.padding(end = 8.dp))
                Text("Copy All")
            }
        }
    }
}

@Composable
private fun RawJsonPane(previewData: StagedPreviewData, store: Store) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(16.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                .padding(1.dp) // for border effect
        ) {
            SelectionContainer {
                Text(
                    text = previewData.rawRequestJson,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Button(onClick = {
                store.dispatch("ui.contextView", Action(ActionRegistry.Names.CORE_COPY_TO_CLIPBOARD, buildJsonObject { put("text", previewData.rawRequestJson) }))
            }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy All", modifier = Modifier.padding(end = 8.dp))
                Text("Copy All")
            }
        }
    }
}