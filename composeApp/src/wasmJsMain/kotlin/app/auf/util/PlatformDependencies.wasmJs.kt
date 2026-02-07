package app.auf.util

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
}