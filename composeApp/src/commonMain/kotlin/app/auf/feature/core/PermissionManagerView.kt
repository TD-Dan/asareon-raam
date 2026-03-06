package app.auf.feature.core

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
    val explicitGrant = identity.permissions[permKey] ?: return false
    val parentGrant = parentEffectivePerms[permKey]
    val parentLevel = parentGrant?.level ?: PermissionLevel.NO
    return explicitGrant.level > parentLevel
}

// ============================================================================
// Main View
// ============================================================================

/**
 * Permission Manager — matrix UI.
 *
 * Layout: fixed identity column on the left + horizontally scrollable permission
 * matrix on the right. Both share a single vertical scroll state so rows stay
 * aligned. Visible scrollbars on both axes.
 */
@Composable
fun PermissionManagerView(store: Store) {
    val appState by store.state.collectAsState()

    // Shared scroll states — one vertical (rows), one horizontal (permission columns)
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()

    val declarations = ActionRegistry.permissionDeclarations
    val groupedPermissions = remember(declarations) { groupPermissionsByDomain(declarations) }
    val flatPermKeys = remember(groupedPermissions) {
        groupedPermissions.flatMap { (_, decls) -> decls.map { it.key } }
    }

    val editableIdentities = remember(appState.identityRegistry) {
        appState.identityRegistry.values
            .filter { it.uuid != null }
            .sortedBy { it.handle }
    }

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

        // Matrix area with scrollbars
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize().padding(bottom = 8.dp, end = 8.dp)) {
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

                    // Identity rows — shares verticalScrollState with the matrix
                    Column(Modifier.verticalScroll(verticalScrollState)) {
                        for (identity in editableIdentities) {
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
                Column(Modifier.fillMaxSize()) {
                    // Column headers scroll horizontally but stay pinned vertically
                    Row(Modifier.height(72.dp).horizontalScroll(horizontalScrollState)) {
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

                    // Data rows — shares both scroll states
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(verticalScrollState)
                            .horizontalScroll(horizontalScrollState)
                    ) {
                        for (identity in editableIdentities) {
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

            // Vertical scrollbar — anchored to the right edge, below the header row
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(verticalScrollState),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(top = 72.dp)
            )

            // Horizontal scrollbar — anchored to the bottom edge, right of the identity column
            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(horizontalScrollState),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 160.dp)
            )
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
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
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
 * Checkbox uses theme-consistent colors — no white fill on unchecked state.
 */
@Composable
private fun PermissionCell(
    isChecked: Boolean,
    isInherited: Boolean,
    isEscalated: Boolean,
    onToggle: () -> Unit
) {
    val bgColor = when {
        isEscalated -> EscalationBgColor
        else -> Color.Transparent
    }

    Box(
        modifier = Modifier
            .width(80.dp)
            .fillMaxHeight()
            .background(bgColor),
        contentAlignment = Alignment.Center
    ) {
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
                        MaterialTheme.colorScheme.primary,
                    uncheckedColor = MaterialTheme.colorScheme.outline,
                    checkmarkColor = MaterialTheme.colorScheme.onPrimary
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
    }
}