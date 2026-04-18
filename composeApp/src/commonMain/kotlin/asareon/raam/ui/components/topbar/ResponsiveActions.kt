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
 * Pure-data result of splitting a set of [HeaderAction]s into a visible row
 * and an overflow list, given a slot budget. Extracted from [ResponsiveActions]
 * so the layout logic can be unit-tested without a Compose runtime.
 */
internal data class HeaderActionLayout(
    val visible: List<HeaderAction>,
    val overflow: List<HeaderAction>,
) {
    /** Whether the kebab should render (it counts as one of the visible slots). */
    val kebabVisible: Boolean get() = overflow.isNotEmpty()
}

/**
 * Split [actions] into visible vs. overflow following the priority rules:
 *  - priority < 0 → always overflow.
 *  - priority ≥ 0 → candidate for visible row; highest priority wins, ties
 *    break by input order.
 *  - If any action would be hidden, one visible slot is reserved for the
 *    kebab; the kebab then holds everything that didn't fit.
 *
 * @param maxVisibleSlots maximum total slots in the visible row, *including*
 *   the kebab. [Int.MAX_VALUE] disables slot clamping (kebab only appears for
 *   negative-priority entries). Negative values are coerced to 0.
 */
internal fun computeHeaderActionLayout(
    actions: List<HeaderAction>,
    maxVisibleSlots: Int,
): HeaderActionLayout {
    if (actions.isEmpty()) return HeaderActionLayout(emptyList(), emptyList())

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
    val visibleBudget = if (allFit) slots else (slots - 1).coerceAtLeast(0)
    val visible = candidates.take(visibleBudget)
    val overflow = candidates.drop(visibleBudget) + alwaysOverflow

    return HeaderActionLayout(visible, overflow)
}

/**
 * Renders [actions] as a row of `IconButton`s, spilling the lowest-priority
 * actions into an overflow kebab when [maxVisibleSlots] is exceeded.
 *
 * See [computeHeaderActionLayout] for the priority rules.
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
    val layout = computeHeaderActionLayout(actions, maxVisibleSlots)

    Row(modifier = modifier) {
        layout.visible.forEach { action ->
            TooltipIconButton(
                label = action.label,
                onClick = action.onClick,
                enabled = action.enabled,
            ) {
                Icon(action.icon, contentDescription = action.label)
            }
        }
        if (layout.kebabVisible) {
            OverflowKebab(layout.overflow)
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
