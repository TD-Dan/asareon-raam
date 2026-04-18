package asareon.raam.ui.components.sidepane

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Defaults for the [SidePane] family of components.
 *
 * The stacking breakpoint matches M3's "Expanded" window size class
 * (≥ 840dp): below that, panes stack vertically; at or above, they lay
 * out side-by-side with a draggable gutter.
 *
 * Sizing rule: the pane can be dragged to any size from [MinPaneSize] up
 * to `container - MinPaneSize` — i.e. a small hard floor, and just enough
 * space reserved for the primary content to remain clickable. There is no
 * hard ceiling. This lets users hide the pane to a sliver or let it take
 * almost the full window as their task demands.
 */
object SidePaneDefaults {
    /**
     * Minimum size the pane is allowed to shrink to along the splitter
     * axis (width when side-by-side, height when stacked). Also the slack
     * reserved for the primary content on the opposite side — the effective
     * maximum pane size is `container - MinPaneSize`.
     *
     * 20dp is intentionally small: it leaves the pane recoverable (users
     * can still grab the gutter and drag it back open) without imposing a
     * subjective "this pane needs to be usable" threshold. Pane content
     * that needs more space to remain legible should enforce its own
     * minimum via `Modifier.widthIn` / `heightIn`.
     */
    val MinPaneSize: Dp = 20.dp

    /** Starting width for a side-by-side pane. */
    val DefaultPaneWidth: Dp = 320.dp

    /** Starting height for a stacked pane (primary on top, pane below). */
    val DefaultPaneHeight: Dp = 240.dp

    /**
     * Window width below which side-by-side panes stack vertically. Matches
     * M3's Compact-to-Expanded boundary (840dp).
     */
    val StackingBreakpoint: Dp = 840.dp

    /** Visual thickness of the gutter divider line. */
    val GutterVisualThickness: Dp = 1.dp

    /**
     * Hit-testable thickness of the gutter drag zone. Wider than the
     * visual line so the cursor can grab it without pixel-hunting.
     */
    val GutterHitThickness: Dp = 8.dp
}
