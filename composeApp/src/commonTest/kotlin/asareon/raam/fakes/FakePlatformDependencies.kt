package asareon.raam.fakes

import asareon.raam.util.BasePath
import asareon.raam.util.FileEntry
import asareon.raam.util.LogBufferEntry
import asareon.raam.util.LogLevel
import asareon.raam.util.PlatformDependencies

private data class FakeFile(val content: String, val lastModified: Long)

/**
 * A public data class to hold a captured log message for test assertions.
 * [REFACTOR] Now includes an optional throwable to enable more precise test assertions.
 */
data class CapturedLog(val level: LogLevel, val tag: String, val message: String, val throwable: Throwable? = null)

/**
 * A "Fake" implementation of the PlatformDependencies contract for use in unit tests.
 *
 * @property capturedLogs A public list to store all log messages for test assertions.
 */
open class FakePlatformDependencies(
    private val appVersion: String
) : PlatformDependencies(appVersion) {
    private val files = mutableMapOf<String, FakeFile>()
    val directories = mutableSetOf<String>()
    var clipboardContent: String? = null
    var currentTime = 1_000_000_000_000L
    var uuidCounter = 0
    val capturedLogs = mutableListOf<CapturedLog>()
    val writtenFiles = mutableMapOf<String, String>()
    var selectedDirectoryPathToReturn: String? = null
    val openedFolderPaths = mutableListOf<String>()
    var openFolderShouldThrow = false

    /** Tracks all scheduled callbacks for test assertions and manual firing. */
    data class ScheduledCallback(val delayMs: Long, val callback: () -> Unit, var cancelled: Boolean = false)
    val scheduledCallbacks = mutableListOf<ScheduledCallback>()

    override val pathSeparator: Char = '/'

    override fun readFileContent(path: String): String {
        return files[path]?.content ?: throw Exception("Fake file not found at path: $path")
    }

    override fun writeFileContent(path: String, content: String) {
        val parent = getParentDirectory(path)
        if (parent != null) {
            createDirectories(parent)
        }
        files[path] = FakeFile(content, currentTime)
        writtenFiles[path] = content
    }

    override fun readFileBytes(path: String): ByteArray {
        return readFileContent(path).encodeToByteArray()
    }

    override fun writeFileBytes(path: String, bytes: ByteArray) {
        writeFileContent(path, bytes.decodeToString())
    }

    override fun fileExists(path: String): Boolean {
        return files.containsKey(path) || directories.contains(path)
    }

    override fun listDirectory(path: String): List<FileEntry> {
        if (!directories.contains(path) && path != "/") throw Exception("Path does not exist '$path'")
        val directChildren = mutableSetOf<String>()
        val pathWithSeparator = if (path.endsWith(pathSeparator) || path == "/") path else "$path$pathSeparator"
        (files.keys + directories).forEach { entryPath ->
            if (entryPath.startsWith(pathWithSeparator) && entryPath != pathWithSeparator) {
                val relativePath = entryPath.removePrefix(pathWithSeparator)
                val firstSegment = relativePath.split(pathSeparator).first()
                if (firstSegment.isNotEmpty()) {
                    directChildren.add(firstSegment)
                }
            }
        }
        return directChildren.map { childName ->
            val fullPath = if (path == "/") "/$childName" else "$pathWithSeparator$childName".removeSuffix("/")
            val isDir = directories.contains(fullPath)
            val lastMod = if (isDir) null else files[fullPath]?.lastModified
            FileEntry(fullPath, isDir, lastModified = lastMod)
        }
    }

    override fun listDirectoryRecursive(path: String): List<FileEntry> {
        if (!directories.contains(path)) throw Exception("Path does not exist '$path'")
        val pathWithSeparator = if (path.endsWith(pathSeparator)) path else "$path$pathSeparator"
        return files.keys
            .filter { it.startsWith(pathWithSeparator) }
            .map { FileEntry(it, isDirectory = false, lastModified = files[it]?.lastModified) }
    }

    override fun createDirectories(path: String) {
        var currentPath = ""
        val parts = path.split(pathSeparator).filter { it.isNotEmpty() }
        if (path.startsWith(pathSeparator)) {
            currentPath = pathSeparator.toString()
            directories.add(currentPath)
        }
        for (part in parts) {
            currentPath = if (currentPath.endsWith(pathSeparator)) {
                "$currentPath$part"
            } else if (currentPath.isEmpty()){
                part
            } else {
                "$currentPath$pathSeparator$part"
            }
            directories.add(currentPath)
        }
    }

    override fun copyFile(sourcePath: String, destinationPath: String) {
        val sourceFile = files[sourcePath] ?: throw Exception("Fake source file not found: $sourcePath")
        files[destinationPath] = sourceFile.copy(lastModified = currentTime)
    }

    override fun deleteFile(path: String) {
        files.remove(path)
        writtenFiles.remove(path)
    }

    override fun deleteDirectory(path: String) {
        val pathWithSeparator = if (path.endsWith(pathSeparator)) path else "$path$pathSeparator"
        files.keys.removeAll { it.startsWith(pathWithSeparator) }
        writtenFiles.keys.removeAll { it.startsWith(pathWithSeparator) }
        directories.removeAll { it.startsWith(pathWithSeparator) }
        directories.remove(path)
    }

    override fun getBasePathFor(type: BasePath): String {
        return when (type) {
            BasePath.APP_ZONE -> "/fake/.auf/$appVersion"
            BasePath.USER_ZONE -> getUserHomePath()
        }
    }

    override fun getFileName(path: String): String {
        return path.substringAfterLast(pathSeparator)
    }

    override fun getParentDirectory(path: String): String? {
        if (!path.contains(pathSeparator) || path == "/") return null
        return path.substringBeforeLast(pathSeparator).ifEmpty { "/" }
    }

    override fun getLastModified(path: String): Long {
        return files[path]?.lastModified ?: directories.find { it == path }?.let { currentTime } ?: 0L
    }

    override fun getUserHomePath(): String = "/fake/user/home"

    override fun resolveAbsoluteSandboxPath(featureHandle: String, relativePath: String): String {
        return "${getBasePathFor(BasePath.APP_ZONE)}/$featureHandle/$relativePath"
    }

    override fun createZipArchive(
        sourceDirectoryPath: String,
        destinationZipPath: String,
        excludeDirectoryName: String,
        onProgress: ((bytesProcessed: Long, totalBytes: Long) -> Unit)?
    ) {
        // Simulate creating a zip by writing a marker file
        val parent = getParentDirectory(destinationZipPath)
        if (parent != null) createDirectories(parent)
        files[destinationZipPath] = FakeFile("ZIP_ARCHIVE:$sourceDirectoryPath:exclude=$excludeDirectoryName", currentTime)
    }

    /** Tracks all extract operations for test assertions. */
    data class ExtractedZip(val zipPath: String, val targetDirectoryPath: String)
    val extractedZips = mutableListOf<ExtractedZip>()

    override fun extractZipArchive(
        zipPath: String,
        targetDirectoryPath: String,
        onProgress: ((bytesProcessed: Long, totalBytes: Long) -> Unit)?
    ) {
        if (!files.containsKey(zipPath)) throw Exception("Fake zip file not found: $zipPath")
        extractedZips.add(ExtractedZip(zipPath, targetDirectoryPath))
    }

    var restartRequested = false
    override fun restartApplication() {
        restartRequested = true
    }

    override fun fileSize(path: String): Long {
        return files[path]?.content?.length?.toLong() ?: throw Exception("Fake file not found: $path")
    }

    override fun openFolderInExplorer(path: String) {
        if (openFolderShouldThrow) throw Exception("Fake openFolderInExplorer error for path: $path")
        openedFolderPaths.add(path)
    }
    override fun selectDirectoryPath(): String? = selectedDirectoryPathToReturn

    override fun currentTimeMillis(): Long = currentTime
    override fun generateUUID(): String {
        uuidCounter++
        // Produce a valid UUID v4 format: 8-4-4-4-12 hex digits.
        // The counter is embedded in the last segment for easy identification in tests.
        val hex = uuidCounter.toString(16).padStart(12, '0')
        return "00000000-0000-4000-a000-$hex"
    }
    override fun formatIsoTimestamp(timestamp: Long): String {
        val totalSeconds = floorDivLong(timestamp, 1000L)
        val totalDays = floorDivLong(totalSeconds, 86400L)
        val secondsOfDay = (totalSeconds - totalDays * 86400L).toInt()
        val h = secondsOfDay / 3600
        val m = (secondsOfDay / 60) % 60
        val s = secondsOfDay % 60
        val (y, mo, d) = civilFromDays(totalDays)
        return "${pad4(y)}-${pad2(mo)}-${pad2(d)}T${pad2(h)}:${pad2(m)}:${pad2(s)}Z"
    }
    override fun parseIsoTimestamp(timestamp: String): Long? {
        val match = ISO_8601_UTC_REGEX.matchEntire(timestamp) ?: return null
        val (yStr, moStr, dStr, hStr, mmStr, sStr) = match.destructured
        val y = yStr.toIntOrNull() ?: return null
        val mo = moStr.toInt()
        val d = dStr.toInt()
        val h = hStr.toInt()
        val mm = mmStr.toInt()
        val s = sStr.toInt()
        if (mo !in 1..12 || d !in 1..31 || h !in 0..23 || mm !in 0..59 || s !in 0..59) return null
        val days = daysFromCivil(y, mo, d)
        return (days * 86400L + h * 3600L + mm * 60L + s) * 1000L
    }
    override fun formatDisplayTimestamp(timestamp: Long): String = "DISPLAY_TIMESTAMP_$timestamp"
    override fun copyToClipboard(text: String) { clipboardContent = text }
    override fun applyNativeWindowDecorations(window: Any) { /* No-op */ }

    // --- Scheduling ---

    override fun scheduleDelayed(delayMs: Long, callback: () -> Unit): Any? {
        val entry = ScheduledCallback(delayMs, callback)
        scheduledCallbacks.add(entry)
        // Return the index as a handle for cancellation
        return scheduledCallbacks.size - 1
    }

    override fun cancelScheduled(handle: Any?) {
        val index = handle as? Int ?: return
        if (index in scheduledCallbacks.indices) {
            scheduledCallbacks[index] = scheduledCallbacks[index].copy(cancelled = true)
        }
    }

    /**
     * Test utility: fires all pending (non-cancelled) scheduled callbacks immediately.
     * Useful for simulating timeout scenarios in tests.
     */
    fun fireAllScheduledCallbacks() {
        scheduledCallbacks.filter { !it.cancelled }.forEach { it.callback() }
    }

    /**
     * Test utility: fires only scheduled callbacks with the specified delay.
     */
    fun fireScheduledCallbacks(delayMs: Long) {
        scheduledCallbacks.filter { !it.cancelled && it.delayMs == delayMs }.forEach { it.callback() }
    }

    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        capturedLogs.add(CapturedLog(level, tag, message, throwable))
        val timestamp = currentTimeMillis()
        logBuffer.add(LogBufferEntry(level, tag, message, timestamp))
        if (logBuffer.size > 1000) logBuffer.removeAt(0)
        logListenerMap.values.forEach { it(level, tag, message, timestamp) }
    }

    @Suppress("DEPRECATION")
    override var logListener: ((LogLevel, String, String) -> Unit)? = null

    private val logListenerMap = mutableMapOf<String, (LogLevel, String, String, Long) -> Unit>()
    private val logBuffer = mutableListOf<LogBufferEntry>()

    override fun addLogListener(id: String, listener: (LogLevel, String, String, Long) -> Unit) {
        logListenerMap[id] = listener
    }

    override fun removeLogListener(id: String) {
        logListenerMap.remove(id)
    }

    override fun getRecentLogs(limit: Int, minLevel: LogLevel): List<LogBufferEntry> {
        return logBuffer.filter { it.level >= minLevel }.takeLast(limit)
    }
}

private val ISO_8601_UTC_REGEX = Regex("^(-?\\d{4,})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})Z$")

private fun pad2(n: Int): String = if (n in 0..9) "0$n" else n.toString()

private fun pad4(n: Int): String = when {
    n < 0 -> n.toString()
    n >= 1000 -> n.toString()
    n >= 100 -> "0$n"
    n >= 10 -> "00$n"
    else -> "000$n"
}

private fun floorDivLong(a: Long, b: Long): Long {
    val q = a / b
    return if ((a xor b) < 0L && q * b != a) q - 1 else q
}

/**
 * Howard Hinnant's civil_from_days: converts days-since-epoch (1970-01-01) into a
 * proleptic Gregorian (year, month, day) triple. Works for any Long in practical range.
 */
private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
    val z = days + 719468L
    val era = (if (z >= 0L) z else z - 146096L) / 146097L
    val doe = (z - era * 146097L).toInt() // [0, 146096]
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365 // [0, 399]
    val y = (era * 400L + yoe.toLong()).toInt()
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100) // [0, 365]
    val mp = (5 * doy + 2) / 153 // [0, 11]
    val d = doy - (153 * mp + 2) / 5 + 1 // [1, 31]
    val m = if (mp < 10) mp + 3 else mp - 9 // [1, 12]
    return Triple(if (m <= 2) y + 1 else y, m, d)
}

/**
 * Howard Hinnant's days_from_civil: inverse of [civilFromDays]. Returns days from the
 * 1970-01-01 epoch for a proleptic Gregorian date.
 */
private fun daysFromCivil(y: Int, m: Int, d: Int): Long {
    val yAdj = if (m <= 2) y - 1 else y
    val era = (if (yAdj >= 0) yAdj else yAdj - 399) / 400
    val yoe = yAdj - era * 400 // [0, 399]
    val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1 // [0, 365]
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy // [0, 146096]
    return era.toLong() * 146097L + doe.toLong() - 719468L
}