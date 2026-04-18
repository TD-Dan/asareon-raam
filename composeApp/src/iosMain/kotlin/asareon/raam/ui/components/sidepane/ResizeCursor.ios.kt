package asareon.raam.ui.components.sidepane

import androidx.compose.ui.input.pointer.PointerIcon

// iOS has no OS resize-cursor; touch-only platform. Fall back to default.
actual val horizontalResizeCursor: PointerIcon = PointerIcon.Default
actual val verticalResizeCursor: PointerIcon = PointerIcon.Default
