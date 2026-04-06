package asareon.raam.util

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.*
import com.sun.jna.platform.win32.WinNT.HRESULT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window

/**
 * Subclasses the native window proc to return correct WM_NCHITTEST values for
 * our custom title bar. This gives us:
 *  - Windows 11 Snap Layouts flyout on maximize-button hover (HTMAXBUTTON)
 *  - Native window dragging via the title bar (HTCAPTION) with Aero Snap to edges
 *  - Native minimize / maximize / close on button click
 *  - Resize borders on all edges and corners
 *  - DWM rounded corners (Windows 11)
 *
 * Hit-test regions are computed from fixed layout dimensions (title bar height,
 * button width, ribbon width) passed at install time — no runtime tracking needed.
 *
 * NC mouse messages (drag, clicks on non-client buttons) are forwarded to
 * DefWindowProcW so the OS handles them natively, bypassing Java's event loop.
 *
 * Dependencies: com.sun.jna.* (already present for WindowsDarkMode)
 */
internal object WindowsSnapHelper {

    // ── Win32 constants ──────────────────────────────────────────────
    private const val GWL_WNDPROC = -4
    private const val GWL_STYLE = -16

    // Window styles required for snap layout and edge snapping
    private const val WS_MAXIMIZEBOX = 0x00010000
    private const val WS_MINIMIZEBOX = 0x00020000

    // Messages
    private const val WM_NCHITTEST = 0x0084
    private const val WM_NCCALCSIZE = 0x0083

    // Non-client mouse messages — forwarded to DefWindowProcW for native handling
    private const val WM_NCMOUSEMOVE = 0x00A0
    private const val WM_NCXBUTTONDBLCLK = 0x00AD  // end of NC mouse message range

    // Hit-test results
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

    // DWM
    private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
    private const val DWMWCP_ROUND = 2

    private const val RESIZE_BORDER_PX = 6

    // ── State ──────────────────────────────────────────────────────
    var isInstalled = false
        private set

    @Volatile private var installedCallback: WndProcCallback? = null
    @Volatile private var originalWndProc: Pointer? = null

    // ── JNA interfaces ─────────────────────────────────────────────

    interface WndProcCallback : StdCallLibrary.StdCallCallback {
        fun callback(hWnd: Pointer?, msg: Int, wParam: Pointer?, lParam: Pointer?): LRESULT
    }

    private interface NativeUser32 : StdCallLibrary {
        fun SetWindowLongPtrW(hWnd: Pointer?, nIndex: Int, wndProc: WndProcCallback): Pointer
        fun CallWindowProcW(prev: Pointer?, hWnd: Pointer?, msg: Int, wParam: Pointer?, lParam: Pointer?): LRESULT
        fun DefWindowProcW(hWnd: Pointer?, msg: Int, wParam: Pointer?, lParam: Pointer?): LRESULT
        fun SetWindowLongW(hWnd: Pointer?, nIndex: Int, dwNewLong: Int): Int
        fun GetWindowLongW(hWnd: Pointer?, nIndex: Int): Int

        companion object {
            val INSTANCE: NativeUser32 = Native.load("user32", NativeUser32::class.java)
        }
    }

    private interface Dwmapi : StdCallLibrary {
        fun DwmSetWindowAttribute(hwnd: HWND, dwAttribute: Int, pvAttribute: IntByReference, cbAttribute: Int): HRESULT
        companion object {
            val INSTANCE: Dwmapi by lazy { Native.load("dwmapi", Dwmapi::class.java) }
        }
    }

    // ── Installation ───────────────────────────────────────────────

    /**
     * Installs the custom window-proc subclass, adds required window styles,
     * and enables DWM rounded corners.
     *
     * @param window           the AWT Window (from Compose Desktop)
     * @param titleBarHeightPx height of the custom title bar **in physical pixels**
     * @param buttonWidthPx    width of each window-control button **in physical pixels**
     * @param ribbonWidthPx    width of the left ribbon + divider **in physical pixels**
     */
    fun install(
        window: Window,
        titleBarHeightPx: Int,
        buttonWidthPx: Int,
        ribbonWidthPx: Int
    ) {
        if (isInstalled) return
        try {
            val hwndPtr = Native.getComponentPointer(window)
            val hwnd = HWND(hwndPtr)

            // ── 1. Add window styles for snap layout + minimize support ──
            val style = NativeUser32.INSTANCE.GetWindowLongW(hwndPtr, GWL_STYLE)
            NativeUser32.INSTANCE.SetWindowLongW(hwndPtr, GWL_STYLE, style or WS_MAXIMIZEBOX or WS_MINIMIZEBOX)

            // ── 2. Enable DWM rounded corners (Windows 11+) ──
            try {
                Dwmapi.INSTANCE.DwmSetWindowAttribute(
                    hwnd,
                    DWMWA_WINDOW_CORNER_PREFERENCE,
                    IntByReference(DWMWCP_ROUND),
                    Int.SIZE_BYTES
                )
            } catch (_: Throwable) { /* pre-Win11, silently skip */ }

            // ── 3. Subclass the window proc ──
            val origLong = User32.INSTANCE.GetWindowLongPtr(hwnd, GWL_WNDPROC)
            originalWndProc = Pointer(origLong.toLong())

            val proc = object : WndProcCallback {
                override fun callback(hWnd: Pointer?, msg: Int, wParam: Pointer?, lParam: Pointer?): LRESULT {

                    // ── WM_NCHITTEST: tell Windows what region the cursor is over ──
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

                        // 1. Resize borders
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

                        // 2. Title bar region (right of the ribbon)
                        if (relY in 0 until titleBarHeightPx && relX >= ribbonWidthPx) {
                            val btnHit = when {
                                relX >= winW - buttonWidthPx -> HTCLOSE
                                relX >= winW - 2 * buttonWidthPx -> HTMAXBUTTON
                                relX >= winW - 3 * buttonWidthPx -> HTMINBUTTON
                                else -> HTCAPTION
                            }
                            return LRESULT(btnHit.toLong())
                        }

                        // 3. Everything else is client area
                        return LRESULT(HTCLIENT.toLong())
                    }

                    // ── WM_NCCALCSIZE: keep zero non-client area (no OS-drawn frame) ──
                    if (msg == WM_NCCALCSIZE) {
                        return LRESULT(0)
                    }

                    // ── NC mouse messages: forward to DefWindowProc for native handling ──
                    // This makes drag (HTCAPTION), snap, minimize, maximize, close
                    // all work via the OS rather than Java's event loop.
                    if (msg in WM_NCMOUSEMOVE..WM_NCXBUTTONDBLCLK) {
                        return NativeUser32.INSTANCE.DefWindowProcW(hWnd, msg, wParam, lParam)
                    }

                    // ── Everything else: pass to the original Java wndproc ──
                    return NativeUser32.INSTANCE.CallWindowProcW(
                        originalWndProc, hWnd, msg, wParam, lParam
                    )
                }
            }

            installedCallback = proc
            NativeUser32.INSTANCE.SetWindowLongPtrW(hwndPtr, GWL_WNDPROC, proc)
            isInstalled = true

        } catch (e: Throwable) {
            println("INFO: Windows snap layout helper not available (expected on non-Windows): ${e.message}")
        }
    }
}