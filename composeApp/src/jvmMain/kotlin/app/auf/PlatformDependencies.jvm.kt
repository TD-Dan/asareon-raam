package app.auf

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The actual JVM implementation of the PlatformDependencies contract.
 * It uses java.io and java.text to fulfill the requirements.
 */
actual class PlatformDependencies {
    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    actual fun readFileContent(filePath: String): String {
        return try {
            File(filePath).readText()
        } catch (e: Exception) {
            "Error reading file: $filePath. Details: ${e.message}"
        }
    }

    actual fun formatIsoTimestamp(timestamp: Long): String {
        return isoFormatter.format(Date(timestamp))
    }
}