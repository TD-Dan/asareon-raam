package asareon.raam.util

/**
 * Placeholder Wasm/JS implementation of PlatformDependencies.
 */
actual open class PlatformDependencies {
    actual open fun readFileContent(filePath: String): String {
        return "Error: Not implemented on Wasm"
    }

    actual open fun formatIsoTimestamp(timestamp: Long): String {
        return timestamp.toString()
    }

    actual open fun parseIsoTimestamp(timestamp: String): Long? {
        return null
    }

    @Suppress("DEPRECATION")
    actual open var logListener: ((LogLevel, String, String) -> Unit)? = null
    actual open fun addLogListener(id: String, listener: (LogLevel, String, String, Long) -> Unit) {}
    actual open fun removeLogListener(id: String) {}
    actual open fun getRecentLogs(limit: Int, minLevel: LogLevel): List<LogBufferEntry> = emptyList()
}