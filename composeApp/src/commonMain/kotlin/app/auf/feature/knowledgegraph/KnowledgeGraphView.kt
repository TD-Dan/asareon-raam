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
import androidx.compose.material.icons.filled.*
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
import kotlinx.serialization.json.JsonNull
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

    // --- Deletion Dialogs ---
    kgState.personaIdToDelete?.let { personaId ->
        val personaName = kgState.holons[personaId]?.header?.name ?: "Unknown Persona"
        AlertDialog(
            onDismissRequest = { store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_SET_PERSONA_TO_DELETE, buildJsonObject { put("personaId", JsonNull) })) },
            title = { Text("Delete Persona?") },
            text = { Text("Are you sure you want to permanently delete '$personaName'? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = { store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_DELETE_PERSONA, buildJsonObject { put("personaId", personaId) })) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { Button(onClick = { store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_SET_PERSONA_TO_DELETE, buildJsonObject { put("personaId", JsonNull) })) }) { Text("Cancel") } }
        )
    }
    kgState.holonIdToDelete?.let { holonId ->
        val holonName = kgState.holons[holonId]?.header?.name ?: "Unknown Holon"
        AlertDialog(
            onDismissRequest = { store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_SET_HOLON_TO_DELETE, buildJsonObject { put("holonId", null as String?) })) },
            title = { Text("Delete Holon?") },
            text = { Text("Are you sure you want to permanently delete '$holonName'? This will also delete all of its children.") },
            confirmButton = {
                Button(
                    onClick = { store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_DELETE_HOLON, buildJsonObject { put("holonId", holonId) })) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { Button(onClick = { store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_SET_HOLON_TO_DELETE, buildJsonObject { put("holonId", null as String?) })) }) { Text("Cancel") } }
        )
    }
    kgState.holonIdToRename?.let { holonId ->
        RenameHolonDialog(
            holon = kgState.holons[holonId],
            onDismiss = { store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_SET_HOLON_TO_RENAME, buildJsonObject { put("holonId", null as String?) })) },
            onRename = { newName -> store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_RENAME_HOLON, buildJsonObject { put("holonId", holonId); put("newName", newName) })) }
        )
    }


    // --- Creation Dialog ---
    if (kgState.isCreatingPersona) {
        var newPersonaName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_SET_CREATING_PERSONA, buildJsonObject { put("isCreating", false) })) },
            title = { Text("Create New Persona") },
            text = {
                OutlinedTextField(
                    value = newPersonaName,
                    onValueChange = { newPersonaName = it },
                    label = { Text("Persona Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_CREATE_PERSONA, buildJsonObject { put("name", newPersonaName) }))
                        store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_SET_CREATING_PERSONA, buildJsonObject { put("isCreating", false) }))
                    },
                    enabled = newPersonaName.isNotBlank()
                ) { Text("Create") }
            },
            dismissButton = { Button(onClick = { store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_SET_CREATING_PERSONA, buildJsonObject { put("isCreating", false) })) }) { Text("Cancel") } }
        )
    }


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Knowledge Graph Manager") },
                actions = {
                    if (kgState.viewMode == KnowledgeGraphViewMode.INSPECTOR) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Show Summaries", style = MaterialTheme.typography.labelMedium)
                            Switch(
                                checked = kgState.showSummariesInTreeView,
                                onCheckedChange = { store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_TOGGLE_SHOW_SUMMARIES)) },
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                        PersonaSelector(kgState, store)
                        IconButton(
                            onClick = { store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_SET_VIEW_MODE, buildJsonObject { put("mode", KnowledgeGraphViewMode.IMPORT.name) })) }
                        ) {
                            Icon(Icons.Default.Download, contentDescription = "Import Holons")
                        }
                        Button(onClick = { store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_SET_CREATING_PERSONA, buildJsonObject { put("isCreating", true) })) }) {
                            Text("Create Persona")
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InspectorPane(kgState: KnowledgeGraphState, store: Store, modifier: Modifier = Modifier) {
    val activePersonaRootId = kgState.activePersonaIdForView
    val selectedHolonId = kgState.activeHolonIdForView
    val holonToEditId = kgState.holonIdToEdit
    val selectedHolon = kgState.holons[selectedHolonId]

    val allHolonsInView = remember(kgState.holons, activePersonaRootId) {
        activePersonaRootId?.let { rootId ->
            val holons = mutableListOf<Holon>()
            val visited = mutableSetOf<String>()
            fun traverse(holonId: String) {
                if (visited.contains(holonId)) return
                kgState.holons[holonId]?.let { holon ->
                    holons.add(holon)
                    visited.add(holonId)
                    holon.header.subHolons.forEach { subRef -> traverse(subRef.id) }
                }
            }
            traverse(rootId)
            holons
        } ?: emptyList()
    }

    val availableTypes = remember(allHolonsInView) { (listOf("All") + allHolonsInView.map { it.header.type }.distinct().sorted()).toSet() }

    Column(modifier = modifier.fillMaxSize()) {
        if (activePersonaRootId != null) {
            FlowRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                availableTypes.forEach { type ->
                    val isSelected = if (type == "All") kgState.activeTypeFilters.isEmpty() else kgState.activeTypeFilters.contains(type)
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            val newFilters = if (type == "All") {
                                emptySet()
                            } else if (isSelected) {
                                kgState.activeTypeFilters - type
                            } else {
                                kgState.activeTypeFilters + type
                            }
                            store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_SET_TYPE_FILTERS, buildJsonObject { put("types", Json.encodeToJsonElement(newFilters)) }))
                        },
                        label = { Text(type) }
                    )
                }
            }
            HorizontalDivider()
        }

        Row(Modifier.fillMaxSize()) {
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
                    allHolonsInView = allHolonsInView,
                    kgState = kgState,
                    store = store,
                    modifier = Modifier.width(400.dp)
                )
                VerticalDivider()

                if (holonToEditId != null) {
                    HolonEditView(
                        holon = kgState.holons[holonToEditId],
                        onDismiss = { store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_SET_HOLON_TO_EDIT, buildJsonObject { put("holonId", null as String?) })) },
                        onSave = { newContent ->
                            store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_UPDATE_HOLON_CONTENT, buildJsonObject {
                                put("holonId", holonToEditId)
                                put("newContent", newContent)
                            }))
                        },
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    HolonDetailView(
                        holon = selectedHolon,
                        store = store,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun HolonTreeView(allHolonsInView: List<Holon>, kgState: KnowledgeGraphState, store: Store, modifier: Modifier = Modifier) {
    val rootHolon = kgState.activePersonaIdForView?.let { kgState.holons[it] }
    val holonsById = remember(allHolonsInView) { allHolonsInView.associateBy { it.header.id } }

    LazyColumn(modifier = modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
        if (rootHolon != null) {
            val treeHolons = mutableListOf<Holon>()
            fun buildTreeList(holon: Holon) {
                if (kgState.activeTypeFilters.isEmpty() || kgState.activeTypeFilters.contains(holon.header.type)) {
                    treeHolons.add(holon)
                }
                holon.header.subHolons
                    .mapNotNull { subRef -> holonsById[subRef.id] }
                    .sortedBy { childHolon -> childHolon.header.name }
                    .forEach { sortedChildHolon -> buildTreeList(sortedChildHolon) }
            }
            buildTreeList(rootHolon)

            items(treeHolons, key = { it.header.id }) { holon ->
                HolonTreeItem(holon, kgState.activeHolonIdForView, kgState.showSummariesInTreeView, store)
            }
        }
    }
}


@Composable
private fun HolonTreeItem(holon: Holon, selectedHolonId: String?, showSummary: Boolean, store: Store) {
    val header = holon.header
    ListItem(
        headlineContent = {
            Text(header.name, maxLines = 1, style = MaterialTheme.typography.titleSmall, fontWeight = if (header.id == selectedHolonId) FontWeight.Bold else FontWeight.Normal)
        },
        supportingContent = {
            if (showSummary) {
                Text(header.summary ?: header.type, maxLines = 1, style = MaterialTheme.typography.bodySmall)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_SET_ACTIVE_VIEW_HOLON, buildJsonObject { put("holonId", header.id) })) }
            .background(if (header.id == selectedHolonId) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
            .padding(start = (header.depth * 24).dp)
    )
    HorizontalDivider()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HolonDetailView(holon: Holon?, store: Store, modifier: Modifier = Modifier) {
    if (holon == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Select a holon to view its content.")
        }
        return
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(holon.header.name, style = MaterialTheme.typography.titleMedium) },
                actions = {
                    Button(onClick = { store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_SET_HOLON_TO_EDIT, buildJsonObject { put("holonId", holon.header.id) })) }) { Text("Edit") }
                    Spacer(Modifier.width(8.dp))
                    // Hide Rename and Delete for root personas
                    if (holon.header.type != "AI_Persona_Root") {
                        OutlinedButton(onClick = { store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_SET_HOLON_TO_RENAME, buildJsonObject { put("holonId", holon.header.id) })) }) { Text("Rename") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_SET_HOLON_TO_DELETE, buildJsonObject { put("holonId", holon.header.id) })) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) { Text("Delete") }
                    }
                }
            )
        }
    ) { paddingValues ->
        SelectionContainer(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            Text(
                text = holon.content,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())
            )
        }
    }
}

@Composable
private fun HolonEditView(holon: Holon?, onDismiss: () -> Unit, onSave: (String) -> Unit, modifier: Modifier = Modifier) {
    if (holon == null) {
        onDismiss()
        return
    }

    var text by remember(holon.header.id) { mutableStateOf(holon.content) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Editing: ${holon.header.name}", style = MaterialTheme.typography.titleMedium)
            Row {
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSave(text); onDismiss() }) { Text("Save Changes") }
            }
        }
        HorizontalDivider()
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxSize().padding(16.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            label = { Text("Holon Content") }
        )
    }
}

@Composable
private fun RenameHolonDialog(holon: Holon?, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    if (holon == null) {
        onDismiss()
        return
    }

    var newName by remember { mutableStateOf(holon.header.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Holon") },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("New Name") },
                singleLine = true
            )
        },
        confirmButton = {
            Button(
                onClick = { onRename(newName); onDismiss() },
                enabled = newName.isNotBlank() && newName != holon.header.name
            ) { Text("Rename") }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } }
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonaSelector(kgState: KnowledgeGraphState?, store: Store) {
    if (kgState == null) return
    var isExpanded by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val activePersonaName = kgState.personaRoots.entries.find { it.value == kgState.activePersonaIdForView }?.key ?: "Select Persona"

    Row(verticalAlignment = Alignment.CenterVertically) {
        ExposedDropdownMenuBox(expanded = isExpanded, onExpandedChange = { isExpanded = !isExpanded }) {
            OutlinedTextField(
                value = activePersonaName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Selected Persona") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(isExpanded) },
                modifier = Modifier.menuAnchor().width(250.dp)
            )
            ExposedDropdownMenu(expanded = isExpanded, onDismissRequest = { isExpanded = false }) {
                kgState.personaRoots.entries.sortedBy { it.key }.forEach { (name, id) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_SET_ACTIVE_VIEW_PERSONA, buildJsonObject { put("personaId", id) }))
                            store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_LOAD_PERSONA, buildJsonObject { put("personaId", id) }))
                            isExpanded = false
                        }
                    )
                }
            }
        }
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                enabled = kgState.activePersonaIdForView != null
            ) {
                Icon(Icons.Default.MoreVert, contentDescription = "More options")
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Delete Persona") },
                    onClick = {
                        kgState.activePersonaIdForView?.let {
                            store.dispatch("ui.kgView", Action(ActionNames.KNOWLEDGEGRAPH_SET_PERSONA_TO_DELETE, buildJsonObject { put("personaId", it) }))
                        }
                        menuExpanded = false
                    },
                    leadingIcon = { Icon(Icons.Default.Delete, null) }
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

        // **THE FIX**: Add the summary line here.
        if (kgState.importFileContents.isNotEmpty() && !kgState.isLoading) {
            val totalFiles = kgState.importFileContents.size
            val createCount = kgState.importSelectedActions.values.count { it is CreateRoot || it is Integrate || it is AssignParent }
            val updateCount = kgState.importSelectedActions.values.count { it is Update }
            Text(
                text = "Analysis complete: Found $totalFiles total files. Plan: $createCount to create, $updateCount to update.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
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
    val availableActions = ImportActionType.entries.filter { it != ImportActionType.ASSIGN_PARENT }

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