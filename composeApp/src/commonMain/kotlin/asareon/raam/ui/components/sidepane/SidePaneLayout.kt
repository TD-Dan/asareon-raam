package asareon.raam.ui.components.sidepane

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * Which side of the layout the [SidePane] sits on when side-by-side.
 *
 * In stacked mode the side pane is always below the primary, regardless
 * of [SidePanePosition] — M3's "supporting pane" convention: keep primary
 * most visible, let the pane follow.
 */
enum class SidePanePosition { Start, End }

/**
 * Adaptive two-pane layout.
 *
 * At or above [stackingBreakpoint], renders [primary] and [sidePane]
 * side-by-side with a draggable vertical gutter between them. The cursor
 * changes to a horizontal-resize arrow over the gutter so users see it's
 * grabbable.
 *
 * Below the breakpoint, panes stack: [primary] on top, [sidePane] below,
 * separated by a draggable horizontal gutter (vertical resize cursor).
 *
 * In both modes, [paneState] tracks the pane's size along the splitter
 * axis. The layout clamps writes to `[minSize, container - minSize]` so
 * the pane can shrink to a sliver or grow to almost the full window,
 * but always leaves [SidePaneState.minSize] of space for the primary.
 * When the container resizes (window resize, breakpoint crossed), the
 * stored size is re-clamped automatically.
 *
 * Dragging-right increases the pane width when [panePosition] == Start
 * and decreases it when End — callers never flip signs themselves.
 * Dragging-down in stacked mode decreases the pane height, mirroring the
 * same "drag toward primary grows primary" rule.
 */
@Composable
fun SidePaneLayout(
    sidePane: @Composable () -> Unit,
    primary: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    paneState: SidePaneState = rememberSidePaneState(),
    panePosition: SidePanePosition = SidePanePosition.End,
    stackingBreakpoint: Dp = SidePaneDefaults.StackingBreakpoint,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val containerWidth = maxWidth
        val containerHeight = maxHeight
        val stacked = containerWidth < stackingBreakpoint
        if (stacked) {
            // Keep the stored height inside `[minSize, containerHeight - minSize]`
            // as the window grows or shrinks.
            LaunchedEffect(containerHeight) {
                paneState.coerceHeightToContainer(containerHeight)
            }
            StackedLayout(
                sidePane = sidePane,
                primary = primary,
                paneState = paneState,
                containerHeight = containerHeight,
            )
        } else {
            LaunchedEffect(containerWidth) {
                paneState.coerceWidthToContainer(containerWidth)
            }
            SideBySideLayout(
                sidePane = sidePane,
                primary = primary,
                paneState = paneState,
                panePosition = panePosition,
                containerWidth = containerWidth,
            )
        }
    }
}

@Composable
private fun SideBySideLayout(
    sidePane: @Composable () -> Unit,
    primary: @Composable () -> Unit,
    paneState: SidePaneState,
    panePosition: SidePanePosition,
    containerWidth: Dp,
) {
    Row(Modifier.fillMaxSize()) {
        when (panePosition) {
            SidePanePosition.Start -> {
                PaneWidthSlot(width = paneState.width, content = sidePane)
                VerticalDragGutter(
                    onDragDp = { deltaDp -> paneState.dragWidthBy(deltaDp, containerWidth) },
                )
                PrimaryRowSlot(content = primary)
            }
            SidePanePosition.End -> {
                PrimaryRowSlot(content = primary)
                VerticalDragGutter(
                    onDragDp = { deltaDp -> paneState.dragWidthBy(-deltaDp, containerWidth) },
                )
                PaneWidthSlot(width = paneState.width, content = sidePane)
            }
        }
    }
}

@Composable
private fun StackedLayout(
    sidePane: @Composable () -> Unit,
    primary: @Composable () -> Unit,
    paneState: SidePaneState,
    containerHeight: Dp,
) {
    Column(Modifier.fillMaxSize()) {
        PrimaryColumnSlot(content = primary)
        HorizontalDragGutter(
            // Drag down = primary grows, pane shrinks → pass -deltaDp.
            onDragDp = { deltaDp -> paneState.dragHeightBy(-deltaDp, containerHeight) },
        )
        PaneHeightSlot(height = paneState.height, content = sidePane)
    }
}

@Composable
private fun PaneWidthSlot(width: Dp, content: @Composable () -> Unit) {
    Box(Modifier.width(width).fillMaxHeight()) { content() }
}

@Composable
private fun PaneHeightSlot(height: Dp, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().height(height)) { content() }
}

@Composable
private fun RowScope.PrimaryRowSlot(content: @Composable () -> Unit) {
    Box(Modifier.weight(1f).fillMaxHeight()) { content() }
}

@Composable
private fun ColumnScope.PrimaryColumnSlot(content: @Composable () -> Unit) {
    Box(Modifier.weight(1f).fillMaxWidth()) { content() }
}

/**
 * Draggable vertical gutter (a grabbable seam between two horizontally
 * arranged panes). Visible as a single-line [VerticalDivider]; hit-tested
 * as a wider [SidePaneDefaults.GutterHitThickness] zone. Shows
 * [horizontalResizeCursor] on hover.
 *
 * Delta is reported in [Dp] with the convention **"positive = dragged
 * right"**. The caller flips the sign for end-positioned panes.
 */
@Composable
private fun VerticalDragGutter(
    onDragDp: (Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    DragGutter(
        orientation = Orientation.Horizontal,
        cursor = horizontalResizeCursor,
        onDragDp = onDragDp,
        modifier = modifier
            .fillMaxHeight()
            .width(SidePaneDefaults.GutterHitThickness),
    ) {
        VerticalDivider(modifier = Modifier.width(SidePaneDefaults.GutterVisualThickness))
    }
}

/**
 * Draggable horizontal gutter (seam between two vertically stacked panes).
 * Visible as a single-line [HorizontalDivider]; hit-tested as a wider
 * [SidePaneDefaults.GutterHitThickness] zone. Shows
 * [verticalResizeCursor] on hover.
 *
 * Delta convention: **"positive = dragged down"**. Caller flips the sign
 * when the pane is on top vs. bottom.
 */
@Composable
private fun HorizontalDragGutter(
    onDragDp: (Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    DragGutter(
        orientation = Orientation.Vertical,
        cursor = verticalResizeCursor,
        onDragDp = onDragDp,
        modifier = modifier
            .fillMaxWidth()
            .height(SidePaneDefaults.GutterHitThickness),
    ) {
        HorizontalDivider(modifier = Modifier.height(SidePaneDefaults.GutterVisualThickness))
    }
}

@Composable
private fun DragGutter(
    orientation: Orientation,
    cursor: PointerIcon,
    onDragDp: (Dp) -> Unit,
    modifier: Modifier,
    divider: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val draggableState = rememberDraggableState { deltaPx ->
        val deltaDp = with(density) { deltaPx.toDp() }
        onDragDp(deltaDp)
    }
    Box(
        modifier = modifier
            .pointerHoverIcon(cursor)
            .draggable(orientation = orientation, state = draggableState),
        contentAlignment = Alignment.Center,
    ) {
        divider()
    }
}
