package asareon.raam.ui.components.topbar

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Width reserved per `IconButton` (and the kebab itself). M3 touch-target. */
val HeaderActionSlotWidth = 48.dp

/**
 * Renders [actions] as a row of `IconButton`s, spilling the lowest-priority
 * actions into an overflow kebab when [maxVisibleSlots] is exceeded.
 *
 * Priority rules:
 *  - Actions with priority ≥ 0 are candidates for the visible row.
 *  - Actions with priority < 0 always live in the kebab.
 *  - Among candidates, highest priority wins. Ties break by input order.
 *
 * The kebab counts as one visible slot whenever it's rendered. It's rendered
 * whenever at least one action would be hidden.
 *
 * @param maxVisibleSlots The maximum number of icon-button slots the caller
 *   wants rendered in the row (including the kebab if present). Pass
 *   [Int.MAX_VALUE] to disable visible-count clamping (the kebab still appears
 *   if there are any negative-priority actions).
 */
@Composable
fun ResponsiveActions(
    actions: List<HeaderAction>,
    maxVisibleSlots: Int = Int.MAX_VALUE,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) return

    val alwaysOverflow = actions.filter { it.priority < 0 }
    val candidates = actions
        .filter { it.priority >= 0 }
        .withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<HeaderAction>> { it.value.priority }
                .thenBy { it.index }
        )
        .map { it.value }

    val slots = maxVisibleSlots.coerceAtLeast(0)
    val allFit = candidates.size <= slots && alwaysOverflow.isEmpty()
    val kebabNeeded = !allFit

    val visibleBudget = if (kebabNeeded) (slots - 1).coerceAtLeast(0) else slots
    val visible = candidates.take(visibleBudget)
    val overflow = candidates.drop(visibleBudget) + alwaysOverflow

    Row(modifier = modifier) {
        visible.forEach { action ->
            TooltipIconButton(
                label = action.label,
                onClick = action.onClick,
                enabled = action.enabled,
            ) {
                Icon(action.icon, contentDescription = action.label)
            }
        }
        if (kebabNeeded && overflow.isNotEmpty()) {
            OverflowKebab(overflow)
        }
    }
}

@Composable
private fun OverflowKebab(entries: List<HeaderAction>) {
    var expanded by remember { mutableStateOf(false) }
    TooltipIconButton(
        label = "More actions",
        onClick = { expanded = true },
    ) {
        Icon(Icons.Default.MoreVert, contentDescription = "More actions")
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
    ) {
        entries.forEach { action ->
            DropdownMenuItem(
                text = { Text(action.label) },
                leadingIcon = { Icon(action.icon, contentDescription = null) },
                enabled = action.enabled,
                onClick = {
                    expanded = false
                    action.onClick()
                },
            )
        }
    }
}
