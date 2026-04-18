package asareon.raam.ui.components.sidepane

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Observable state for a single [SidePane]: its current size along each
 * splitter axis. [width] is used when the layout is side-by-side; [height]
 * is used when the layout is stacked. Both are tracked independently so
 * the user can prefer e.g. a wide side-by-side pane and a short stacked
 * pane, and neither choice stomps the other when the window resizes
 * across the breakpoint.
 *
 * **Bounds.** The state enforces only the lower bound [minSize]. The
 * *upper* bound depends on the current container size (width / height)
 * and is applied by the layout via [dragWidthBy] / [dragHeightBy] and
 * [coerceWidthToContainer] / [coerceHeightToContainer]. This lets the
 * pane stretch to any size the user wants — limited only by
 * "leave at least [minSize] for the primary content".
 *
 * The class is [Stable] so Compose can elide recomposition when no
 * observed property changed.
 */
@Stable
class SidePaneState(
    initialWidth: Dp = SidePaneDefaults.DefaultPaneWidth,
    initialHeight: Dp = SidePaneDefaults.DefaultPaneHeight,
    val minSize: Dp = SidePaneDefaults.MinPaneSize,
) {
    init {
        require(minSize > 0.dp) { "SidePaneState minSize ($minSize) must be > 0" }
    }

    private var _width by mutableStateOf(initialWidth.coerceAtLeast(minSize))
    private var _height by mutableStateOf(initialHeight.coerceAtLeast(minSize))

    /**
     * Current pane width in side-by-side layout. Writing a value smaller
     * than [minSize] clamps to [minSize]. Upper bound is not enforced at
     * this layer — the layout calls [coerceWidthToContainer] once it
     * knows the viewport size.
     */
    var width: Dp
        get() = _width
        set(value) {
            _width = value.coerceAtLeast(minSize)
        }

    /** Current pane height in stacked layout. Same clamping rule as [width]. */
    var height: Dp
        get() = _height
        set(value) {
            _height = value.coerceAtLeast(minSize)
        }

    /**
     * Clamp [width] into `[minSize, containerWidth - minSize]`. Call from
     * the layout whenever the viewport size changes so a previously-dragged
     * width doesn't leave the pane overflowing a shrunken window.
     *
     * If the container is too narrow to honour both bounds (i.e.
     * `containerWidth < 2 * minSize`), width is pinned to [minSize].
     */
    fun coerceWidthToContainer(containerWidth: Dp) {
        val effectiveMax = (containerWidth - minSize).coerceAtLeast(minSize)
        _width = _width.coerceIn(minSize, effectiveMax)
    }

    fun coerceHeightToContainer(containerHeight: Dp) {
        val effectiveMax = (containerHeight - minSize).coerceAtLeast(minSize)
        _height = _height.coerceIn(minSize, effectiveMax)
    }

    /**
     * Apply a signed width delta, clamped to
     * `[minSize, containerWidth - minSize]`. Positive grows the pane.
     * Over-drag past a boundary does not accumulate debt — a subsequent
     * opposite-direction drag starts moving immediately.
     */
    fun dragWidthBy(deltaDp: Dp, containerWidth: Dp) {
        val effectiveMax = (containerWidth - minSize).coerceAtLeast(minSize)
        _width = (_width + deltaDp).coerceIn(minSize, effectiveMax)
    }

    /** Drag the pane height. Same semantics as [dragWidthBy]. */
    fun dragHeightBy(deltaDp: Dp, containerHeight: Dp) {
        val effectiveMax = (containerHeight - minSize).coerceAtLeast(minSize)
        _height = (_height + deltaDp).coerceIn(minSize, effectiveMax)
    }
}

/**
 * Remember a [SidePaneState] across recompositions.
 *
 * The state is keyed by [minSize] — changing [initialWidth] or
 * [initialHeight] after the first composition does not resize an existing
 * pane. For persisted sizes, read the saved value once and pass it as
 * the initial.
 */
@Composable
fun rememberSidePaneState(
    initialWidth: Dp = SidePaneDefaults.DefaultPaneWidth,
    initialHeight: Dp = SidePaneDefaults.DefaultPaneHeight,
    minSize: Dp = SidePaneDefaults.MinPaneSize,
): SidePaneState = remember(minSize) {
    SidePaneState(
        initialWidth = initialWidth,
        initialHeight = initialHeight,
        minSize = minSize,
    )
}
