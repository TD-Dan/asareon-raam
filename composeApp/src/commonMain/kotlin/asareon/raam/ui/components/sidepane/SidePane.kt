package asareon.raam.ui.components.sidepane

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import asareon.raam.ui.components.topbar.HeaderAction
import asareon.raam.ui.components.topbar.HeaderStyle
import asareon.raam.ui.components.topbar.RaamTopBarHeader

/**
 * A unified pane with the standard Raam header chrome and a
 * `surfaceContainerLow` body. Used as the secondary surface in a
 * [SidePaneLayout] (e.g. workspace files beside a session, resources beside
 * an agent editor).
 *
 * Layout:
 * ```
 * ┌─────────────────────────────────────────────────────────┐
 * │ {title}                              [actions…] [kebab] │
 * ├─────────────────────────────────────────────────────────┤
 * │ surfaceContainerLow                                     │
 * │ {content — caller-supplied, scrolls as it chooses}      │
 * └─────────────────────────────────────────────────────────┘
 * ```
 *
 * The header uses [RaamTopBarHeader] so its height, divider, and action
 * overflow behaviour match the main view top bar — the seam between the
 * pane and any sibling view aligns horizontally.
 *
 * [content] runs in a [ColumnScope] so callers can `.weight(1f)` a
 * scrolling region to fill the remaining space.
 */
@Composable
fun SidePane(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: List<HeaderAction> = emptyList(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxHeight()) {
        RaamTopBarHeader(
            title = title,
            subtitle = subtitle,
            actions = actions,
            style = HeaderStyle.Secondary,
        )
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(Modifier.fillMaxSize(), content = content)
        }
    }
}
