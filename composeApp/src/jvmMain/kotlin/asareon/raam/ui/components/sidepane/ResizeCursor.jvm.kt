package asareon.raam.ui.components.sidepane

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

actual val horizontalResizeCursor: PointerIcon =
    PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))

actual val verticalResizeCursor: PointerIcon =
    PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR))
