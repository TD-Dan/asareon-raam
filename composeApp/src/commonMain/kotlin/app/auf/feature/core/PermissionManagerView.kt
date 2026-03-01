package app.auf.feature.core

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.auf.core.*
import app.auf.core.generated.ActionRegistry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ============================================================================
// Color Constants for Danger Levels
// ============================================================================
private val DangerLowColor = Color(0xFF4CAF50)        // Green
private val DangerCautionColor = Color(0xFFFF9800)     // Orange
private val DangerDangerColor = Color(0xFFF44336)      // Red
private val EscalationBgColor = Color(0xFFFFF3E0)      // Light orange background

/**
 * Groups permission declarations by their domain (the part before the colon).
 * Returns a list of (domain, list-of-declarations) pairs sorted by domain.
 */
private fun groupPermissionsByDomain(
    declarations: Map<String, ActionRegistry.PermissionDeclaration>
): List<Pair<String, List<ActionRegistry.PermissionDeclaration>>> {
    return declarations.values
        .groupBy { it.key.substringBefore(':') }
        .entries
        .sortedBy { it.key }
        .map { (domain, decls) -> domain to decls.sortedBy { it.key } }
}

/**
 * Returns the short capability name from a permission key (part after the colon).
 */
private fun capabilityName(key: String): String = key.substringAfter(':')

/**
 * Returns the Material color for a given danger level.
 */
private fun dangerColor(dangerLevel: DangerLevel): Color = when (dangerLevel) {
    DangerLevel.LOW -> DangerLowColor
    DangerLevel.CAUTION -> DangerCautionColor
    DangerLevel.DANGER -> DangerDangerColor
}

/**
 * Detects whether an identity's effective permission for a given key represents
 * a controlled escalation — i.e., the identity's explicit grant exceeds what
 * its parent's effective permissions would grant.
 */
private fun isEscalated(
    identity: Identity,
    permKey: String,
    effectivePerms: Map<String, PermissionGrant>,
    parentEffectivePerms: Map<String, PermissionGrant>?
): Boolean {
    if (parentEffectivePerms == null) return false
    // Only escalated if the identity has an explicit grant AND it exceeds the parent
    val explicitGrant = identity.permissions[permKey] ?: return false
    val parentGrant = parentEffectivePerms[permKey]
    val parentLevel = parentGrant?.level ?: PermissionLevel.NO
    return explicitGrant.level > parentLevel
}

// ============================================================================
// Main View
// ============================================================================

/**
 * Permission Manager — Phase 2.A matrix UI.
 *
 * Displays a permission grant matrix with:
 * - Rows: non-feature identities (users, agents, sessions)
 * - Columns: permission keys from ActionRegistry.permissionDeclarations, grouped by domain
 * - Cells: YES/NO toggle checkboxes
 * - Column headers colored by danger level with tooltips
 * - Escalation indicators (⚠ + orange bg) when child grant exceeds parent
 */
@Composable
fun PermissionManagerView(store: Store) {
    val appState by store.state.collectAsState()
    val horizontalScrollState = rememberScrollState()

    // Get all permission declarations sorted and grouped
    val declarations = ActionRegistry.permissionDeclarations
    val groupedPermissions = remember(declarations) { groupPermissionsByDomain(declarations) }
    val flatPermKeys = remember(groupedPermissions) {
        groupedPermissions.flatMap { (_, decls) -> decls.map { it.key } }
    }

    // Get non-feature identities (uuid != null means not a feature)
    val editableIdentities = remember(appState.identityRegistry) {
        appState.identityRegistry.values
            .filter { it.uuid != null }
            .sortedBy { it.handle }
    }

    // Pre-compute effective permissions and parent effective permissions for each identity
    val effectivePermsMap = remember(appState.identityRegistry, editableIdentities) {
        editableIdentities.associate { identity ->
            identity.handle to store.resolveEffectivePermissions(identity)
        }
    }

    val parentEffectivePermsMap = remember(appState.identityRegistry, editableIdentities) {
        editableIdentities.associate { identity ->
            val parent = identity.parentHandle?.let { appState.identityRegistry[it] }
            identity.handle to (parent?.let { store.resolveEffectivePermissions(it) })
        }
    }

    if (declarations.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "No permission declarations found.\nAdd 'permissions' arrays to your *.actions.json manifests.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    if (editableIdentities.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "No user or agent identities registered yet.\nCreate a user identity first.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        // Legend
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Danger levels:", style = MaterialTheme.typography.labelMedium)
            LegendChip("Low", DangerLowColor)
            LegendChip("Caution", DangerCautionColor)
            LegendChip("Danger", DangerDangerColor)
            Spacer(Modifier.width(16.dp))
            Icon(Icons.Default.Warning, null, tint = DangerCautionColor, modifier = Modifier.size(16.dp))
            Text("= escalated above parent", style = MaterialTheme.typography.labelSmall)
        }

        HorizontalDivider()

        // Matrix: fixed identity column + scrollable permission columns
        Row(Modifier.fillMaxSize()) {
            // --- Fixed left column: identity names ---
            Column(Modifier.width(160.dp)) {
                // Header cell
                Box(
                    Modifier.height(72.dp).fillMaxWidth().padding(4.dp),
                    contentAlignment = Alignment.BottomStart
                ) {
                    Text(
                        "Identity",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                HorizontalDivider()

                LazyColumn {
                    items(editableIdentities, key = { it.handle }) { identity ->
                        Box(
                            Modifier.height(48.dp).fillMaxWidth().padding(horizontal = 4.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Column {
                                Text(
                                    identity.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    identity.handle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        HorizontalDivider(thickness = 0.5.dp)
                    }
                }
            }

            VerticalDivider()

            // --- Scrollable right section: permission columns ---
            Column(Modifier.fillMaxSize().horizontalScroll(horizontalScrollState)) {
                // --- Column headers: grouped by domain ---
                Row(Modifier.height(72.dp)) {
                    for ((domain, decls) in groupedPermissions) {
                        for (decl in decls) {
                            PermissionColumnHeader(
                                domain = domain,
                                declaration = decl
                            )
                        }
                    }
                }
                HorizontalDivider()

                // --- Data rows ---
                LazyColumn {
                    items(editableIdentities, key = { "perm-${it.handle}" }) { identity ->
                        val effective = effectivePermsMap[identity.handle] ?: emptyMap()
                        val parentEffective = parentEffectivePermsMap[identity.handle]

                        Row(Modifier.height(48.dp)) {
                            for (permKey in flatPermKeys) {
                                val effectiveGrant = effective[permKey]
                                val effectiveLevel = effectiveGrant?.level ?: PermissionLevel.NO
                                val isChecked = effectiveLevel == PermissionLevel.YES
                                val explicitGrant = identity.permissions[permKey]
                                val isInherited = explicitGrant == null && effectiveLevel != PermissionLevel.NO
                                val escalated = isEscalated(identity, permKey, effective, parentEffective)

                                PermissionCell(
                                    isChecked = isChecked,
                                    isInherited = isInherited,
                                    isEscalated = escalated,
                                    resourceScope = effectiveGrant?.resourceScope,
                                    onToggle = {
                                        val newLevel = if (isChecked) "NO" else "YES"
                                        store.dispatch("core", Action(
                                            ActionRegistry.Names.CORE_SET_PERMISSION,
                                            buildJsonObject {
                                                put("identityHandle", identity.handle)
                                                put("permissionKey", permKey)
                                                put("level", newLevel)
                                            }
                                        ))
                                    }
                                )
                            }
                        }
                        HorizontalDivider(thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

// ============================================================================
// Subcomponents
// ============================================================================

@Composable
private fun LegendChip(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(color, shape = MaterialTheme.shapes.extraSmall)
        )
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Column header for a single permission key. Shows:
 * - Short capability name (part after colon)
 * - Domain label above
 * - Color-coded bottom bar by dangerLevel
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PermissionColumnHeader(
    domain: String,
    declaration: ActionRegistry.PermissionDeclaration
) {
    val color = dangerColor(declaration.dangerLevel)
    val capability = capabilityName(declaration.key)

    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        state = rememberTooltipState(),
        tooltip = {
            PlainTooltip {
                Text(
                    "${declaration.key}\n${declaration.description}\nDanger: ${declaration.dangerLevel}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .width(80.dp)
                .fillMaxHeight()
                .padding(horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom
        ) {
            Text(
                domain,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                capability,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            // Danger level indicator bar
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(color)
            )
        }
    }
}

/**
 * A single cell in the permission matrix. Shows a checkbox for YES/NO,
 * with visual indicators for inherited and escalated states.
 * Phase 3: shows resourceScope as a small label when present.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PermissionCell(
    isChecked: Boolean,
    isInherited: Boolean,
    isEscalated: Boolean,
    resourceScope: String?,
    onToggle: () -> Unit
) {
    val bgColor = when {
        isEscalated -> EscalationBgColor
        else -> Color.Transparent
    }

    val cellContent = @Composable {
        Box(
            modifier = Modifier
                .width(80.dp)
                .fillMaxHeight()
                .background(bgColor),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Checkbox(
                        checked = isChecked,
                        onCheckedChange = { onToggle() },
                        colors = CheckboxDefaults.colors(
                            checkedColor = if (isInherited)
                                MaterialTheme.colorScheme.outline
                            else
                                MaterialTheme.colorScheme.primary
                        )
                    )
                    if (isEscalated) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Escalated above parent",
                            tint = DangerCautionColor,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                // Phase 3: Show resource scope prefix when present
                if (resourceScope != null) {
                    Text(
                        text = resourceScope,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    if (resourceScope != null) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            state = rememberTooltipState(),
            tooltip = {
                PlainTooltip {
                    Text("Scope: $resourceScope", style = MaterialTheme.typography.bodySmall)
                }
            }
        ) {
            cellContent()
        }
    } else {
        cellContent()
    }
}