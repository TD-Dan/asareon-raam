package asareon.raam.ui.components.topbar

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import asareon.raam.ui.theme.spacing

/**
 * Minimum width reserved for the title area before actions start spilling
 * into the overflow kebab. Ensures the title never collapses to nothing on
 * narrow windows.
 */
private val MinTitleWidth = 120.dp

/**
 * The standard header content used by most screens: optional [leading] slot,
 * [title] (with optional [subtitle]), and a responsive [actions] row with
 * overflow kebab. [subContent] is rendered by [RaamTopBar] beneath the bar.
 *
 * For non-standard headers (e.g. tabs-as-title) call [RaamTopBar] directly
 * and compose your own header layout.
 */
@Composable
fun RaamTopBarHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: HeaderLeading = HeaderLeading.None,
    actions: List<HeaderAction> = emptyList(),
    subContent: @Composable (() -> Unit)? = null,
) {
    RaamTopBar(modifier = modifier, subContent = subContent) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val rowWidth = maxWidth
            val leadingReserve = if (leading is HeaderLeading.None) 0.dp else HeaderActionSlotWidth
            val actionBudget = (rowWidth - leadingReserve - MinTitleWidth).coerceAtLeast(0.dp)
            val maxSlots = (actionBudget / HeaderActionSlotWidth).toInt()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.tight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LeadingSlot(leading)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = MaterialTheme.spacing.inner),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                if (actions.isNotEmpty()) {
                    ResponsiveActions(actions = actions, maxVisibleSlots = maxSlots)
                }
            }
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
