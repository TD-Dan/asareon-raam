package asareon.raam.ui.components.topbar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import asareon.raam.ui.theme.spacing

/** Width reserved per plain `IconButton` (and the kebab itself). M3 touch-target. */
val HeaderActionSlotWidth = 48.dp

/**
 * Approximate slot-cost of a labelled `FilledTonalButton` ([HeaderActionEmphasis.Prominent]
 * or [HeaderActionEmphasis.Create]) — about three icon-button widths.
 */
private const val LabelledButtonSlots = 3

private fun HeaderAction.slotCost(): Int = when (emphasis) {
    HeaderActionEmphasis.Icon -> 1
    HeaderActionEmphasis.Prominent, HeaderActionEmphasis.Create -> LabelledButtonSlots
}

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
 * Split [actions] into visible vs. overflow:
 *  - priority < 0 → always overflow.
 *  - priority ≥ 0 → candidate for visible row.
 *  - Sort order: [HeaderActionEmphasis.Create] always sorts before other
 *    emphases, then by priority desc, then by input order. This puts the
 *    create-action leftmost in the visible row and makes it the last to
 *    spill into the kebab when the bar narrows.
 *  - Slot cost: 1 for an icon-emphasis action, 3 for Prominent or Create
 *    (approximating the width of a labelled button).
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
            // Create wins first no matter the declared priority.
            compareByDescending<IndexedValue<HeaderAction>> {
                if (it.value.emphasis == HeaderActionEmphasis.Create) 1 else 0
            }
                .thenByDescending { it.value.priority }
                .thenBy { it.index }
        )
        .map { it.value }

    val slots = maxVisibleSlots.coerceAtLeast(0)
    val totalCost = candidates.sumOf { it.slotCost() }
    val allFit = totalCost <= slots && alwaysOverflow.isEmpty()
    val costBudget = if (allFit) slots else (slots - 1).coerceAtLeast(0)

    val visible = mutableListOf<HeaderAction>()
    var used = 0
    for (action in candidates) {
        val cost = action.slotCost()
        if (used + cost > costBudget) break
        visible.add(action)
        used += cost
    }
    val overflow = candidates.drop(visible.size) + alwaysOverflow

    return HeaderActionLayout(visible, overflow)
}

/**
 * Renders [actions] as a row of buttons, spilling the lowest-priority
 * actions into an overflow kebab when [maxVisibleSlots] is exceeded.
 *
 * See [computeHeaderActionLayout] for the sort + overflow rules.
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

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.inner),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        layout.visible.forEach { action ->
            when (action.emphasis) {
                HeaderActionEmphasis.Icon -> TooltipIconButton(
                    label = action.label,
                    onClick = action.onClick,
                    enabled = action.enabled,
                ) {
                    Icon(action.icon, contentDescription = action.label)
                }
                HeaderActionEmphasis.Prominent -> LabelledActionButton(
                    action = action,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                HeaderActionEmphasis.Create -> LabelledActionButton(
                    action = action,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
        if (layout.kebabVisible) {
            OverflowKebab(layout.overflow)
        }
    }
}

@Composable
private fun LabelledActionButton(
    action: HeaderAction,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
) {
    FilledTonalButton(
        onClick = action.onClick,
        enabled = action.enabled,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(action.label)
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
