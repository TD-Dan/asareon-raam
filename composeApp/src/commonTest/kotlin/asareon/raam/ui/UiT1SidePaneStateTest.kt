package asareon.raam.ui

import androidx.compose.ui.unit.dp
import asareon.raam.ui.components.sidepane.SidePaneDefaults
import asareon.raam.ui.components.sidepane.SidePaneState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * T1 Unit Tests for [SidePaneState].
 *
 * Covers pure state logic: lower-bound clamping, container-relative upper
 * clamping, drag accumulation without over-drag debt, and independence of
 * the width / height axes. No Compose runtime, no layout.
 */
class UiT1SidePaneStateTest {

    // ═══════════════════════════════════════════════════════════════════════
    // Construction
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `initial values above minSize are preserved`() {
        val state = SidePaneState(
            initialWidth = 300.dp,
            initialHeight = 200.dp,
            minSize = 20.dp,
        )
        assertEquals(300.dp, state.width)
        assertEquals(200.dp, state.height)
    }

    @Test
    fun `initial values below minSize are clamped up`() {
        val state = SidePaneState(
            initialWidth = 5.dp,
            initialHeight = 0.dp,
            minSize = 20.dp,
        )
        assertEquals(20.dp, state.width)
        assertEquals(20.dp, state.height)
    }

    @Test
    fun `minSize of zero is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SidePaneState(minSize = 0.dp)
        }
    }

    @Test
    fun `negative minSize is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SidePaneState(minSize = (-1).dp)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Raw setters — only the lower bound is enforced here
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `setting width above minSize succeeds without clamp`() {
        val state = SidePaneState(minSize = 20.dp)
        state.width = 9999.dp
        assertEquals(9999.dp, state.width)
    }

    @Test
    fun `setting width below minSize clamps up`() {
        val state = SidePaneState(initialWidth = 300.dp, minSize = 20.dp)
        state.width = 1.dp
        assertEquals(20.dp, state.width)
    }

    @Test
    fun `setting height below minSize clamps up`() {
        val state = SidePaneState(initialHeight = 200.dp, minSize = 20.dp)
        state.height = 5.dp
        assertEquals(20.dp, state.height)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // coerceWidthToContainer / coerceHeightToContainer
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `coerceWidthToContainer preserves a width already inside bounds`() {
        val state = SidePaneState(initialWidth = 300.dp, minSize = 20.dp)
        state.coerceWidthToContainer(containerWidth = 1000.dp)
        assertEquals(300.dp, state.width)
    }

    @Test
    fun `coerceWidthToContainer pulls an overlarge width down to container minus minSize`() {
        val state = SidePaneState(initialWidth = 900.dp, minSize = 20.dp)
        state.coerceWidthToContainer(containerWidth = 500.dp)
        // effectiveMax = 500 - 20 = 480
        assertEquals(480.dp, state.width)
    }

    @Test
    fun `coerceWidthToContainer pins width to minSize when container is too small for both`() {
        // 30dp container, 20dp minSize → container - minSize = 10dp, clamped
        // up to minSize (20dp) → pane pinned at 20dp, overflowing the
        // container. Documented behaviour; the layout is responsible for
        // clipping beyond this point.
        val state = SidePaneState(initialWidth = 300.dp, minSize = 20.dp)
        state.coerceWidthToContainer(containerWidth = 30.dp)
        assertEquals(20.dp, state.width)
    }

    @Test
    fun `coerceHeightToContainer works symmetrically`() {
        val state = SidePaneState(initialHeight = 500.dp, minSize = 20.dp)
        state.coerceHeightToContainer(containerHeight = 300.dp)
        assertEquals(280.dp, state.height)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // dragWidthBy / dragHeightBy
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `dragWidthBy positive grows the pane`() {
        val state = SidePaneState(initialWidth = 300.dp, minSize = 20.dp)
        state.dragWidthBy(deltaDp = 25.dp, containerWidth = 1000.dp)
        assertEquals(325.dp, state.width)
    }

    @Test
    fun `dragWidthBy negative shrinks the pane`() {
        val state = SidePaneState(initialWidth = 300.dp, minSize = 20.dp)
        state.dragWidthBy(deltaDp = (-50).dp, containerWidth = 1000.dp)
        assertEquals(250.dp, state.width)
    }

    @Test
    fun `dragWidthBy clamps at minSize`() {
        val state = SidePaneState(initialWidth = 25.dp, minSize = 20.dp)
        state.dragWidthBy(deltaDp = (-500).dp, containerWidth = 1000.dp)
        assertEquals(20.dp, state.width)
    }

    @Test
    fun `dragWidthBy clamps at containerWidth minus minSize`() {
        val state = SidePaneState(initialWidth = 900.dp, minSize = 20.dp)
        state.dragWidthBy(deltaDp = 500.dp, containerWidth = 1000.dp)
        assertEquals(980.dp, state.width)
    }

    @Test
    fun `drag past upper bound does not accumulate debt`() {
        // Drag way past the effective max, then drag back a small amount.
        // The pane should start shrinking from the boundary immediately —
        // not sit pinned until some accumulated "overdrag" is repaid.
        val state = SidePaneState(initialWidth = 900.dp, minSize = 20.dp)
        state.dragWidthBy(deltaDp = 5000.dp, containerWidth = 1000.dp)
        assertEquals(980.dp, state.width)
        state.dragWidthBy(deltaDp = (-30).dp, containerWidth = 1000.dp)
        assertEquals(950.dp, state.width)
    }

    @Test
    fun `drag past lower bound does not accumulate debt`() {
        val state = SidePaneState(initialWidth = 25.dp, minSize = 20.dp)
        state.dragWidthBy(deltaDp = (-5000).dp, containerWidth = 1000.dp)
        assertEquals(20.dp, state.width)
        state.dragWidthBy(deltaDp = 30.dp, containerWidth = 1000.dp)
        assertEquals(50.dp, state.width)
    }

    @Test
    fun `dragHeightBy works symmetrically`() {
        val state = SidePaneState(initialHeight = 200.dp, minSize = 20.dp)
        state.dragHeightBy(deltaDp = 30.dp, containerHeight = 800.dp)
        assertEquals(230.dp, state.height)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Axis independence
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `width and height track independently`() {
        val state = SidePaneState(
            initialWidth = 300.dp,
            initialHeight = 200.dp,
            minSize = 20.dp,
        )
        state.dragWidthBy(deltaDp = 100.dp, containerWidth = 1000.dp)
        assertEquals(400.dp, state.width)
        assertEquals(200.dp, state.height) // unchanged
        state.dragHeightBy(deltaDp = (-40).dp, containerHeight = 800.dp)
        assertEquals(400.dp, state.width) // unchanged
        assertEquals(160.dp, state.height)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Defaults (contract-pinning)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `default SidePaneState lands at defaults`() {
        val state = SidePaneState()
        assertEquals(SidePaneDefaults.DefaultPaneWidth, state.width)
        assertEquals(SidePaneDefaults.DefaultPaneHeight, state.height)
        assertEquals(SidePaneDefaults.MinPaneSize, state.minSize)
    }

    @Test
    fun `default values match the documented contract`() {
        // If you intentionally change these defaults, update this test AND
        // the note in SidePaneDefaults's KDoc — consumers read both.
        assertEquals(20.dp, SidePaneDefaults.MinPaneSize)
        assertEquals(320.dp, SidePaneDefaults.DefaultPaneWidth)
        assertEquals(240.dp, SidePaneDefaults.DefaultPaneHeight)
        assertEquals(840.dp, SidePaneDefaults.StackingBreakpoint)
    }
}
