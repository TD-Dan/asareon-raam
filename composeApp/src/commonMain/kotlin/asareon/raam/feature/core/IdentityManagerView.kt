package asareon.raam.feature.core

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import asareon.raam.core.Action
import asareon.raam.core.Identity
import asareon.raam.core.Store
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.core.resolveDisplayColor
import asareon.raam.ui.components.destructive.ConfirmDestructiveDialog
import asareon.raam.ui.components.destructive.DangerDropdownMenuItem
import asareon.raam.ui.components.footer.FooterActionEmphasis
import asareon.raam.ui.components.footer.FooterButton
import asareon.raam.ui.components.footer.ViewFooter
import asareon.raam.ui.components.identity.IdentityDraft
import asareon.raam.ui.components.identity.IdentityFieldsSection
import asareon.raam.ui.components.identity.toDraft
import asareon.raam.ui.components.topbar.HeaderAction
import asareon.raam.ui.components.topbar.HeaderActionEmphasis
import asareon.raam.ui.components.topbar.HeaderLeading
import asareon.raam.ui.components.topbar.RaamTopBarHeader
import asareon.raam.ui.theme.spacing
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
 * Container for the Identity Manager. The primary view is the list of user
 * identities; Permissions is a secondary destination reached from the header,
 * not a peer tab — it's accessed rarely and hierarchically subordinate to
 * the identities it describes.
 */
/**
 * Which identity the full-view editor is currently targeting. [Create] means
 * we're creating a fresh identity; [Edit] wraps the handle of an existing one.
 */
private sealed interface UserIdentityEditTarget {
    data object Create : UserIdentityEditTarget
    data class Edit(val handle: String) : UserIdentityEditTarget
}

@Composable
fun IdentityManagerView(store: Store) {
    var showingPermissions by remember { mutableStateOf(false) }
    var showAllIdentities by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<UserIdentityEditTarget?>(null) }

    if (editTarget != null) {
        UserIdentityEditorView(
            store = store,
            target = editTarget!!,
            onClose = { editTarget = null },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        if (!showingPermissions) {
            RaamTopBarHeader(
                title = "Identities",
                leading = HeaderLeading.Back(onClick = {
                    store.dispatch("core", Action(ActionRegistry.Names.CORE_SHOW_DEFAULT_VIEW))
                }),
                actions = listOf(
                    HeaderAction(
                        id = "create-identity",
                        label = "Create Identity",
                        icon = Icons.Default.Add,
                        priority = 30,
                        emphasis = HeaderActionEmphasis.Create,
                        onClick = { editTarget = UserIdentityEditTarget.Create },
                    ),
                    HeaderAction(
                        id = "view-permissions",
                        label = "Permissions",
                        icon = Icons.Default.Lock,
                        priority = 20,
                        emphasis = HeaderActionEmphasis.Prominent,
                        onClick = { showingPermissions = true },
                    ),
                    HeaderAction(
                        id = "toggle-show-all",
                        label = if (showAllIdentities) "Hide internal identities"
                            else "Show all identities",
                        icon = if (showAllIdentities) Icons.Default.Visibility
                            else Icons.Default.VisibilityOff,
                        priority = 10,
                        onClick = { showAllIdentities = !showAllIdentities },
                    ),
                ),
            )
            IdentitiesTabContent(
                store = store,
                showAllIdentities = showAllIdentities,
                onEdit = { handle -> editTarget = UserIdentityEditTarget.Edit(handle) },
            )
        } else {
            RaamTopBarHeader(
                title = "Permissions",
                subtitle = "Identities",
                leading = HeaderLeading.Back(onClick = { showingPermissions = false }),
            )
            PermissionManagerView(store)
        }
    }
}

// ============================================================================
// Tab 0: Identities (original content extracted into its own composable)
// ============================================================================

@Composable
private fun IdentitiesTabContent(
    store: Store,
    showAllIdentities: Boolean,
    onEdit: (handle: String) -> Unit,
) {
    val appState by store.state.collectAsState()
    val coreState = appState.featureStates["core"] as? CoreState

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

    // --- Deletion confirmation dialog ---
    var identityToDelete by remember { mutableStateOf<Identity?>(null) }
    identityToDelete?.let { identity ->
        ConfirmDestructiveDialog(
            title = "Delete Identity?",
            message = "Permanently delete '${identity.name}'? This action cannot be undone.",
            onConfirm = {
                store.dispatch(
                    "core",
                    Action(
                        ActionRegistry.Names.CORE_REMOVE_USER_IDENTITY,
                        buildJsonObject { put("id", identity.handle) },
                    ),
                )
                identityToDelete = null
            },
            onDismiss = { identityToDelete = null },
        )
    }

    Column(Modifier.fillMaxSize()) {
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
                        onSetActive = {
                            store.dispatch("core", Action(ActionRegistry.Names.CORE_SET_ACTIVE_USER_IDENTITY, buildJsonObject { put("id", identity.handle) }))
                        },
                        onEdit = { onEdit(identity.handle) },
                        onDelete = { identityToDelete = identity }
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
            containerColor = MaterialTheme.colorScheme.surfaceDim
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

@Composable
private fun IdentityRow(
    identity: Identity,
    isActive: Boolean,
    showDetails: Boolean = false,
    onSetActive: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val accentColor = identity.resolveDisplayColor() ?: MaterialTheme.colorScheme.primary

    Card(
        modifier = Modifier.fillMaxWidth(),
        border = if (isActive) BorderStroke(2.dp, accentColor) else null,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(accentColor)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(identity.name, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal)
                Text(
                    "ID: ${identity.handle}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (identity.permissions.isNotEmpty()) {
                    Text(
                        "${identity.permissions.size} permission(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            if (isActive) {
                FilledTonalButton(onClick = {}) { Text("Active") }
            } else {
                Button(onClick = onSetActive) { Text("Set Active") }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit Identity")
            }
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DangerDropdownMenuItem(
                        label = "Delete",
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

// ============================================================================
// Full-view Identity Editor — create/edit a user identity
// ============================================================================

@Composable
private fun UserIdentityEditorView(
    store: Store,
    target: UserIdentityEditTarget,
    onClose: () -> Unit,
) {
    val appState by store.state.collectAsState()
    val existing = (target as? UserIdentityEditTarget.Edit)
        ?.let { appState.identityRegistry[it.handle] }

    // Snapshot initial draft once per target — further updates to the
    // registry while we're editing must not clobber the user's in-flight
    // edits. Keyed only by target (not by existing), intentionally.
    val initial = remember(target) {
        existing?.toDraft() ?: IdentityDraft(name = "")
    }
    var draft by remember(target) { mutableStateOf(initial) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    val dirty = draft != initial
    val canSave = draft.name.isNotBlank() && dirty

    val tryClose = {
        if (dirty) showDiscardDialog = true else onClose()
    }

    val onSave = save@{
        when (target) {
            is UserIdentityEditTarget.Create -> {
                store.dispatch("core", Action(
                    ActionRegistry.Names.CORE_ADD_USER_IDENTITY,
                    buildJsonObject {
                        put("name", draft.name)
                        if (draft.displayColor != null) put("displayColor", draft.displayColor)
                        if (draft.displayIcon != null) put("displayIcon", draft.displayIcon)
                        if (draft.displayEmoji != null) put("displayEmoji", draft.displayEmoji)
                    },
                ))
            }
            is UserIdentityEditTarget.Edit -> {
                store.dispatch("core", Action(
                    ActionRegistry.Names.CORE_UPDATE_IDENTITY,
                    buildJsonObject {
                        put("handle", target.handle)
                        put("newName", draft.name)
                        put("displayColor", draft.displayColor)
                        put("displayIcon", draft.displayIcon)
                        put("displayEmoji", draft.displayEmoji)
                    },
                ))
            }
        }
        onClose()
    }

    Column(Modifier.fillMaxSize()) {
        RaamTopBarHeader(
            title = when (target) {
                is UserIdentityEditTarget.Create -> "New Identity"
                is UserIdentityEditTarget.Edit -> existing?.name ?: "Edit Identity"
            },
            subtitle = "Identities",
            leading = HeaderLeading.Back(onClick = tryClose),
        )
        IdentityFieldsSection(
            draft = draft,
            onDraftChange = { draft = it },
            nameLabel = "Display Name",
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = MaterialTheme.spacing.screenEdge,
                    vertical = MaterialTheme.spacing.itemGap,
                ),
        )
        ViewFooter {
            FooterButton(FooterActionEmphasis.Cancel, "Cancel", onClick = tryClose)
            FooterButton(
                emphasis = FooterActionEmphasis.Confirm,
                label = if (target is UserIdentityEditTarget.Create) "Create" else "Save",
                onClick = onSave,
                enabled = canSave,
            )
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard changes?") },
            text = { Text("Your unsaved edits will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onClose()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("Keep editing") }
            },
        )
    }
}