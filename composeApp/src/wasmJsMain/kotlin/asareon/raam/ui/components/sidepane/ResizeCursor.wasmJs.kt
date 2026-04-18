package asareon.raam.ui.components.sidepane

import androidx.compose.ui.input.pointer.PointerIcon

// wasmJs target: Compose Multiplatform for Web maps PointerIcon to CSS
// cursor values, but there is no stable preset for E-resize / N-resize at
// time of writing. Fall back to the default until upstream adds one.
actual val horizontalResizeCursor: PointerIcon = PointerIcon.Default
actual val verticalResizeCursor: PointerIcon = PointerIcon.Default
