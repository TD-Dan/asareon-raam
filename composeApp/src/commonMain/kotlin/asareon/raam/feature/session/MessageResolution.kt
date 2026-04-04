package app.auf.feature.session

import app.auf.util.PlatformDependencies

/**
 * Result of attempting to resolve a ledger entry by senderId + timestamp.
 */
data class MessageResolutionResult(
    /** The matched entry, or null if no match. */
    val entry: LedgerEntry?,
    /** A diagnostic error message if entry is null. */
    val errorMessage: String?
)

/**
 * Pure, testable helper for resolving a ledger entry by senderId + ISO timestamp.
 *
 * Handles the most common agent mistakes:
 * - Raw epoch millis instead of ISO 8601
 * - Correct sender, wrong timestamp (suggests closest)
 * - Correct timestamp, wrong sender (reveals actual sender — catches UUID vs display name confusion)
 * - Unknown sender (lists known senders)
 *
 * See design doc: P-AGENT-API-001, P-AGENT-API-002, P-AGENT-API-003
 */
object MessageResolution {

    fun resolve(
        ledger: List<LedgerEntry>,
        senderId: String,
        timestampStr: String,
        platformDependencies: PlatformDependencies
    ): MessageResolutionResult {

        // 1. Parse the incoming timestamp (ISO 8601 → epoch millis)
        val targetTimestamp = platformDependencies.parseIsoTimestamp(timestampStr)

        if (targetTimestamp == null) {
            // Heuristic: Did the agent send a raw unix millis instead of ISO?
            val asLong = timestampStr.toLongOrNull()
            if (asLong != null) {
                // Try matching with the raw value directly
                val match = ledger.find { it.senderId == senderId && it.timestamp == asLong }
                if (match != null) {
                    // Lucky match despite wrong format — return it but still works
                    return MessageResolutionResult(match, null)
                }
                val isoHint = platformDependencies.formatIsoTimestamp(asLong)
                return MessageResolutionResult(
                    null,
                    "Could not parse timestamp '$timestampStr'. " +
                            "It looks like a raw epoch value — use ISO 8601 format instead (e.g., '$isoHint')."
                )
            }
            return MessageResolutionResult(
                null,
                "Could not parse timestamp '$timestampStr'. Expected ISO 8601 format (e.g., '2025-02-07T18:40:00Z')."
            )
        }

        // 2. Exact match — compare via formatted ISO strings so both sides go through
        //    the same platform formatting pipeline (avoids domain mismatches between
        //    the stored millis and the parsed-back millis).
        val exactMatch = ledger.find {
            it.senderId == senderId &&
                    platformDependencies.formatIsoTimestamp(it.timestamp) == timestampStr
        }
        if (exactMatch != null) return MessageResolutionResult(exactMatch, null)

        // 3. No exact match — build "did you mean?" diagnostics
        val suggestions = mutableListOf<String>()

        // 3a. Right sender, wrong timestamp? Find closest by time.
        //     Compare formatted ISO strings directly to avoid domain mismatches
        //     between raw millis and parseIsoTimestamp output. Extracting digits
        //     from fixed-format ISO 8601 UTC strings gives a numeric representation
        //     where arithmetic distance ≈ chronological distance.
        val senderMessages = ledger.filter { it.senderId == senderId }
        if (senderMessages.isNotEmpty()) {
            val targetNumeric = timestampStr.filter { it.isDigit() }.toLongOrNull() ?: 0L
            val closest = senderMessages.minByOrNull {
                val entryIso = platformDependencies.formatIsoTimestamp(it.timestamp)
                val entryNumeric = entryIso.filter { ch -> ch.isDigit() }.toLongOrNull() ?: 0L
                kotlin.math.abs(entryNumeric - targetNumeric)
            }!!
            val closestIso = platformDependencies.formatIsoTimestamp(closest.timestamp)
            suggestions.add(
                "Found ${senderMessages.size} message(s) from '$senderId'. " +
                        "Closest timestamp: '$closestIso'."
            )
        }

        // 3b. Right timestamp, wrong sender? Maybe UUID vs display name confusion.
        val timestampMessages = ledger.filter {
            platformDependencies.formatIsoTimestamp(it.timestamp) == timestampStr
        }
        if (timestampMessages.isNotEmpty()) {
            val senderList = timestampMessages.map { it.senderId }.distinct().joinToString(", ")
            suggestions.add("Found message(s) at that timestamp from: $senderList.")
        }

        // 3c. No messages from this sender at all
        if (senderMessages.isEmpty()) {
            val knownSenders = ledger.map { it.senderId }.distinct().joinToString(", ")
            suggestions.add("No messages found from '$senderId'. Known senders in this session: $knownSenders.")
        }

        val errorMsg = "Message not found (senderId='$senderId', timestamp='$timestampStr'). " +
                suggestions.joinToString(" ")

        return MessageResolutionResult(null, errorMsg)
    }
}