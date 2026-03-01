package app.auf.feature.agent

import app.auf.util.PlatformDependencies
import kotlinx.serialization.json.*

/**
 * ## Mandate
 * A pure formatting utility that transforms a filesystem listing response payload
 * into a concise workspace context string for injection into an agent's system prompt.
 *
 * This object has NO filesystem access — it only formats data already retrieved
 * through the filesystem feature's security boundary.
 */
object WorkspaceContextProvider {

    /**
     * Formats a `filesystem.response.LIST` payload into a human-readable workspace summary.
     *
     * Output format (when files exist):
     * ```
     * --- WORKSPACE FILES ---
     * Your workspace has 7 files in 2 directories.
     * Latest 3:
     * notes/standup-2026-02-07.md (2026-02-07T10:30:00Z),
     * config.json (2026-02-06T14:22:00Z),
     * ...
     * Oldest 3: readme.md (2026-01-15T08:00:00Z),
     * templates/report.md (2026-01-20T11:00:00Z),
     * ...
     * ```
     *
     * Returns `"Your workspace is empty."` if no files are present.
     */
    fun formatListingResponse(payload: JsonObject, platformDeps: PlatformDependencies): String {
        val listing = payload["listing"]?.jsonArray ?: return "Your workspace is empty."

        // Parse entries into (path, isDirectory, lastModified) triples
        data class Entry(val path: String, val isDirectory: Boolean, val lastModified: Long?)

        val entries = listing.mapNotNull { element ->
            try {
                val obj = element.jsonObject
                val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val isDirectory = obj["isDirectory"]?.jsonPrimitive?.booleanOrNull ?: false
                val lastModified = obj["lastModified"]?.jsonPrimitive?.longOrNull
                Entry(path, isDirectory, lastModified)
            } catch (_: Exception) {
                null
            }
        }

        val files = entries.filter { !it.isDirectory }
        val directories = entries.filter { it.isDirectory }

        if (files.isEmpty()) {
            return "Your workspace is empty."
        }

        // Sort files by lastModified descending (most recent first), nulls last
        val sortedFiles = files.sortedByDescending { it.lastModified ?: 0L }

        val dirCount = directories.size
        // Also count implicit directories from file paths
        val implicitDirs = files.mapNotNull { file ->
            val sep = file.path.lastIndexOf('/')
            if (sep > 0) file.path.substring(0, sep) else null
        }.toSet()
        val totalDirCount = (directories.map { it.path }.toSet() + implicitDirs).size

        val summary = buildString {
            append("Your workspace has ${files.size} file${if (files.size != 1) "s" else ""}")
            if (totalDirCount > 0) {
                append(" in $totalDirCount director${if (totalDirCount != 1) "ies" else "y"}")
            }
            appendLine(".")

            // Newest 3
            val newest = sortedFiles.take(3)
            append("Latest ${newest.size}:\n")
            append(newest.joinToString(",\n") { formatEntry(it.path, it.lastModified, platformDeps) })
            appendLine()

            // Oldest 3 — only show if there are more than 3 files and the oldest set differs from newest
            if (sortedFiles.size > 3) {
                val oldest = sortedFiles.takeLast(3)
                // Check they're actually different from newest
                if (oldest != newest) {
                    append("Oldest ${oldest.size}:\n")
                    append(oldest.joinToString(",\n") { formatEntry(it.path, it.lastModified, platformDeps) })
                    appendLine()
                }
            }
        }

        return summary.trimEnd()
    }

    private fun formatEntry(path: String, lastModified: Long?, platformDeps: PlatformDependencies): String {
        // Normalize separators
        val normalizedPath = path.replace("\\", "/")
        // Strip the agent workspace prefix if still present (e.g., "agentId/workspace/")
        val cleanPath = normalizedPath
            .replace(Regex("^[^/]+/workspace/"), "")
            .removePrefix("/")

        val timestamp = if (lastModified != null && lastModified > 0) {
            platformDeps.formatIsoTimestamp(lastModified)
        } else {
            "unknown"
        }
        return "$cleanPath ($timestamp)"
    }
}