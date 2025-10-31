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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
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
import app.auf.util.PlatformDependencies
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeGraphView(store: Store, platformDependencies: PlatformDependencies) {
    val appState by store.state.collectAsState()
    val kgState = appState.featureStates["knowledgegraph"] as? KnowledgeGraphState

    if (kgState == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Knowledge Graph Manager") },
                actions = {
                    if (kgState.viewMode == KnowledgeGraphViewMode.INSPECTOR) {
                        PersonaSelector(kgState, store)
                        IconButton(
                            onClick = { store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_SET_VIEW_MODE, buildJsonObject { put("mode", KnowledgeGraphViewMode.IMPORT.name) })) }
                        ) {
                            Icon(Icons.Default.Download, contentDescription = "Import Holons")
                        }
                        IconButton(onClick = { /* TODO Create Persona */ }) {
                            Icon(Icons.Default.Add, contentDescription = "Create New Persona")
                        }
                    } else {
                        IconButton(onClick = { store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_SET_VIEW_MODE, buildJsonObject { put("mode", KnowledgeGraphViewMode.INSPECTOR.name) })) }) {
                            Icon(Icons.Default.Close, contentDescription = "Close View")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        when (kgState.viewMode) {
            KnowledgeGraphViewMode.INSPECTOR -> InspectorPane(kgState, store, Modifier.padding(paddingValues))
            KnowledgeGraphViewMode.IMPORT -> ImportPane(kgState, store, platformDependencies, Modifier.padding(paddingValues))
            KnowledgeGraphViewMode.EXPORT -> { /* TODO */ }
        }
    }
}

@Composable
private fun InspectorPane(kgState: KnowledgeGraphState, store: Store, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxSize()) {
        val activePersonaRootId = kgState.activePersonaIdForView
        val selectedHolon = kgState.holons[kgState.activeHolonIdForView]

        if (activePersonaRootId == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    kgState.isLoading -> CircularProgressIndicator()
                    kgState.fatalError != null -> Text(kgState.fatalError, color = MaterialTheme.colorScheme.error)
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

@Composable
private fun HolonTreeView(kgState: KnowledgeGraphState, store: Store, modifier: Modifier = Modifier) {
    val rootHolon = kgState.activePersonaIdForView?.let { kgState.holons[it] }

    LazyColumn(modifier = modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
        if (rootHolon != null) {
            val treeHolons = mutableListOf<Holon>()
            fun buildTreeList(holon: Holon) {
                treeHolons.add(holon)
                holon.header.subHolons
                    .mapNotNull { subRef -> kgState.holons[subRef.id] }
                    .sortedBy { childHolon -> childHolon.header.name }
                    .forEach { sortedChildHolon -> buildTreeList(sortedChildHolon) }
            }
            buildTreeList(rootHolon)

            items(treeHolons, key = { it.header.id }) { holon ->
                HolonTreeItem(holon, kgState.activeHolonIdForView, store)
            }
        }
    }
}

@Composable
private fun HolonTreeItem(holon: Holon, selectedHolonId: String?, store: Store) {
    val header = holon.header
    ListItem(
        headlineContent = {
            Text(header.name, maxLines = 1, style = MaterialTheme.typography.titleSmall, fontWeight = if (header.id == selectedHolonId) FontWeight.Bold else FontWeight.Normal)
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
            SelectionContainer(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = holon.content,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())
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

@Composable
private fun ImportPane(
    kgState: KnowledgeGraphState,
    store: Store,
    platformDependencies: PlatformDependencies,
    modifier: Modifier = Modifier
) {
    val filteredItems = remember(kgState.importItems, kgState.showOnlyChangedImportItems) {
        if (kgState.showOnlyChangedImportItems) {
            kgState.importItems.filter { it.initialAction !is Ignore }
        } else {
            kgState.importItems
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(value = kgState.importSourcePath, onValueChange = {}, label = { Text("Source Folder") }, modifier = Modifier.weight(1f), readOnly = true)
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                platformDependencies.selectDirectoryPath()?.let {
                    store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_START_IMPORT_ANALYSIS, buildJsonObject { put("path", it) }))
                }
            }) { Text("Select & Analyze...") }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
                store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_SET_IMPORT_RECURSIVE, buildJsonObject { put("recursive", !kgState.isImportRecursive) }))
            }) {
                Checkbox(checked = kgState.isImportRecursive, onCheckedChange = null)
                Text("Import sub-folders recursively")
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
                store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_TOGGLE_SHOW_ONLY_CHANGED))
            }) {
                Checkbox(checked = kgState.showOnlyChangedImportItems, onCheckedChange = null)
                Text("Show only changed files")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (kgState.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (kgState.importItems.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (kgState.importSourcePath.isBlank()) "Select a folder to begin analysis." else "No importable .json files found.")
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(filteredItems, key = { it.sourcePath }) { item ->
                    ImportItemRow(
                        item = item,
                        selectedAction = kgState.importSelectedActions[item.sourcePath] ?: item.initialAction,
                        onActionSelected = { newAction ->
                            store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_UPDATE_IMPORT_ACTION, buildJsonObject {
                                put("sourcePath", item.sourcePath)
                                put("action", Json.encodeToJsonElement(newAction))
                            }))
                        },
                        potentialParents = kgState.holons.values.map { it.header },
                        platform = platformDependencies
                    )
                    HorizontalDivider()
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = { store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_SET_VIEW_MODE, buildJsonObject { put("mode", KnowledgeGraphViewMode.INSPECTOR.name) })) }) { Text("Cancel") }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_EXECUTE_IMPORT)) },
                    enabled = kgState.importItems.isNotEmpty()
                ) { Text("Execute Import") }
            }
        }
    }
}

@Composable
private fun ImportItemRow(
    item: ImportItem,
    selectedAction: ImportAction,
    onActionSelected: (ImportAction) -> Unit,
    potentialParents: List<HolonHeader>,
    platform: PlatformDependencies
) {
    val fileName = remember(item.sourcePath) { item.sourcePath.substringAfterLast(platform.pathSeparator) }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(fileName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.width(16.dp))
        ActionSelector(item.initialAction, selectedAction, onActionSelected, potentialParents)
    }
}

@Composable
private fun ActionSelector(
    initialAction: ImportAction,
    selectedAction: ImportAction,
    onActionSelected: (ImportAction) -> Unit,
    potentialParents: List<HolonHeader>
) {
    when (selectedAction) {
        is AssignParent -> ParentSelector(selectedAction, onActionSelected, potentialParents)
        else -> GenericActionSelector(initialAction, selectedAction, onActionSelected)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParentSelector(
    selectedAction: AssignParent,
    onActionSelected: (ImportAction) -> Unit,
    potentialParents: List<HolonHeader>
) {
    var parentMenuExpanded by remember { mutableStateOf(false) }
    var actionMenuExpanded by remember { mutableStateOf(false) }
    val selectedParentName = selectedAction.assignedParentId?.let { id -> potentialParents.find { it.id == id }?.name } ?: "Select Parent..."

    Row(verticalAlignment = Alignment.CenterVertically) {
        ExposedDropdownMenuBox(expanded = parentMenuExpanded, onExpandedChange = { parentMenuExpanded = !parentMenuExpanded }) {
            OutlinedTextField(
                value = selectedParentName, onValueChange = {}, readOnly = true,
                modifier = Modifier.menuAnchor().width(200.dp),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = parentMenuExpanded) }
            )
            ExposedDropdownMenu(expanded = parentMenuExpanded, onDismissRequest = { parentMenuExpanded = false }) {
                potentialParents.sortedBy { it.name }.forEach { parent ->
                    DropdownMenuItem(text = { Text(parent.name) }, onClick = {
                        onActionSelected(AssignParent(assignedParentId = parent.id))
                        parentMenuExpanded = false
                    })
                }

            }
        }

        IconButton(onClick = { actionMenuExpanded = true }) { Icon(Icons.Default.MoreVert, "Change Action Type") }
        DropdownMenu(expanded = actionMenuExpanded, onDismissRequest = { actionMenuExpanded = false }) {
            ImportActionType.entries.filter { it != ImportActionType.CREATE_ROOT && it != ImportActionType.ASSIGN_PARENT }.forEach { type ->
                DropdownMenuItem(text = { Text(type.toInstance(selectedAction).summary) }, onClick = {
                    onActionSelected(type.toInstance(selectedAction))
                    actionMenuExpanded = false
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenericActionSelector(
    initialAction: ImportAction,
    selectedAction: ImportAction,
    onActionSelected: (ImportAction) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val availableActions = ImportActionType.entries.filter { it != ImportActionType.CREATE_ROOT && it != ImportActionType.ASSIGN_PARENT }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = selectedAction.summary, onValueChange = {}, readOnly = true,
            modifier = Modifier.menuAnchor().width(250.dp),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            availableActions.forEach { type ->
                DropdownMenuItem(text = { Text(type.toInstance(initialAction).summary) }, onClick = {
                    onActionSelected(type.toInstance(initialAction))
                    expanded = false
                })
            }
        }
    }
}