// FILE: composeApp/src/wasmJsMain/kotlin/app/auf/PlatformDependencies.wasmJs.kt
package app.auf

/**
 * Placeholder Wasm/JS implementation of PlatformDependencies.
 */
actual class PlatformDependencies {
    actual fun readFileContent(filePath: String): String {
        println("Wasm readFileContent not implemented for path: $filePath")
        return "Error: Not implemented on Wasm"
    }

    actual fun formatIsoTimestamp(timestamp: Long): String {
        println("Wasm formatIsoTimestamp not implemented.")
        return timestamp.toString()
    }
}