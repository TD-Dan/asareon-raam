package asareon.raam.util

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.*
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window

/**
 * Subclasses the native window proc to return correct WM_NCHITTEST values for
 * our custom title bar. This gives us:
 *  - Windows 11 Snap Layouts flyout on maximize-button hover
 *  - Native window dragging via the title bar (Aero Snap to edges)
 *  - Native minimize / maximize / close on button click
 *  - Resize borders on all edges and corners
 *
 * The Compose-side click/drag handlers in CustomTitleBar remain as a cross-platform fallback.
 * On Windows, the native handling takes priority and the Compose handlers are not reached.
 *
 * Dependencies: com.sun.jna.* (already present for WindowsDarkMode)
 */
internal object WindowsSnapHelper {

    // ── Win32 constants ──────────────────────────────────────────────
    private const val GWL_WNDPROC = -4
    private const val WM_NCHITTEST = 0x0084

    private const val HTCLIENT = 1
    private const val HTCAPTION = 2
    private const val HTMINBUTTON = 8
    private const val HTMAXBUTTON = 9
    private const val HTLEFT = 10
    private const val HTRIGHT = 11
    private const val HTTOP = 12
    private const val HTTOPLEFT = 13
    private const val HTTOPRIGHT = 14
    private const val HTBOTTOM = 15
    private const val HTBOTTOMLEFT = 16
    private const val HTBOTTOMRIGHT = 17
    private const val HTCLOSE = 20

    private const val RESIZE_BORDER_PX = 6

    // ── prevent GC of the callback ───────────────────────────────────
    @Volatile private var installedCallback: WndProcCallback? = null
    @Volatile private var originalWndProc: Pointer? = null

    /**
     * JNA stdcall callback matching the WNDPROC signature.
     */
    interface WndProcCallback : StdCallLibrary.StdCallCallback {
        fun callback(hWnd: Pointer?, msg: Int, wParam: Pointer?, lParam: Pointer?): LRESULT
    }

    /**
     * Minimal User32 surface for SetWindowLongPtrW (with Callback param)
     * and CallWindowProcW which aren't in JNA's stock User32 with these signatures.
     */
    private interface NativeUser32 : StdCallLibrary {
        fun SetWindowLongPtrW(hWnd: Pointer?, nIndex: Int, wndProc: WndProcCallback): Pointer
        fun CallWindowProcW(prev: Pointer?, hWnd: Pointer?, msg: Int, wParam: Pointer?, lParam: Pointer?): LRESULT

        companion object {
            val INSTANCE: NativeUser32 = Native.load("user32", NativeUser32::class.java)
        }
    }

    /**
     * Installs the custom window-proc subclass.
     *
     * @param window           the AWT Window (from Compose Desktop)
     * @param titleBarHeightPx height of the custom title bar **in physical pixels**
     * @param buttonWidthPx    width of each window-control button **in physical pixels**
     * @param ribbonWidthPx    width of the left ribbon + divider **in physical pixels**
     *                         (hit-test returns HTCLIENT for the ribbon area)
     */
    fun install(
        window: Window,
        titleBarHeightPx: Int,
        buttonWidthPx: Int,
        ribbonWidthPx: Int
    ) {
        try {
            val hwndPtr = Native.getComponentPointer(window)
            val hwnd = HWND(hwndPtr)

            val origLong = User32.INSTANCE.GetWindowLongPtr(hwnd, GWL_WNDPROC)
            originalWndProc = Pointer(origLong.toLong())

            val proc = object : WndProcCallback {
                override fun callback(hWnd: Pointer?, msg: Int, wParam: Pointer?, lParam: Pointer?): LRESULT {
                    if (msg == WM_NCHITTEST && hWnd != null) {
                        val lpVal = Pointer.nativeValue(lParam).toInt()
                        val screenX = (lpVal and 0xFFFF).toShort().toInt()
                        val screenY = ((lpVal shr 16) and 0xFFFF).toShort().toInt()

                        val rect = RECT()
                        User32.INSTANCE.GetWindowRect(hwnd, rect)
                        val relX = screenX - rect.left
                        val relY = screenY - rect.top
                        val winW = rect.right - rect.left
                        val winH = rect.bottom - rect.top

                        // ── 1. Resize borders (outermost ring) ──────────
                        val onLeft = relX < RESIZE_BORDER_PX
                        val onRight = relX >= winW - RESIZE_BORDER_PX
                        val onTop = relY < RESIZE_BORDER_PX
                        val onBottom = relY >= winH - RESIZE_BORDER_PX

                        val resizeHit = when {
                            onTop && onLeft -> HTTOPLEFT
                            onTop && onRight -> HTTOPRIGHT
                            onBottom && onLeft -> HTBOTTOMLEFT
                            onBottom && onRight -> HTBOTTOMRIGHT
                            onLeft -> HTLEFT
                            onRight -> HTRIGHT
                            onTop -> HTTOP
                            onBottom -> HTBOTTOM
                            else -> null
                        }
                        if (resizeHit != null) return LRESULT(resizeHit.toLong())

                        // ── 2. Title bar (right of the ribbon) ──────────
                        if (relY in 0 until titleBarHeightPx && relX >= ribbonWidthPx) {
                            val btnHit = when {
                                relX >= winW - buttonWidthPx -> HTCLOSE
                                relX >= winW - 2 * buttonWidthPx -> HTMAXBUTTON
                                relX >= winW - 3 * buttonWidthPx -> HTMINBUTTON
                                else -> HTCAPTION
                            }
                            return LRESULT(btnHit.toLong())
                        }

                        // ── 3. Everything else is client area ───────────
                        return LRESULT(HTCLIENT.toLong())
                    }

                    return NativeUser32.INSTANCE.CallWindowProcW(
                        originalWndProc, hWnd, msg, wParam, lParam
                    )
                }
            }

            installedCallback = proc
            NativeUser32.INSTANCE.SetWindowLongPtrW(hwndPtr, GWL_WNDPROC, proc)

        } catch (e: Throwable) {
            println("INFO: Windows snap layout helper not available (expected on non-Windows): ${e.message}")
        }
    }
}