package asareon.raam.ui.components.footer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import asareon.raam.ui.theme.spacing

/**
 * The standard bottom footer for a view that has a commit CTA.
 *
 * Layout:
 *
 * ```
 * ─────────────────────────────────────────────────────────
 * [leading — optional info/meta]   [actions — right-aligned]
 * ```
 *
 * Renders a top divider, `surfaceContainer` tone, screen-edge horizontal
 * padding, and a height matching the top bar. [leading] is pushed to the
 * left and may be left empty; [actions] are right-aligned and are where
 * [FooterButton]s go. Compose Cancel before Confirm so Cancel sits on
 * the left of Confirm (M3 affirmative-on-the-right).
 *
 * Views use this whenever they present a draft/edit that the user must
 * explicitly commit. Views without a commit step do not need a footer.
 */
@Composable
fun ViewFooter(
    modifier: Modifier = Modifier,
    leading: (@Composable RowScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MaterialTheme.spacing.topBarHeight)
                    .padding(horizontal = MaterialTheme.spacing.screenEdge),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.inner),
            ) {
                if (leading != null) {
                    leading()
                }
                Spacer(Modifier.weight(1f))
                actions()
            }
        }
    }
}
