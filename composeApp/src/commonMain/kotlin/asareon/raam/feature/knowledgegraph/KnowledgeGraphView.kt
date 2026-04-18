package asareon.raam.feature.knowledgegraph

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import asareon.raam.core.Action
import asareon.raam.core.Store
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.ui.components.CodeEditor
import asareon.raam.ui.components.SyntaxMode
import asareon.raam.util.PlatformDependencies
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; isLenient = true }

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
            onDismissRequest = { store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_PERSONA_TO_DELETE, buildJsonObject { put("personaId", JsonNull) })) },
            title = { Text("Delete Persona?") },
            text = { Text("Are you sure you want to permanently delete '$personaName'? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = { store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_DELETE_PERSONA, buildJsonObject { put("personaId", personaId) })) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { Button(onClick = { store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_PERSONA_TO_DELETE, buildJsonObject { put("personaId", JsonNull) })) }) { Text("Cancel") } }
        )
    }
    kgState.holonIdToDelete?.let { holonId ->
        val holonName = kgState.holons[holonId]?.header?.name ?: "Unknown Holon"
        AlertDialog(
            onDismissRequest = { store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_HOLON_TO_DELETE, buildJsonObject { put("holonId", null as String?) })) },
            title = { Text("Delete Holon?") },
            text = { Text("Are you sure you want to permanently delete '$holonName'? This will also delete all of its children.") },
            confirmButton = {
                Button(
                    onClick = { store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_DELETE_HOLON, buildJsonObject { put("holonId", holonId) })) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { Button(onClick = { store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_HOLON_TO_DELETE, buildJsonObject { put("holonId", null as String?) })) }) { Text("Cancel") } }
        )
    }
    kgState.holonIdToRename?.let { holonId ->
        RenameHolonDialog(
            holon = kgState.holons[holonId],
            onDismiss = { store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_HOLON_TO_RENAME, buildJsonObject { put("holonId", null as String?) })) },
            onRename = { newName -> store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_RENAME_HOLON, buildJsonObject { put("holonId", holonId); put("newName", newName) })) }
        )
    }


    // --- Creation Dialog ---
    if (kgState.isCreatingPersona) {
        var newPersonaName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_CREATING_PERSONA, buildJsonObject { put("isCreating", false) })) },
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
                        store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_CREATE_PERSONA, buildJsonObject { put("name", newPersonaName) }))
                        store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_CREATING_PERSONA, buildJsonObject { put("isCreating", false) }))
                    },
                    enabled = newPersonaName.isNotBlank()
                ) { Text("Create") }
            },
            dismissButton = { Button(onClick = { store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_CREATING_PERSONA, buildJsonObject { put("isCreating", false) })) }) { Text("Cancel") } }
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
                                onCheckedChange = { store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_TOGGLE_SHOW_SUMMARIES)) },
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                        IconButton(
                            onClick = { store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_VIEW_MODE, buildJsonObject { put("mode", KnowledgeGraphViewMode.IMPORT.name) })) }
                        ) {
                            Icon(Icons.Default.Download, contentDescription = "Import Holons")
                        }
                        Button(onClick = { store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_CREATING_PERSONA, buildJsonObject { put("isCreating", true) })) }) {
                            Text("Create Persona")
                        }
                    } else {
                        IconButton(onClick = { store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_VIEW_MODE, buildJsonObject { put("mode", KnowledgeGraphViewMode.INSPECTOR.name) })) }) {
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
    val selectedHolonId = kgState.activeHolonIdForView
    val holonToEditId = kgState.holonIdToEdit
    val selectedHolon = kgState.holons[selectedHolonId]

    val availableTypes = remember(kgState.holons) {
        (listOf("All") + kgState.holons.values.map { it.header.type }.distinct().sorted()).toSet()
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (kgState.personaRoots.isNotEmpty()) {
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
                            store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_TYPE_FILTERS, buildJsonObject { put("types", Json.encodeToJsonElement(newFilters)) }))
                        },
                        label = { Text(type) }
                    )
                }
            }
            HorizontalDivider()
        }

        Row(Modifier.fillMaxSize()) {
            if (kgState.personaRoots.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when {
                        kgState.isLoading -> CircularProgressIndicator()
                        kgState.fatalError != null -> Text(kgState.fatalError, color = MaterialTheme.colorScheme.error)
                        else -> Text("No Knowledge Graphs found. Create or import a Persona to begin.")
                    }
                }
            } else {
                MultiRootTreeView(
                    kgState = kgState,
                    store = store,
                    modifier = Modifier.width(400.dp)
                )
                VerticalDivider()

                if (holonToEditId != null) {
                    HolonEditView(
                        holon = kgState.holons[holonToEditId],
                        onDismiss = { store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_HOLON_TO_EDIT, buildJsonObject { put("holonId", null as String?) })) },
                        onSave = { holonId, newPayload, newExecute ->
                            store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_UPDATE_HOLON_CONTENT, buildJsonObject {
                                put("holonId", holonId)
                                newPayload?.let { put("payload", it) }
                                newExecute?.let { put("execute", it) }
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

// [FIX] New data class to hold the unique key for the lazy list.
private data class UiTreeNode(
    val holon: Holon,
    val uniqueKey: String
)

@Composable
private fun MultiRootTreeView(kgState: KnowledgeGraphState, store: Store, modifier: Modifier = Modifier) {
    // [FIX] Strict Key Deduplication Logic
    // We maintain a set of seen keys during the tree traversal. If a key is duplicated,
    // we append a counter to ensure uniqueness. This prevents LazyColumn crashes.
    val treeNodes = remember(kgState.holons, kgState.personaRoots, kgState.activeTypeFilters, kgState.collapsedHolonIds) {
        val visibleNodes = mutableListOf<UiTreeNode>()
        val sortedRoots = kgState.personaRoots.values.mapNotNull { kgState.holons[it] }.sortedBy { it.header.name }

        val seenKeys = mutableSetOf<String>()

        fun getUniqueKey(baseKey: String): String {
            var key = baseKey
            var counter = 1
            while (seenKeys.contains(key)) {
                key = "$baseKey#${counter++}"
            }
            seenKeys.add(key)
            return key
        }

        fun buildTreeList(holon: Holon, parentPath: String) {
            val baseKey = "$parentPath>${holon.header.id}"
            val uniqueKey = getUniqueKey(baseKey)

            if (kgState.activeTypeFilters.isEmpty() || kgState.activeTypeFilters.contains(holon.header.type)) {
                visibleNodes.add(UiTreeNode(holon, uniqueKey))
            }

            if (!kgState.collapsedHolonIds.contains(holon.header.id)) {
                holon.header.subHolons
                    .mapNotNull { kgState.holons[it.id] }
                    .sortedBy { it.header.name }
                    .forEach { buildTreeList(it, uniqueKey) } // Pass the unique parent key down
            }
        }

        sortedRoots.forEach { buildTreeList(it, "root") }
        visibleNodes
    }

    LazyColumn(modifier = modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
        // [FIX] Use the guaranteed uniqueKey.
        items(treeNodes, key = { it.uniqueKey }) { node ->
            HolonTreeItem(node.holon, kgState, store)
        }
    }
}

@Composable
private fun HolonTreeItem(holon: Holon, kgState: KnowledgeGraphState, store: Store) {
    val header = holon.header
    val selectedHolonId = kgState.activeHolonIdForView
    val showSummary = kgState.showSummariesInTreeView
    val reservationOwner = if (header.type == "AI_Persona_Root") kgState.reservations[header.id] else null
    val isCollapsed = kgState.collapsedHolonIds.contains(header.id)
    val hasChildren = header.subHolons.isNotEmpty()

    val rotationAngle by animateFloatAsState(if (isCollapsed) 0f else 90f)

    ListItem(
        headlineContent = {
            Text(header.name, maxLines = 1, style = MaterialTheme.typography.titleSmall, fontWeight = if (header.id == selectedHolonId) FontWeight.Bold else FontWeight.Normal)
        },
        supportingContent = {
            Column {
                if (showSummary) {
                    Text(header.summary ?: header.type, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                }
                if (reservationOwner != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "Reserved",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Reserved by: $reservationOwner",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
        },
        leadingContent = {
            if (hasChildren) {
                IconButton(
                    onClick = { store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_TOGGLE_HOLON_EXPANDED, buildJsonObject { put("holonId", header.id) })) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Toggle expand ${header.name}",
                        modifier = Modifier.rotate(rotationAngle)
                    )
                }
            } else {
                Spacer(Modifier.width(24.dp)) // Maintain alignment for items without children
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_ACTIVE_VIEW_HOLON, buildJsonObject { put("holonId", header.id) })) }
            .background(if (header.id == selectedHolonId) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
            .padding(start = (header.depth * 12).dp) // Reduced padding to accommodate icon
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
                    Button(onClick = { store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_HOLON_TO_EDIT, buildJsonObject { put("holonId", holon.header.id) })) }) { Text("Edit") }
                    Spacer(Modifier.width(8.dp))
                    if (holon.header.type == "AI_Persona_Root") {
                        Button(
                            onClick = { store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_PERSONA_TO_DELETE, buildJsonObject { put("personaId", holon.header.id) })) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) { Text("Delete Persona") }
                    } else {
                        OutlinedButton(onClick = { store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_HOLON_TO_RENAME, buildJsonObject { put("holonId", holon.header.id) })) }) { Text("Rename") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_HOLON_TO_DELETE, buildJsonObject { put("holonId", holon.header.id) })) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) { Text("Delete") }
                    }
                }
            )
        }
    ) { paddingValues ->
        SelectionContainer(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            CodeEditor(
                value = holon.rawContent,
                onValueChange = {},
                readOnly = true,
                syntax = SyntaxMode.JSON,
                modifier = Modifier.fillMaxSize().padding(16.dp)
            )
        }
    }
}

@Composable
private fun HolonEditView(
    holon: Holon?,
    onDismiss: () -> Unit,
    onSave: (holonId: String, newPayload: JsonElement?, newExecute: JsonElement?) -> Unit,
    modifier: Modifier = Modifier
) {
    if (holon == null) {
        onDismiss(); return
    }

    var payloadText by remember(holon.header.id) { mutableStateOf(json.encodeToString(JsonElement.serializer(), holon.payload)) }
    var executeText by remember(holon.header.id) { mutableStateOf(holon.execute?.let { json.encodeToString(JsonElement.serializer(), it) } ?: "") }
    var isPayloadValid by remember { mutableStateOf(true) }
    var isExecuteValid by remember { mutableStateOf(true) }

    fun validateAndSave() {
        var payloadJson: JsonElement? = null
        var executeJson: JsonElement? = null
        var isValid = true

        try {
            payloadJson = json.parseToJsonElement(payloadText)
            isPayloadValid = true
        } catch (e: Exception) {
            isPayloadValid = false
            isValid = false
        }

        if (executeText.isNotBlank()) {
            try {
                executeJson = json.parseToJsonElement(executeText)
                isExecuteValid = true
            } catch (e: Exception) {
                isExecuteValid = false
                isValid = false
            }
        } else {
            isExecuteValid = true
        }


        if (isValid) {
            onSave(holon.header.id, payloadJson, executeJson)
            onDismiss()
        }
    }

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
                Button(onClick = { validateAndSave() }) { Text("Save Changes") }
            }
        }
        HorizontalDivider()

        Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("Payload", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            CodeEditor(
                value = payloadText,
                onValueChange = { payloadText = it; isPayloadValid = true },
                syntax = SyntaxMode.JSON,
                modifier = Modifier.fillMaxWidth().weight(1f),
                inputTag = "holon-edit-payload"
            )
            if (!isPayloadValid) {
                Text("Invalid JSON format in payload.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(16.dp))
            Text("Execute (optional)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            CodeEditor(
                value = executeText,
                onValueChange = { executeText = it; isExecuteValid = true },
                syntax = SyntaxMode.JSON,
                modifier = Modifier.fillMaxWidth().weight(1f),
                inputTag = "holon-edit-execute"
            )
            if (!isExecuteValid) {
                Text("Invalid JSON format in execute.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
        }
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

@Composable
private fun ImportPane(
    kgState: KnowledgeGraphState,
    store: Store,
    platformDependencies: PlatformDependencies,
    modifier: Modifier = Modifier
) {
    val filteredItems = remember(kgState.importItems, kgState.showOnlyChangedImportItems) {
        if (kgState.showOnlyChangedImportItems) {
            kgState.importItems.filter { item ->
                val selectedAction = kgState.importSelectedActions[item.sourcePath]
                // Show if the final action is not a simple Ignore with the default reason.
                !(selectedAction is Ignore && selectedAction.reason == "Content is identical")
            }
        } else {
            kgState.importItems
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Select a folder to analyze for holon files.", modifier = Modifier.weight(1f))
            Button(onClick = {
                store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_START_IMPORT_ANALYSIS))
            }) { Text("Select & Analyze...") }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
                store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_IMPORT_RECURSIVE, buildJsonObject { put("recursive", !kgState.isImportRecursive) }))
            }) {
                Checkbox(checked = kgState.isImportRecursive, onCheckedChange = null)
                Text("Import sub-folders recursively")
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
                store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_TOGGLE_SHOW_ONLY_CHANGED))
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
                Text("Ready to analyze a folder.")
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(filteredItems, key = { it.sourcePath }) { item ->
                    ImportItemRow(
                        item = item,
                        selectedAction = kgState.importSelectedActions[item.sourcePath] ?: item.proposedAction,
                        onActionSelected = { newAction ->
                            store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_UPDATE_IMPORT_ACTION, buildJsonObject {
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
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (kgState.importFileContents.isNotEmpty() && !kgState.isLoading) {
                    val total = kgState.importFileContents.size
                    val create = kgState.importSelectedActions.values.count { it is CreateRoot || it is Integrate || it is AssignParent }
                    val update = kgState.importSelectedActions.values.count { it is Update }
                    val ignore = kgState.importSelectedActions.values.count { it is Ignore }
                    val quarantine = kgState.importSelectedActions.values.count { it is Quarantine }

                    Text(
                        text = "Found $total files. Plan: $create create, $update update, $quarantine quarantine, $ignore ignore.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                // [ADDED] "Copy Report" button
                OutlinedButton(onClick = { store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_COPY_ANALYSIS_TO_CLIPBOARD)) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Report", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Copy Report")
                }
                Spacer(modifier = Modifier.width(16.dp))
                OutlinedButton(onClick = { store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_VIEW_MODE, buildJsonObject { put("mode", KnowledgeGraphViewMode.INSPECTOR.name) })) }) { Text("Cancel") }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_EXECUTE_IMPORT)) },
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
        Column(modifier = Modifier.weight(1f)) {
            Text(fileName, style = MaterialTheme.typography.bodyMedium)
            // [MODIFIED] Display the new statusReason field.
            item.statusReason?.let { reason ->
                val color = when {
                    reason.startsWith("USER:") -> MaterialTheme.colorScheme.tertiary
                    selectedAction is Quarantine -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    text = reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = color
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        ActionSelector(item, selectedAction, onActionSelected, potentialParents)
    }
}

@Composable
private fun ActionSelector(
    item: ImportItem,
    selectedAction: ImportAction,
    onActionSelected: (ImportAction) -> Unit,
    potentialParents: List<HolonHeader>
) {
    if (item.availableActions.contains(ImportActionType.ASSIGN_PARENT)) {
        ParentSelector(item, selectedAction as? AssignParent ?: AssignParent(), onActionSelected, potentialParents)
    } else {
        GenericActionSelector(item, selectedAction, onActionSelected)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParentSelector(
    item: ImportItem,
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
            item.availableActions.forEach { type ->
                DropdownMenuItem(text = { Text(type.toInstance(item.proposedAction).summary) }, onClick = {
                    onActionSelected(type.toInstance(item.proposedAction))
                    actionMenuExpanded = false
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenericActionSelector(
    item: ImportItem,
    selectedAction: ImportAction,
    onActionSelected: (ImportAction) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = selectedAction.summary, onValueChange = {}, readOnly = true,
            modifier = Modifier.menuAnchor().width(250.dp),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            item.availableActions.forEach { type ->
                DropdownMenuItem(text = { Text(type.toInstance(item.proposedAction).summary) }, onClick = {
                    onActionSelected(type.toInstance(item.proposedAction))
                    expanded = false
                })
            }
        }
    }
}