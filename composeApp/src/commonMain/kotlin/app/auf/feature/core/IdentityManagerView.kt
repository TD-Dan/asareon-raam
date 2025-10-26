package app.auf.feature.core

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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

    if (showAddDialog) {
        AddIdentityDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name ->
                store.dispatch("core.ui", Action(ActionNames.CORE_ADD_USER_IDENTITY, buildJsonObject { put("name", name) }))
                showAddDialog = false
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Identity Manager") },
                navigationIcon = {
                    IconButton(onClick = { store.dispatch("core.ui", Action(ActionNames.CORE_SHOW_DEFAULT_VIEW)) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Button(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, "Add Identity"); Spacer(Modifier.width(8.dp)); Text("Add")
                    }
                }
            )
        }
    ) { paddingValues ->
        if (coreState == null || coreState.userIdentities.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(paddingValues), Alignment.Center) {
                Text("No user identities configured.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text("Select your active identity for this session. This will be used to identify you in conversations with agents.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                }
                items(coreState.userIdentities, key = { it.id }) { identity ->
                    IdentityRow(
                        identity = identity,
                        isActive = identity.id == coreState.activeUserId,
                        onSetActive = { store.dispatch("core.ui", Action(ActionNames.CORE_SET_ACTIVE_USER_IDENTITY, buildJsonObject { put("id", identity.id) })) },
                        onDelete = { store.dispatch("core.ui", Action(ActionNames.CORE_REMOVE_USER_IDENTITY, buildJsonObject { put("id", identity.id) })) }
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
                Text("ID: ${identity.id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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