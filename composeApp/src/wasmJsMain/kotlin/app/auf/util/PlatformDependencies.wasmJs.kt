package app.auf.util

/**
 * Placeholder Wasm/JS implementation of PlatformDependencies.
 */
actual open class PlatformDependencies {
    actual open fun readFileContent(filePath: String): String {
        println("Wasm readFileContent not implemented for path: $filePath")
        return "Error: Not implemented on Wasm"
    }

    actual open fun formatIsoTimestamp(timestamp: Long): String {
        println("Wasm formatIsoTimestamp not implemented.")
        return timestamp.toString()
    }
}