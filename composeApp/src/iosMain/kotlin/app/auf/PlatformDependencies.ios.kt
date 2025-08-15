// FILE: composeApp/src/iosMain/kotlin/app/auf/PlatformDependencies.ios.kt
package app.auf

/**
 * Placeholder iOS implementation of PlatformDependencies.
 * These will need real implementations to run on iOS.
 */
actual class PlatformDependencies {
    actual fun readFileContent(filePath: String): String {
        println("iOS readFileContent not implemented for path: $filePath")
        return "Error: Not implemented on iOS"
    }

    actual fun formatIsoTimestamp(timestamp: Long): String {
        // This could be implemented with platform-specific date formatters if needed.
        println("iOS formatIsoTimestamp not implemented.")
        return timestamp.toString()
    }
}