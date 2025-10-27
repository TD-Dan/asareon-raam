package app.auf.feature.knowledgegraph

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
    val selectedHolon = kgState?.holons?.get(kgState.activeHolonId)

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
            if (kgState?.rootHolonId == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when {
                        kgState?.isLoading == true -> CircularProgressIndicator()
                        kgState?.fatalError != null -> Text(kgState.fatalError, color = MaterialTheme.colorScheme.error)
                        else -> Text("Select a Persona to load its Knowledge Graph.")
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
    val rootHolon = kgState.rootHolonId?.let { kgState.holons[it] }

    LazyColumn(modifier = modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
        if (rootHolon != null) {
            item {
                HolonTreeItem(
                    holon = rootHolon,
                    selectedHolonId = kgState.activeHolonId,
                    store = store
                )
            }
            // Render the rest of the tree recursively
            rootHolon.header.subHolons.sortedBy { it.name }.forEach { subRef ->
                val childHolon = kgState.holons[subRef.id]
                if (childHolon != null) {
                    item {
                        RecursiveHolonTree(
                            holon = childHolon,
                            allHolons = kgState.holons,
                            selectedHolonId = kgState.activeHolonId,
                            store = store
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecursiveHolonTree(
    holon: Holon,
    allHolons: Map<String, Holon>,
    selectedHolonId: String?,
    store: Store
) {
    HolonTreeItem(holon = holon, selectedHolonId = selectedHolonId, store = store)
    holon.header.subHolons.sortedBy { it.name }.forEach { subRef ->
        val childHolon = allHolons[subRef.id]
        if (childHolon != null) {
            RecursiveHolonTree(
                holon = childHolon,
                allHolons = allHolons,
                selectedHolonId = selectedHolonId,
                store = store
            )
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
            .clickable { store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_SELECT_HOLON, buildJsonObject { put("holonId", header.id) })) }
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
        } else if (holon.content == null) {
            CircularProgressIndicator() // Content is being fetched
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
private fun PersonaSelector(kgState: KnowledgeGraphState?, store: Store) {
    if (kgState == null) return
    var isExpanded by remember { mutableStateOf(false) }
    val activePersonaName = kgState.availablePersonas.find { it.id == kgState.activePersonaId }?.name ?: "Select Persona"

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
            kgState.availablePersonas.forEach { persona ->
                DropdownMenuItem(
                    text = { Text(persona.name) },
                    onClick = {
                        store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_SELECT_PERSONA, buildJsonObject { put("personaId", persona.id) }))
                        isExpanded = false
                    }
                )
            }
        }
    }
}