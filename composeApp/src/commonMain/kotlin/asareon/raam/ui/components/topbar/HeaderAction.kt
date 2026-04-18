package asareon.raam.ui.components.topbar

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Visual emphasis for a [HeaderAction]. Three tiers:
 *
 *  - [Icon] (default): plain `IconButton`, neutral tint, tooltip on hover.
 *  - [Prominent]: `FilledTonalButton` with icon + full label, uses
 *    `secondaryContainer`. For medium-emphasis actions like secondary
 *    destinations ("Permissions", "System Resources").
 *  - [Create]: `FilledTonalButton` with icon + full label, uses
 *    `tertiaryContainer`. For the "create a new asset" action on a screen
 *    (Create Session, Create Agent, Create Persona…).
 *
 * **Sort order:** [Create] actions always sort before [Prominent] and [Icon]
 * actions regardless of their declared priority, placing them leftmost in
 * the trailing action row. Within each emphasis group the normal priority
 * rules apply. This makes the primary creation action the most persistent
 * (last to spill into the overflow kebab).
 *
 * **Collapse behaviour:** When the bar is narrow, lower-priority actions
 * spill into the kebab first. Because Create sorts first, it is the last
 * to be hidden, and only collapses when the bar is so narrow no button
 * with a label can fit.
 */
enum class HeaderActionEmphasis { Icon, Prominent, Create }

/**
 * A single action rendered in a [RaamTopBarHeader] (or any custom header
 * that uses [ResponsiveActions] / [RaamTopBar]).
 *
 * Priority decides which actions render as visible buttons vs. spill into
 * the overflow kebab when the bar is narrow. Higher values win; ties break
 * by input order. Negative values are "overflow-only" — always rendered
 * inside the kebab regardless of available width.
 *
 * [emphasis] picks the visual tier (see [HeaderActionEmphasis]) and — for
 * [HeaderActionEmphasis.Create] — also biases the sort order so the action
 * renders leftmost and is the last to spill.
 */
@Immutable
data class HeaderAction(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val priority: Int = 0,
    val enabled: Boolean = true,
    val emphasis: HeaderActionEmphasis = HeaderActionEmphasis.Icon,
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
