package asareon.raam.ui.components.sidepane

import androidx.compose.ui.input.pointer.PointerIcon

/**
 * Platform-provided pointer cursor for a horizontal resize (east-west arrows).
 * Shown over the vertical drag gutter in a side-by-side [SidePaneLayout]
 * so users know the seam is grabbable.
 *
 * On desktop (JVM) this maps to the OS's native E-resize cursor via AWT.
 * On platforms without a native resize cursor concept (Android, iOS), this
 * falls back to [PointerIcon.Default] — dragging still works, just without
 * the visual hint.
 */
expect val horizontalResizeCursor: PointerIcon

/**
 * Platform-provided pointer cursor for a vertical resize (north-south arrows).
 * Shown over the horizontal drag gutter in a stacked [SidePaneLayout].
 *
 * Fallback rules match [horizontalResizeCursor].
 */
expect val verticalResizeCursor: PointerIcon
