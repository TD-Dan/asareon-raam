package asareon.raam.ui.components.topbar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import asareon.raam.ui.theme.spacing

/**
 * Minimum width reserved for the center slot before actions start spilling
 * into the overflow kebab. Keeps the center content from collapsing to nothing
 * on narrow windows.
 */
private val MinCenterSlotWidth = 120.dp

/**
 * The app's unified top bar. Provides the standard surface, divider,
 * leading slot, responsive actions (with overflow kebab), and an optional
 * [subContent] row beneath the bar. The [headerContent] slot is the centre
 * of the bar — callers fill it with a title, a tab row, or whatever custom
 * content the screen needs.
 *
 * For the common "just a title" case, prefer [RaamTopBarHeader].
 *
 * Layout:
 * ```
 * ┌────────────────────────────────────────────────────────────┐
 * │ [leading] {headerContent} [actions…] [kebab]               │
 * ├────────────────────────────────────────────────────────────┤
 * │ {subContent — optional, full width}                        │
 * └────────────────────────────────────────────────────────────┘
 * ```
 *
 * The center slot gets `weight(1f)` inside the header row. Any caller-supplied
 * content that needs to push sibling widgets to one edge (e.g. a title plus
 * inline affordances) should use its own `Row { Text(Modifier.weight(1f)); … }`
 * internally.
 */
@Composable
fun RaamTopBar(
    modifier: Modifier = Modifier,
    leading: HeaderLeading = HeaderLeading.None,
    actions: List<HeaderAction> = emptyList(),
    style: HeaderStyle = HeaderStyle.Primary,
    subContent: @Composable (() -> Unit)? = null,
    headerContent: @Composable () -> Unit,
) {
    val minHeight = when (style) {
        HeaderStyle.Primary -> MaterialTheme.spacing.topBarHeight
        HeaderStyle.Secondary -> MaterialTheme.spacing.secondaryTopBarHeight
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val rowWidth = maxWidth
                val leadingReserve =
                    if (leading is HeaderLeading.None) 0.dp else HeaderActionSlotWidth
                val actionBudget =
                    (rowWidth - leadingReserve - MinCenterSlotWidth).coerceAtLeast(0.dp)
                val maxSlots = (actionBudget / HeaderActionSlotWidth).toInt()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = minHeight)
                        .padding(horizontal = MaterialTheme.spacing.tight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LeadingSlot(leading)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = MaterialTheme.spacing.inner),
                    ) {
                        headerContent()
                    }
                    if (actions.isNotEmpty()) {
                        ResponsiveActions(
                            actions = actions,
                            maxVisibleSlots = maxSlots,
                        )
                    }
                }
            }
            if (subContent != null) {
                subContent()
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun LeadingSlot(leading: HeaderLeading) {
    when (leading) {
        HeaderLeading.None -> Unit
        is HeaderLeading.Back -> TooltipIconButton(
            label = "Back",
            onClick = leading.onClick,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        is HeaderLeading.Close -> TooltipIconButton(
            label = "Close",
            onClick = leading.onClick,
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close")
        }
    }
}
