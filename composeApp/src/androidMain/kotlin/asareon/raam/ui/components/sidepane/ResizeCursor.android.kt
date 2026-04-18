package asareon.raam.ui.components.sidepane

import androidx.compose.ui.input.pointer.PointerIcon

// Android has no OS resize-cursor; pointer hover hints do not apply to touch
// input. Fall back to the default cursor.
actual val horizontalResizeCursor: PointerIcon = PointerIcon.Default
actual val verticalResizeCursor: PointerIcon = PointerIcon.Default
