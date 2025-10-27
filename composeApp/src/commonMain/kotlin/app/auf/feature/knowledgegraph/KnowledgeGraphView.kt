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
import androidx.compose.ui.text.font.FontWeight
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
    val selectedHolon = kgState?.holons?.get(kgState.activeHolonIdForView)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Knowledge Graph Manager") },
                actions = {
                    PersonaSelector(kgState, store)
                    // TODO: Re-implement Create Persona functionality
                    IconButton(onClick = { /* TODO */ }) {
                        Icon(Icons.Default.Add, contentDescription = "Create New Persona")
                    }
                }
            )
        }
    ) { paddingValues ->
        Row(Modifier.fillMaxSize().padding(paddingValues)) {
            val activePersonaRootId = kgState?.activePersonaIdForView?.let { kgState.personaRoots.entries.find { (_, v) -> v == it }?.value }

            if (activePersonaRootId == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when {
                        kgState?.isLoading == true -> CircularProgressIndicator()
                        kgState?.fatalError != null -> Text(kgState.fatalError, color = MaterialTheme.colorScheme.error)
                        else -> Text("Select a Persona to display its Knowledge Graph.")
                    }
                }
            } else {
                HolonTreeView(
                    kgState = kgState,
                    store = store,
                    modifier = Modifier.width(400.dp)
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
private fun HolonTreeView(kgState: KnowledgeGraphState, store: Store, modifier: Modifier = Modifier) {
    val rootHolon = kgState.activePersonaIdForView?.let { kgState.holons[it] }

    LazyColumn(modifier = modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
        if (rootHolon != null) {
            // Create a list of all holons to render for the active tree
            val treeHolons = mutableListOf<Holon>()
            fun buildTreeList(holon: Holon) {
                treeHolons.add(holon)
                holon.header.subHolons.sortedBy { it.name }.forEach { subRef ->
                    kgState.holons[subRef.id]?.let { buildTreeList(it) }
                }
            }
            buildTreeList(rootHolon)

            items(treeHolons, key = { it.header.id }) { holon ->
                HolonTreeItem(
                    holon = holon,
                    selectedHolonId = kgState.activeHolonIdForView,
                    store = store
                )
            }
        }
    }
}

@Composable
private fun HolonTreeItem(holon: Holon, selectedHolonId: String?, store: Store) {
    val header = holon.header
    ListItem(
        headlineContent = {
            Text(
                header.name,
                maxLines = 1,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (header.id == selectedHolonId) FontWeight.Bold else FontWeight.Normal
            )
        },
        supportingContent = { Text(header.summary ?: header.type, maxLines = 1, style = MaterialTheme.typography.bodySmall) },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_SET_ACTIVE_VIEW_HOLON, buildJsonObject { put("holonId", header.id) })) }
            .background(if (header.id == selectedHolonId) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
            .padding(start = (header.depth * 16).dp, end = 16.dp)
    )
    HorizontalDivider()
}


@Composable
private fun HolonDetailView(holon: Holon?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (holon == null) {
            Text("Select a holon to view its content.")
        } else {
            // Per the eager-loading directive, content is always present.
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
private fun PersonaSelector(kgState: KnowledgeGraphState?, store: Store) {
    if (kgState == null) return
    var isExpanded by remember { mutableStateOf(false) }
    val activePersonaName = kgState.personaRoots.entries.find { it.value == kgState.activePersonaIdForView }?.key ?: "Select Persona"

    ExposedDropdownMenuBox(expanded = isExpanded, onExpandedChange = { isExpanded = !isExpanded }) {
        OutlinedTextField(
            value = activePersonaName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Active Persona") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(isExpanded) },
            modifier = Modifier.menuAnchor().width(250.dp).padding(end = 8.dp)
        )
        ExposedDropdownMenu(expanded = isExpanded, onDismissRequest = { isExpanded = false }) {
            kgState.personaRoots.entries.sortedBy { it.key }.forEach { (name, id) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_SET_ACTIVE_VIEW_PERSONA, buildJsonObject { put("personaId", id) }))
                        isExpanded = false
                    }
                )
            }
        }
    }
}