package asareon.raam.feature.core

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
import asareon.raam.core.*
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.core.resolveDisplayColor
import asareon.raam.ui.LocalExtendedColors
import asareon.raam.ui.components.IconRegistry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ============================================================================
// Theme-Aware Color Helpers
// ============================================================================

/** Cell background alpha for granted (YES) permissions. */
private const val CELL_TINT_ALPHA = 0.10f

/**
 * Returns the semantic color for a given danger level from extended colors.
 * These are stable regardless of identity-based theme overrides.
 * LOW → success (green), CAUTION → warning (amber), DANGER → danger (red).
 */
@Composable
private fun dangerColor(dangerLevel: DangerLevel): Color {
    val extended = LocalExtendedColors.current
    return when (dangerLevel) {
        DangerLevel.LOW -> extended.success
        DangerLevel.CAUTION -> extended.warning
        DangerLevel.DANGER -> extended.danger
    }
}

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
            // Features first (uuid == null), then children — both sorted by handle
            .sortedWith(compareBy<Identity> { it.uuid != null }.thenBy { it.handle })
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
                "No identities registered yet.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        // Help
        Text(
            "Control what each identity is allowed to do. Feature defaults (bold rows) apply to all " +
                    "children of that feature unless explicitly overridden. Grant permissions to individual " +
                    "agents or users to override their parent's defaults.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        // Legend
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Danger levels:", style = MaterialTheme.typography.labelMedium)
            LegendChip("Low", dangerColor(DangerLevel.LOW))
            LegendChip("Caution", dangerColor(DangerLevel.CAUTION))
            LegendChip("Danger", dangerColor(DangerLevel.DANGER))
            Spacer(Modifier.width(16.dp))
            Icon(Icons.Default.Warning, null, tint = dangerColor(DangerLevel.CAUTION), modifier = Modifier.size(16.dp))
            Text("= escalated above parent", style = MaterialTheme.typography.labelSmall)
        }

        HorizontalDivider()

        // ── Warning banners ──────────────────────────────────────────
        val hasDangerGrants = remember(effectivePermsMap, declarations) {
            effectivePermsMap.values.any { effective ->
                effective.any { (key, grant) ->
                    grant.level == PermissionLevel.YES &&
                            declarations[key]?.dangerLevel == DangerLevel.DANGER
                }
            }
        }

        val hasEscalations = remember(editableIdentities, effectivePermsMap, parentEffectivePermsMap) {
            editableIdentities.any { identity ->
                val effective = effectivePermsMap[identity.handle] ?: emptyMap()
                val parentEffective = parentEffectivePermsMap[identity.handle]
                flatPermKeys.any { key -> isEscalated(identity, key, effective, parentEffective) }
            }
        }

        if (hasDangerGrants) {
            val dangerBannerColor = LocalExtendedColors.current.danger
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(dangerBannerColor.copy(alpha = CELL_TINT_ALPHA))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = dangerBannerColor,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    "Current permissions allow potentially dangerous operations to take place on your computer! " +
                            "Please consider using incremental disk backup or running inside a virtual machine for added security.",
                    style = MaterialTheme.typography.bodySmall,
                    color = dangerBannerColor
                )
            }
        }

        if (hasEscalations) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LocalExtendedColors.current.warning.copy(alpha = CELL_TINT_ALPHA))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = LocalExtendedColors.current.warning,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    "Warning: Some identities have escalated permissions beyond their parent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalExtendedColors.current.warning
                )
            }
        }

        // Matrix area with scrollbars
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Row(Modifier.fillMaxSize().padding(bottom = 12.dp, end = 12.dp)) {
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
                    Column(Modifier.weight(1f).verticalScroll(verticalScrollState)) {
                        for (identity in editableIdentities) {
                            val isFeature = identity.uuid == null
                            val identityColor = identity.resolveDisplayColor()
                            Box(
                                Modifier.height(48.dp).fillMaxWidth().padding(horizontal = 4.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    // Identity icon — emoji or Material icon, tinted
                                    val iconTint = identityColor ?: MaterialTheme.colorScheme.onSurfaceVariant
                                    if (identity.displayEmoji != null) {
                                        Text(
                                            identity.displayEmoji!!,
                                            fontSize = 16.sp,
                                            color = iconTint,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.width(20.dp)
                                        )
                                    } else {
                                        val iconVector = IconRegistry.resolve(identity.displayIcon)
                                            ?: if (isFeature) IconRegistry.defaultSystemIcon else IconRegistry.defaultAgentIcon
                                        Icon(
                                            iconVector,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = iconTint
                                        )
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            identity.name,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (isFeature) FontWeight.Bold else FontWeight.Medium,
                                            color = identityColor ?: MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            if (isFeature) "feature defaults" else identity.handle,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(thickness = 0.5.dp)
                        }
                    }
                }

                VerticalDivider()

                // --- Scrollable right section: permission columns ---
                Column(Modifier.weight(1f).fillMaxHeight()) {
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
                            .weight(1f)
                            .fillMaxWidth()
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
                                    val escalated = isEscalated(identity, permKey, effective, parentEffective)
                                    val dangerLevel = declarations[permKey]?.dangerLevel ?: DangerLevel.LOW

                                    PermissionCell(
                                        isChecked = isChecked,
                                        dangerLevel = dangerLevel,
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

            // Visible scrollbar style for both axes
            val scrollbarStyle = ScrollbarStyle(
                minimalHeight = 48.dp,
                thickness = 8.dp,
                shape = MaterialTheme.shapes.small,
                hoverDurationMillis = 300,
                unhoverColor = MaterialTheme.colorScheme.outline,
                hoverColor = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Vertical scrollbar — anchored to the right edge, below the header row
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(verticalScrollState),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(top = 80.dp, bottom = 12.dp),
                style = scrollbarStyle
            )

            // Horizontal scrollbar — anchored to the bottom edge, right of the identity column
            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(horizontalScrollState),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 168.dp, end = 12.dp),
                style = scrollbarStyle
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
        state = rememberTooltipState(isPersistent = true),
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
 * A single cell in the permission matrix. Shows a checkbox for YES/NO.
 * - YES cells are tinted according to the permission's danger level
 *   (green = low, orange = caution, red = danger) for at-a-glance risk visibility.
 * - NO / unchecked cells have no background.
 * - Escalated grants (child exceeds parent) show a warning icon.
 */
@Composable
private fun PermissionCell(
    isChecked: Boolean,
    dangerLevel: DangerLevel,
    isEscalated: Boolean,
    onToggle: () -> Unit
) {
    val color = dangerColor(dangerLevel)

    Box(
        modifier = Modifier
            .width(80.dp)
            .fillMaxHeight()
            .then(if (isChecked) Modifier.background(color.copy(alpha = CELL_TINT_ALPHA)) else Modifier),
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
                    checkedColor = color,
                    uncheckedColor = MaterialTheme.colorScheme.outline,
                    checkmarkColor = if (isChecked) {
                        // Use appropriate on-color for readability
                        val extended = LocalExtendedColors.current
                        when (dangerLevel) {
                            DangerLevel.LOW -> extended.onSuccess
                            DangerLevel.CAUTION -> extended.onWarning
                            DangerLevel.DANGER -> extended.onDanger
                        }
                    } else MaterialTheme.colorScheme.onPrimary
                )
            )
            if (isEscalated) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = "Escalated above parent",
                    tint = LocalExtendedColors.current.warning,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}