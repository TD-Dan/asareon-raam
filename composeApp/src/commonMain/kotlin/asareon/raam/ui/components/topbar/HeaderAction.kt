package asareon.raam.ui.components.topbar

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A single action rendered in a [RaamTopBarHeader] (or any custom header that
 * uses [ResponsiveActions]).
 *
 * Priority decides which actions render as visible buttons vs. spill into the
 * overflow kebab when the bar is narrow. Higher values win; ties break by
 * input order. Negative values are "overflow-only" — always rendered inside
 * the kebab regardless of available width.
 *
 * Set [prominent] to render the action as a full-label `FilledTonalButton`
 * (secondary-tone) when visible, drawing the user's eye to it. Prominent
 * actions take ~3 slot-widths each so they pack fewer-per-row than plain
 * icon buttons. When spilled into the kebab they render as a regular
 * menu item with label and icon, like any other overflow entry.
 */
@Immutable
data class HeaderAction(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val priority: Int = 0,
    val enabled: Boolean = true,
    val prominent: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * What (if anything) occupies the leading slot of a header.
 *
 * Semantics follow Material 3: [Back] pops the in-app navigation stack,
 * [Close] dismisses a modal-like flow. Mixing them is a lie to the user.
 */
sealed interface HeaderLeading {
    data object None : HeaderLeading
    data class Back(val onClick: () -> Unit) : HeaderLeading
    data class Close(val onClick: () -> Unit) : HeaderLeading
}
