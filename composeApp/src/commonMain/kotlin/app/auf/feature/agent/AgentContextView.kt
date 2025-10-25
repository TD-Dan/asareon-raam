package app.auf.feature.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.auf.core.Action
import app.auf.core.Store
import app.auf.core.generated.ActionNames
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentContextView(store: Store) {
    val appState by store.state.collectAsState()
    val agentState = appState.featureStates["agent"] as? AgentRuntimeState
    val agent = agentState?.viewingContextForAgentId?.let { agentState.agents[it] }

    if (agent == null || agent.stagedPreviewData == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No preview data available for agent.")
        }
        return
    }

    val onDiscard = {
        store.dispatch("ui.contextView", Action(ActionNames.AGENT_DISCARD_PREVIEW, buildJsonObject { put("agentId", agent.id) }))
    }
    val onExecute = {
        store.dispatch("ui.contextView", Action(ActionNames.AGENT_EXECUTE_PREVIEWED_TURN, buildJsonObject { put("agentId", agent.id) }))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Preview Turn: ${agent.name}") },
                navigationIcon = {
                    IconButton(onClick = onDiscard) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar {
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
                0 -> LogicalContextPane(agent.stagedPreviewData)
                1 -> RawJsonPane(agent.stagedPreviewData)
            }
        }
    }
}

@Composable
private fun LogicalContextPane(previewData: StagedPreviewData) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(previewData.agnosticRequest.contents) { message ->
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text = "ROLE: ${message.role.uppercase()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    SelectionContainer {
                        Text(text = message.content, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun RawJsonPane(previewData: StagedPreviewData) {
    Box(
        modifier = Modifier
            .fillMaxSize()
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
}