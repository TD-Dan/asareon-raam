package asareon.raam.ui.components.topbar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import asareon.raam.ui.theme.spacing

/**
 * Minimal top-bar container. Owns the surface, minimum height, and a divider
 * below. Does NOT know about titles, tabs, or actions — callers fill [header]
 * with whatever layout belongs there.
 *
 * Most screens should call [RaamTopBarHeader] instead, which renders a
 * standard title + leading + actions configuration into this container.
 * Screens with special needs (e.g. SessionView's tabs-as-title) call
 * [RaamTopBar] directly and compose their own header layout.
 *
 * Use [subContent] for anything that belongs *under* the header row but is
 * not part of the header itself — path displays, filter chips, help banners.
 */
@Composable
fun RaamTopBar(
    modifier: Modifier = Modifier,
    subContent: @Composable (() -> Unit)? = null,
    header: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MaterialTheme.spacing.topBarHeight),
            ) {
                header()
            }
            if (subContent != null) {
                subContent()
            }
            HorizontalDivider()
        }
    }
}
