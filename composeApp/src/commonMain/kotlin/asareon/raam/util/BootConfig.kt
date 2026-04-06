package asareon.raam.util

/**
 * Reads and writes a minimal `boot.ini` file under APP_ZONE.
 * This file stores window dimensions so they can be loaded synchronously
 * before the Compose window is created — avoiding the jarring resize that
 * occurs when the full settings system loads asynchronously.
 *
 * Format: simple `key=value` lines. Unknown keys are preserved.
 */
object BootConfig {

    private const val FILE_NAME = "boot.ini"

    data class WindowSize(val width: Int, val height: Int)

    /**
     * Reads window dimensions from boot.ini.
     * Returns null if the file doesn't exist or can't be parsed.
     */
    fun readWindowSize(deps: PlatformDependencies): WindowSize? {
        return try {
            val path = "${deps.getBasePathFor(BasePath.APP_ZONE)}${deps.pathSeparator}$FILE_NAME"
            if (!deps.fileExists(path)) return null
            val lines = deps.readFileContent(path).lines()
            val map = lines
                .filter { '=' in it }
                .associate { line ->
                    val (k, v) = line.split('=', limit = 2)
                    k.trim() to v.trim()
                }
            val w = map["width"]?.toIntOrNull() ?: return null
            val h = map["height"]?.toIntOrNull() ?: return null
            WindowSize(w, h)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Writes window dimensions to boot.ini.
     */
    fun writeWindowSize(deps: PlatformDependencies, width: Int, height: Int) {
        try {
            val path = "${deps.getBasePathFor(BasePath.APP_ZONE)}${deps.pathSeparator}$FILE_NAME"
            deps.writeFileContent(path, "width=$width\nheight=$height\n")
        } catch (_: Exception) {
            // Non-critical — worst case the window starts at default size next launch.
        }
    }
}