package app.auf.feature.core

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.auf.core.Action
import app.auf.core.Identity
import app.auf.core.Store
import app.auf.core.generated.ActionRegistry
import app.auf.core.resolveDisplayColor
import app.auf.ui.components.ColorPicker
import app.auf.ui.components.colorToHex
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Formats epoch millis to a human-readable ISO-like timestamp.
 * Example: "2025-03-06 14:30:05"
 */
private fun formatTimestamp(epochMillis: Long): String {
    // Manual formatting from epoch — no platform dependency needed.
    // Computes UTC date/time from millis since 1970-01-01T00:00:00Z.
    val totalSeconds = epochMillis / 1000
    val milliRemainder = epochMillis % 1000

    // Days since epoch
    var days = (totalSeconds / 86400).toInt()
    val timeOfDay = (totalSeconds % 86400).toInt()
    val hour = timeOfDay / 3600
    val minute = (timeOfDay % 3600) / 60
    val second = timeOfDay % 60

    // Year/month/day from day count (civil calendar from days since epoch)
    var year = 1970
    while (true) {
        val daysInYear = if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 366 else 365
        if (days < daysInYear) break
        days -= daysInYear
        year++
    }
    val isLeap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    val monthDays = intArrayOf(31, if (isLeap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    var month = 0
    while (month < 12 && days >= monthDays[month]) {
        days -= monthDays[month]
        month++
    }
    val day = days + 1
    month += 1

    return "%04d-%02d-%02d %02d:%02d:%02d".format(year, month, day, hour, minute, second)
}

/**
 * Tabbed container for the Identity Manager: "Identities" tab shows user/identity
 * management, "Permissions" tab shows the permission grant matrix (Phase 2.A).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentityManagerView(store: Store) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Identities", "Permissions")

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Identity Manager") },
                    navigationIcon = {
                        IconButton(onClick = { store.dispatch("core", Action(ActionRegistry.Names.CORE_SHOW_DEFAULT_VIEW)) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
                // Phase 2.A: Tab row for Identities / Permissions
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(Modifier.fillMaxSize().padding(paddingValues)) {
            when (selectedTab) {
                0 -> IdentitiesTabContent(store)
                1 -> PermissionManagerView(store)
            }
        }
    }
}

// ============================================================================
// Tab 0: Identities (original content extracted into its own composable)
// ============================================================================

@Composable
private fun IdentitiesTabContent(store: Store) {
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
                store.dispatch("core", Action(ActionRegistry.Names.CORE_ADD_USER_IDENTITY, buildJsonObject { put("name", name) }))
                showAddDialog = false
            }
        )
    }

    Column(Modifier.fillMaxSize()) {
        // Action bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { showAllIdentities = !showAllIdentities }
            ) {
                Icon(
                    imageVector = if (showAllIdentities) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = "Show all identities",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, "Add Identity"); Spacer(Modifier.width(8.dp)); Text("Add")
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
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
                        showDetails = showAllIdentities,
                        store = store,
                        onSetActive = {
                            store.dispatch("core", Action(ActionRegistry.Names.CORE_SET_ACTIVE_USER_IDENTITY, buildJsonObject { put("id", identity.handle) }))
                        },
                        onDelete = {
                            store.dispatch("core", Action(ActionRegistry.Names.CORE_REMOVE_USER_IDENTITY, buildJsonObject { put("id", identity.handle) }))
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

// ============================================================================
// Identity Row Components
// ============================================================================

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
                        "registeredAt: ${formatTimestamp(identity.registeredAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (identity.permissions.isNotEmpty()) {
                    Text(
                        "permissions: ${identity.permissions.size} grant(s)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IdentityRow(
    identity: Identity,
    isActive: Boolean,
    showDetails: Boolean = false,
    store: Store,
    onSetActive: () -> Unit,
    onDelete: () -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }
    var editName by remember(identity.name) { mutableStateOf(identity.name) }
    var showColorPicker by remember { mutableStateOf(false) }
    // Draft color tracks the picker state; committed on "Use", discarded on "Cancel"
    var draftColorHex by remember(identity.displayColor) { mutableStateOf(identity.displayColor) }

    val displayColor = identity.resolveDisplayColor()
    val accentColor = displayColor ?: MaterialTheme.colorScheme.primary
    val draftAccent = draftColorHex?.let { hex ->
        val clean = hex.removePrefix("#")
        if (clean.length == 6) try {
            val rgb = clean.toLong(16)
            androidx.compose.ui.graphics.Color(
                red = ((rgb shr 16) and 0xFF).toInt() / 255f,
                green = ((rgb shr 8) and 0xFF).toInt() / 255f,
                blue = (rgb and 0xFF).toInt() / 255f
            )
        } catch (_: Exception) { null } else null
    } ?: accentColor

    fun commitEdits() {
        // Dispatch UPDATE_IDENTITY with extended payload (name + displayColor)
        store.dispatch("core", Action(ActionRegistry.Names.CORE_UPDATE_IDENTITY, buildJsonObject {
            put("handle", identity.handle)
            put("newName", editName)
            if (draftColorHex != null) put("displayColor", draftColorHex)
            else put("displayColor", null as String?)
        }))
        isEditing = false
        showColorPicker = false
    }

    fun cancelEdits() {
        editName = identity.name
        draftColorHex = identity.displayColor
        isEditing = false
        showColorPicker = false
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        border = if (isActive) BorderStroke(2.dp, draftAccent) else null
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                .onKeyEvent { event ->
                    if (isEditing && event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        cancelEdits(); true
                    } else false
                }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Color swatch — always visible
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(draftAccent)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    )

                    if (isEditing) {
                        // Edit mode: name text field
                        OutlinedTextField(
                            value = editName,
                            onValueChange = { editName = it },
                            label = { Text("Display Name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        // Read mode: name + details, double-click enters edit
                        Column(
                            modifier = Modifier.weight(1f).combinedClickable(
                                onClick = {},
                                onDoubleClick = { isEditing = true }
                            )
                        ) {
                            Text(identity.name, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal)
                            Text(
                                "ID: ${identity.handle}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (identity.permissions.isNotEmpty()) {
                                Text(
                                    "${identity.permissions.size} permission(s)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (showDetails) {
                                identity.parentHandle?.let {
                                    Text("parent: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                identity.uuid?.let {
                                    Text("uuid: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("localHandle: ${identity.localHandle}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (identity.registeredAt > 0) {
                                    Text("registeredAt: ${formatTimestamp(identity.registeredAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }

                // Action buttons
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isEditing) {
                        TextButton(onClick = { cancelEdits() }) { Text("Cancel") }
                        Spacer(Modifier.width(4.dp))
                        Button(onClick = { commitEdits() }, enabled = editName.isNotBlank()) { Text("Save") }
                    } else {
                        if (isActive) {
                            FilledTonalButton(onClick = {}) { Text("Active") }
                        } else {
                            Button(onClick = onSetActive) { Text("Set Active") }
                        }
                        IconButton(onClick = { isEditing = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit Identity")
                        }
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Identity")
                        }
                    }
                }
            }

            // Edit mode: Set color button + color picker
            AnimatedVisibility(visible = isEditing) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    if (!showColorPicker) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(onClick = { showColorPicker = true }) {
                                Icon(Icons.Default.Palette, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Set color")
                            }
                            if (identity.displayColor != null) {
                                TextButton(onClick = { draftColorHex = null }) {
                                    Text("Reset to default")
                                }
                            }
                        }
                    }

                    AnimatedVisibility(visible = showColorPicker) {
                        ColorPicker(
                            initialColor = draftAccent,
                            onConfirm = { color ->
                                draftColorHex = colorToHex(color)
                                showColorPicker = false
                            },
                            onCancel = {
                                // Discard draft color, revert to persisted
                                draftColorHex = identity.displayColor
                                showColorPicker = false
                            },
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
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