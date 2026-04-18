package asareon.raam.ui.components.topbar

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Standard-title convenience over [RaamTopBar]. Renders [title] (and optional
 * [subtitle]) in the center slot; delegates leading, actions, and subContent
 * to [RaamTopBar].
 *
 * Use this when the center of the bar is plain text. For a tab row, inline
 * controls alongside the title, or any other custom center content, call
 * [RaamTopBar] directly.
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
    RaamTopBar(
        modifier = modifier,
        leading = leading,
        actions = actions,
        subContent = subContent,
    ) {
        Column {
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
    }
}
