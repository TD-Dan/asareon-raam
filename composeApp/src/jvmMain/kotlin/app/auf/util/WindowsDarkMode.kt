package app.auf.util

import com.sun.jna.Native
import com.sun.jna.platform.win32.WinNT.HRESULT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.platform.win32.WinDef.HWND
import java.awt.Window

/**
 * An internal utility to manage dark mode for Windows title bars using JNA.
 * This directly calls the DwmSetWindowAttribute function from the Windows dwmapi.dll.
 *
 * Statement of Responsibility: To provide a single, safe function for enabling the
 * dark mode window attribute on a native Windows window handle.
 *
 * Dependencies:
 *  - com.sun.jna.* (for native access)
 *  - java.awt.Window (the Compose Desktop window object)
 */
internal object WindowsDarkMode {

    /**
     * JNA requires an interface that maps to the native DLL.
     * We define the single function we need from dwmapi.dll.
     */
    private interface Dwmapi : StdCallLibrary {
        fun DwmSetWindowAttribute(
            hwnd: HWND,
            dwAttribute: Int,
            pvAttribute: IntByReference,
            cbAttribute: Int
        ): HRESULT

        companion object {
            // This magic constant is taken from the Windows SDK and is used
            // by the Desktop Window Manager to control this specific attribute.
            const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
            val INSTANCE: Dwmapi by lazy { Native.load("dwmapi", Dwmapi::class.java) }
        }
    }

    /**
     * Tries to enable the dark mode title bar for the given AWT Window.
     */
    fun enable(window: Window) {
        try {
            // Get the native window handle (HWND) from the AWT window object.
            val hwnd = HWND(Native.getComponentPointer(window))

            // Create a pointer to an integer with the value 1 (true).
            val useDarkMode = IntByReference(1)

            // Call the native function.
            Dwmapi.INSTANCE.DwmSetWindowAttribute(
                hwnd,
                Dwmapi.DWMWA_USE_IMMERSIVE_DARK_MODE,
                useDarkMode,
                Int.SIZE_BYTES
            )
        } catch (e: Throwable) {
            // If anything goes wrong (e.g., not on Windows, DLL not found),
            // we just print an error and continue. The app must not crash.
            println("INFO: Failed to set Windows dark mode title bar (this is expected on non-Windows OS): ${e.message}")
        }
    }
}