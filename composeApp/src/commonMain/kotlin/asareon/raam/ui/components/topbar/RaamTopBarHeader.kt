package asareon.raam.ui.components.topbar

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle

/**
 * Visual weight of a [RaamTopBarHeader]. Two tiers:
 *
 *  - [Primary]: the title of a top-level view. Uses `titleLarge` (~22sp) for
 *    the title and `labelMedium` (~12sp) for the subtitle. This is the
 *    default and matches the main-view top bar elsewhere in the app.
 *  - [Secondary]: the title of a subordinate surface — a side pane, a
 *    supporting pane, a nested section. Uses `titleMedium` (~16sp) and
 *    `labelSmall` (~11sp). Bar height and trailing-action chrome are
 *    unchanged so seams still align horizontally with a neighbouring
 *    Primary bar.
 *
 * Size-only styling: the enum is not a replacement for [HeaderActionEmphasis]
 * and does not affect action buttons.
 */
enum class HeaderStyle { Primary, Secondary }

/**
 * Standard-title convenience over [RaamTopBar]. Renders [title] (and optional
 * [subtitle]) in the center slot; delegates leading, actions, and subContent
 * to [RaamTopBar].
 *
 * Use this when the center of the bar is plain text. For a tab row, inline
 * controls alongside the title, or any other custom center content, call
 * [RaamTopBar] directly.
 *
 * [style] picks the typography tier — see [HeaderStyle]. Use [HeaderStyle.Secondary]
 * for side panes and other subordinate surfaces.
 */
@Composable
fun RaamTopBarHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: HeaderLeading = HeaderLeading.None,
    actions: List<HeaderAction> = emptyList(),
    style: HeaderStyle = HeaderStyle.Primary,
    subContent: @Composable (() -> Unit)? = null,
) {
    val titleStyle: TextStyle = when (style) {
        HeaderStyle.Primary -> MaterialTheme.typography.titleLarge
        HeaderStyle.Secondary -> MaterialTheme.typography.titleMedium
    }
    val subtitleStyle: TextStyle = when (style) {
        HeaderStyle.Primary -> MaterialTheme.typography.labelMedium
        HeaderStyle.Secondary -> MaterialTheme.typography.labelSmall
    }
    RaamTopBar(
        modifier = modifier,
        leading = leading,
        actions = actions,
        subContent = subContent,
    ) {
        Column {
            Text(
                text = title,
                style = titleStyle,
                maxLines = 1,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = subtitleStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
