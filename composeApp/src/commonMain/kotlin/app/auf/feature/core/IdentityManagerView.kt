package app.auf.feature.core

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.auf.core.Action
import app.auf.core.Identity
import app.auf.core.Store
import app.auf.core.generated.ActionNames
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentityManagerView(store: Store) {
    val appState by store.state.collectAsState()
    val coreState = appState.featureStates["core"] as? CoreState
    var showAddDialog by remember { mutableStateOf(false) }
    var showAllIdentities by remember { mutableStateOf(false) }

    // Read user identities from the unified identity registry (parentHandle == "core")
    val userIdentities = remember(appState.identityRegistry) {
        appState.identityRegistry.values
            .filter { it.parentHandle == "core" }
            .sortedBy { it.handle }
    }

    // All identities in the registry, for the "show all" debug view
    val allIdentities = remember(appState.identityRegistry) {
        appState.identityRegistry.values
            .sortedBy { it.handle }
    }

    if (showAddDialog) {
        AddIdentityDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name ->
                store.dispatch("core.ui", Action(ActionRegistry.Names.CORE_ADD_USER_IDENTITY, buildJsonObject { put("name", name) }))
                showAddDialog = false
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Identity Manager") },
                navigationIcon = {
                    IconButton(onClick = { store.dispatch("core.ui", Action(ActionRegistry.Names.CORE_SHOW_DEFAULT_VIEW)) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showAllIdentities = !showAllIdentities }
                    ) {
                        Icon(
                            imageVector = if (showAllIdentities) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Show all identities",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, "Add Identity"); Spacer(Modifier.width(8.dp)); Text("Add")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // --- User Identities Section ---
            if (userIdentities.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), Alignment.Center) {
                        Text("No user identities configured.")
                    }
                }
            } else {
                item {
                    Text(
                        "Select your active identity for this session. This will be used to identify you in conversations with agents.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                }
                items(userIdentities, key = { it.handle }) { identity ->
                    IdentityRow(
                        identity = identity,
                        isActive = identity.handle == coreState?.activeUserId,
                        onSetActive = {
                            store.dispatch("core.ui", Action(ActionRegistry.Names.CORE_SET_ACTIVE_USER_IDENTITY, buildJsonObject { put("id", identity.handle) }))
                        },
                        onDelete = {
                            store.dispatch("core.ui", Action(ActionRegistry.Names.CORE_REMOVE_USER_IDENTITY, buildJsonObject { put("id", identity.handle) }))
                        }
                    )
                }
            }

            // --- Internal Identities Section (toggled) ---
            if (showAllIdentities) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "All Registered Identities",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                }

                if (allIdentities.isEmpty()) {
                    item {
                        Text(
                            "No identities registered.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(allIdentities, key = { "all-${it.handle}" }) { identity ->
                        InternalIdentityRow(identity)
                    }
                }
            }
        }
    }
}

@Composable
private fun InternalIdentityRow(identity: Identity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        SelectionContainer {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    identity.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "handle: ${identity.handle}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                identity.parentHandle?.let {
                    Text(
                        "parent: $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                identity.uuid?.let {
                    Text(
                        "uuid: $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "localHandle: ${identity.localHandle}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (identity.registeredAt > 0) {
                    Text(
                        "registeredAt: ${identity.registeredAt}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun IdentityRow(
    identity: Identity,
    isActive: Boolean,
    onSetActive: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = if (isActive) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(identity.name, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal)
                Text("ID: ${identity.handle}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isActive) {
                    Button(onClick = onSetActive) {
                        Text("Set Active")
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Identity")
                }
            }
        }
    }
}

@Composable
private fun AddIdentityDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add New Identity") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Display Name") },
                singleLine = true
            )
        },
        confirmButton = {
            Button(onClick = { onAdd(name) }, enabled = name.isNotBlank()) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}