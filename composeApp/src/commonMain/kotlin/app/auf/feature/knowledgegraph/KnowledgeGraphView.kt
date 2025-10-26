package app.auf.feature.knowledgegraph

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
fun KnowledgeGraphView(store: Store) {
    val appState by store.state.collectAsState()
    val kgState = appState.featureStates["knowledgegraph"] as? KnowledgeGraphState
    val activeGraph = kgState?.graphs?.find { it.id == kgState.activeGraphId }
    val selectedHolon = activeGraph?.holons?.find { it.id == kgState.activeHolonId }

    var showCreateDialog by remember { mutableStateOf(false) }

    if (showCreateDialog) {
        CreateGraphDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_CREATE_GRAPH, buildJsonObject { put("name", name) }))
                showCreateDialog = false
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Knowledge Graph Manager") },
                actions = {
                    GraphSelector(kgState, store)
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Create New Graph")
                    }
                }
            )
        }
    ) { paddingValues ->
        Row(Modifier.fillMaxSize().padding(paddingValues)) {
            if (activeGraph == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Select or create a Knowledge Graph to begin.")
                }
            } else {
                HolonMasterList(
                    graph = activeGraph,
                    selectedHolonId = kgState?.activeHolonId,
                    store = store,
                    modifier = Modifier.width(350.dp)
                )
                VerticalDivider()
                HolonDetailView(
                    holon = selectedHolon,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun HolonMasterList(graph: HolonKnowledgeGraph, selectedHolonId: String?, store: Store, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
        items(graph.holons.sortedBy { it.name }, key = { it.id }) { holon ->
            ListItem(
                headlineContent = { Text(holon.name, maxLines = 1, style = MaterialTheme.typography.titleSmall) },
                supportingContent = { Text(holon.summary ?: holon.type, maxLines = 1, style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_SELECT_HOLON, buildJsonObject { put("holonId", holon.id) })) }
                    .background(if (holon.id == selectedHolonId) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp)
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun HolonDetailView(holon: Holon?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (holon == null) {
            Text("Select a holon to view its content.")
        } else if (holon.content == null) {
            CircularProgressIndicator()
        } else {
            SelectionContainer(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = holon.content,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GraphSelector(kgState: KnowledgeGraphState?, store: Store) {
    if (kgState == null) return
    var isExpanded by remember { mutableStateOf(false) }
    val activeGraphName = kgState.graphs.find { it.id == kgState.activeGraphId }?.name ?: "Select Graph"

    ExposedDropdownMenuBox(expanded = isExpanded, onExpandedChange = { isExpanded = !isExpanded }) {
        OutlinedTextField(
            value = activeGraphName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Active HKG") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(isExpanded) },
            modifier = Modifier.menuAnchor().width(250.dp).padding(end = 8.dp)
        )
        ExposedDropdownMenu(expanded = isExpanded, onDismissRequest = { isExpanded = false }) {
            kgState.graphs.forEach { graph ->
                DropdownMenuItem(
                    text = { Text(graph.name) },
                    onClick = {
                        store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_SELECT_GRAPH_SCOPE, buildJsonObject { put("graphId", graph.id) }))
                        isExpanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun CreateGraphDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New Knowledge Graph") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Graph Name") },
                singleLine = true
            )
        },
        confirmButton = {
            Button(onClick = { onCreate(name) }, enabled = name.isNotBlank()) {
                Text("Create")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}