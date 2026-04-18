package asareon.raam.ui.components.footer

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Visual emphasis for a [FooterButton]. Two tiers:
 *
 *  - [Confirm]: filled [Button] with `primary` / `onPrimary`. The one true
 *    commit CTA of the view ("Execute Turn", "Save", "Send…").
 *  - [Cancel]: [TextButton] with neutral `onSurface`. The dismissive partner
 *    to a Confirm, for views that present a draft/edit before committing.
 *
 * A view typically pairs one Confirm with an optional Cancel inside a
 * [ViewFooter]. Confirm sits on the right (trailing), Cancel on its left.
 *
 * This mirrors `HeaderActionEmphasis` but is scoped to the view-footer
 * zone. Commit CTAs live at the bottom-right of a view, never in the header.
 */
enum class FooterActionEmphasis { Confirm, Cancel }

/**
 * A single action rendered in a [ViewFooter]. Emphasis picks the visual
 * tier (see [FooterActionEmphasis]); [icon] is optional because Cancel
 * rarely wants one.
 *
 * Label text is caller-provided verbatim — the system does not auto-label
 * Confirm as "Confirm" or Cancel as "Cancel". Use the wording that reads
 * correctly in context ("Execute Turn", "Discard draft", …).
 */
@Composable
fun FooterButton(
    emphasis: FooterActionEmphasis,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    when (emphasis) {
        FooterActionEmphasis.Confirm -> Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(label)
        }
        FooterActionEmphasis.Cancel -> TextButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(label)
        }
    }
}
