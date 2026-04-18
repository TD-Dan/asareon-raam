package asareon.raam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import asareon.raam.core.*
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.feature.core.CoreState
import asareon.raam.ui.theme.spacing
import asareonraam.composeapp.generated.resources.Res
import asareonraam.composeapp.generated.resources.icon
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.compose.resources.painterResource

/**
 * Vertical cost per ribbon slot: 48 dp IconButton + 8 dp spacedBy gap
 * between consecutive children. The gap is charged per-slot rather than
 * per-pair because it aligns with how much *additional* room each new slot
 * needs below the previous one, making the [maxHeight] / slot division come
 * out to an accurate count that doesn't clip the last icon.
 */
private val RibbonSlotHeight = 56.dp

/**
 * Fixed vertical cost of the non-collapsible ribbon chrome: top + bottom
 * padding (16 dp) plus the home IconButton (48 dp). No spacedBy gap here —
 * the gap below the home button is already absorbed into [RibbonSlotHeight]
 * of the first entry.
 */
private val RibbonChromeHeight = 64.dp

@Composable
fun GlobalActionRibbon(
    store: Store,
    features: List<Feature>,
    activeViewKey: String?
) {
    val appState by store.state.collectAsState()
    val coreState = remember(appState.featureStates) {
        appState.featureStates["core"] as? CoreState
    }
    val defaultViewKey = coreState?.defaultViewKey ?: "feature.session.main"

    // Collect all structured entries from all features (sort happens in
    // computeRibbonLayout so the ordering is testable in isolation).
    val structuredEntries = features
        .asSequence()
        .mapNotNull { it.composableProvider }
        .flatMap { it.ribbonEntries(store, activeViewKey).asSequence() }
        .toList()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxHeight()
            .width(MaterialTheme.spacing.ribbonWidth)
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        val slotBudget = ((maxHeight - RibbonChromeHeight) / RibbonSlotHeight)
            .toInt()
            .coerceAtLeast(0)
        val layout = computeRibbonLayout(structuredEntries, slotBudget)

        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = MaterialTheme.spacing.inner),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.inner)
        ) {
            // --- Master Home Button ---
            val payload = buildJsonObject { put("key", defaultViewKey) }
            IconButton(onClick = {
                store.deferredDispatch(
                    "system",
                    Action(ActionRegistry.Names.CORE_SET_ACTIVE_VIEW, payload)
                )
            }) {
                Icon(
                    painter = painterResource(Res.drawable.icon),
                    contentDescription = "Go to Default View (Session)",
                    tint = Color.Unspecified
                )
            }

            // --- Structured ribbon entries (stacked from top) ---
            layout.visible.forEach { entry ->
                RibbonIconButton(entry)
            }
            if (layout.overflowVisible) {
                RibbonOverflowButton(layout.overflow)
            }
        }
    }
}

@Composable
private fun RibbonIconButton(entry: RibbonEntry) {
    val tint = if (entry.isActive) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface
    IconButton(onClick = entry.onClick) {
        Icon(entry.icon, contentDescription = entry.label, tint = tint)
    }
}

@Composable
private fun RibbonOverflowButton(entries: List<RibbonEntry>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.Menu, contentDescription = "More views")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.label) },
                    leadingIcon = { Icon(entry.icon, contentDescription = null) },
                    onClick = {
                        expanded = false
                        entry.onClick()
                    },
                )
            }
        }
    }
}
