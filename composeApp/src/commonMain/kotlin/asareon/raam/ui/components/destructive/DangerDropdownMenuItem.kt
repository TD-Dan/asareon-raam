package asareon.raam.ui.components.destructive

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Standard rendering for a destructive action inside a `DropdownMenu`.
 *
 * Destructive actions in Raam are always hidden behind a kebab — never
 * inline — to reduce accidental clicks. This helper enforces the consistent
 * visual treatment: the label text and the leading icon are tinted with
 * `MaterialTheme.colorScheme.error`, and the default icon is the trash-can
 * glyph ([Icons.Default.Delete]).
 *
 * Convention: pair a `HorizontalDivider()` before this item in the menu to
 * visually separate safe actions (Clone, Clear, Edit) from destructive ones.
 *
 * Wording: use "Delete X" for labels that actually destroy an owned asset.
 * For non-destructive detach-from-list actions, use a regular
 * `DropdownMenuItem` with "Remove from …" wording instead.
 */
@Composable
fun DangerDropdownMenuItem(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector = Icons.Default.Delete,
    enabled: Boolean = true,
) {
    val errorColor = MaterialTheme.colorScheme.error
    DropdownMenuItem(
        text = { Text(label, color = errorColor) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = errorColor) },
        enabled = enabled,
        onClick = onClick,
    )
}
