package asareon.raam.feature.knowledgegraph

import asareon.raam.util.LogLevel
import asareon.raam.util.PlatformDependencies
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; isLenient = true }

/**
 * Recursively canonicalize a JsonElement by sorting all object keys alphabetically.
 * Arrays keep their element order (semantic). Primitives pass through unchanged.
 *
 * Rationale: kotlinx.serialization emits JsonObject fields in iteration order,
 * which is the insertion order from the original parse. Two holon files with
 * identical semantics but different key order serialize to different bytes, so
 * round-trip comparison spuriously reports "Content differs." Canonicalizing
 * on write and on compare removes this source of drift.
 */
internal fun canonicalize(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.toSortedMap().mapValues { canonicalize(it.value) })
    is JsonArray -> JsonArray(element.map { canonicalize(it) })
    else -> element
}

/**
 * A custom exception to signal a failure during the validation or creation of a Holon.
 * This consolidates all potential parsing and validation errors into a single, specific type.
 */
class HolonValidationException(message: String) : Exception(message)


/**
 * The canonical function for normalizing and VALIDATING a holon ID.
 * It enforces the strict `name-YYYYMMDDTHHMMSSZ` format.
 *
 * @param id The raw ID string to be normalized and validated.
 * @return A clean, normalized, validated, and filesystem-safe holon ID string.
 * @throws IllegalArgumentException if the ID does not conform to the required structure or becomes
 *         invalid after sanitization.
 */
fun normalizeHolonId(id: String): String {
    val trimmedId = id.trim()
    val lastHyphenIndex = trimmedId.lastIndexOf('-')
    val timestampRegex = Regex("^\\d{8}T\\d{6}Z$")

    // 1. Structural Validation: Must have a hyphen separating name and timestamp.
    if (lastHyphenIndex == -1) {
        throw IllegalArgumentException("Invalid holon ID format: must contain a hyphen to separate name and timestamp. Received: '$id'")
    }

    val namePart = trimmedId.substring(0, lastHyphenIndex)
    val timestampPart = trimmedId.substring(lastHyphenIndex + 1)

    // 2. Structural Validation: The part after the hyphen must be a valid timestamp.
    if (!timestampRegex.matches(timestampPart)) {
        throw IllegalArgumentException("Invalid holon ID format: timestamp part is malformed. Expected 'YYYYMMDDTHHMMSSZ', received '$timestampPart'. Full ID: '$id'")
    }

    // 3. Sanitization: Clean the name part, allowing letters, numbers, and hyphens.
    val nameSanitizeRegex = Regex("[^a-z0-9-]")
    val normalizedName = namePart.lowercase().replace(nameSanitizeRegex, "")

    // 4. Content Validation: The name part must not be empty after cleaning.
    if (normalizedName.isBlank()) {
        throw IllegalArgumentException("Invalid holon ID: name part becomes empty after sanitization. Original: '$id'")
    }

    // 5. Content Validation: The name part must be at least 3 alphanumerics.
    val containsThreeAlphanumerics = Regex("^[a-z0-9].*[a-z0-9].*[a-z0-9].*")
    if (! normalizedName.matches(containsThreeAlphanumerics)) {
        throw IllegalArgumentException("Invalid holon ID: name should have at least 3 letters. Original: '$id'")
    }

    return "$normalizedName-${timestampPart.uppercase()}"
}


/**
 * Attempt to mechanically repair a malformed holon ID into the canonical
 * `name-YYYYMMDDTHHMMSSZ` shape. Returns the repaired ID if one of the known
 * recoverable patterns matches, or `null` if the ID requires human judgement
 * (e.g. no timestamp component at all, or the name part is missing).
 *
 * Handled cases (observed in legacy import corpora):
 *  - Trailing file-extension suffix on the id tail, e.g. `foo-20250902T190000Z.md` → strip `.md`.
 *  - Missing trailing `Z`, e.g. `foo-20250905T220000` → append `Z`.
 *  - Missing seconds (HHMM instead of HHMMSS), e.g. `foo-20250731T0925Z` → `foo-20250731T092500Z`.
 *  - Combinations of the above.
 *
 * Not handled (returns null — these require a user-guided repair tool):
 *  - No timestamp component at all, e.g. `legacy-deep-archive-1`.
 *  - Non-timestamp tails like `archive-record-202507`.
 *  - Filename-vs-header ID drift where both look valid but differ (content drift).
 */
internal fun repairHolonId(id: String): String? {
    val trimmed = id.trim()
    val lastHyphen = trimmed.lastIndexOf('-')
    if (lastHyphen == -1 || lastHyphen == trimmed.length - 1) return null

    val name = trimmed.substring(0, lastHyphen)
    var ts = trimmed.substring(lastHyphen + 1)

    // Strip a trailing file-extension suffix (seen in rogue sub-holon ids where
    // someone wrote a .md path as the id).
    val extMatch = Regex("\\.[a-zA-Z0-9]{1,5}$").find(ts)
    if (extMatch != null) ts = ts.substring(0, ts.length - extMatch.value.length)

    return when {
        ts.matches(Regex("^\\d{8}T\\d{6}Z$")) -> "$name-$ts"                    // already canonical
        ts.matches(Regex("^\\d{8}T\\d{6}$")) -> "$name-${ts}Z"                  // missing Z
        ts.matches(Regex("^\\d{8}T\\d{4}Z$")) -> "$name-${ts.substring(0, 13)}00Z"  // HHMMZ → HHMM00Z
        ts.matches(Regex("^\\d{8}T\\d{4}$")) -> "$name-${ts}00Z"                // HHMM → HHMM00Z
        else -> null
    }
}


/**
 * The sole, canonical gateway for creating a validated and normalized Holon object from a string.
 * This function is the primary defense against data corruption and inconsistency.
 *
 * Applies [repairHolonId] to the header id, filename-derived id, relationship targets, and
 * sub-holon refs before validation, so that mechanically-fixable legacy IDs (missing Z,
 * missing seconds, trailing extension suffix) load successfully instead of quarantining
 * the whole file. Non-trivially-broken IDs still throw.
 *
 * @param rawContent The raw JSON string content from a file.
 * @param sourcePath The file path from which the content was read, used for the ID match validation.
 * @param platformDependencies Required to extract the filename from the path.
 * @return A successfully validated and normalized `Holon` object.
 * @throws HolonValidationException if the content is malformed, IDs do not match, or any ID format is invalid.
 */
internal fun createHolonFromString(
    rawContent: String,
    sourcePath: String,
    platformDependencies: PlatformDependencies
): Holon {
    // --- 1. Validation: Is it well-formed JSON? ---
    val holon = try {
        json.decodeFromString<Holon>(rawContent)
    } catch (e: Exception) {
        throw HolonValidationException("Malformed JSON in '$sourcePath': ${e.message}")
    }

    // --- 2. Validation: Does the internal ID match the filename? (repair-aware) ---
    val expectedId = platformDependencies.getFileName(sourcePath).removeSuffix(".json")
    val repairedExpectedId = repairHolonId(expectedId) ?: expectedId
    val repairedHeaderId = repairHolonId(holon.header.id) ?: holon.header.id
    if (repairedHeaderId != repairedExpectedId) {
        throw HolonValidationException("Mismatched ID in '$sourcePath': File name implies ID '$expectedId' but header contains '${holon.header.id}'.")
    }
    if (repairedHeaderId != holon.header.id) {
        platformDependencies.log(LogLevel.INFO, "HolonRepair", "Repaired header id '${holon.header.id}' → '$repairedHeaderId' in '$sourcePath'")
    }

    // --- 3. Normalization and Final Validation ---
    // The header id itself must be valid — a holon with no id can't exist. Sub-holon
    // refs and relationship targets, however, are just pointers into the graph; an
    // irreparable one is a dangling legacy stub (seen in the wild: `dream-protocol-1`,
    // `legacy-deep-archive-1`, `long-dream-1`). Dropping those with a warning lets
    // the otherwise-valid parent load instead of quarantining it — which previously
    // cascaded the quarantine to dozens of legitimate children.
    val normalizedId = try {
        normalizeHolonId(repairedHeaderId)
    } catch (e: IllegalArgumentException) {
        throw HolonValidationException("Invalid ID format in '$sourcePath': ${e.message}")
    }

    val normalizedSubHolons = holon.header.subHolons.mapNotNull { s ->
        try {
            s.copy(id = normalizeHolonId(repairHolonId(s.id) ?: s.id))
        } catch (e: IllegalArgumentException) {
            platformDependencies.log(
                LogLevel.WARN, "HolonRepair",
                "Dropped unresolvable sub-holon ref '${s.id}' from '$sourcePath': ${e.message}"
            )
            null
        }
    }

    val normalizedRelationships = holon.header.relationships.mapNotNull { r ->
        try {
            r.copy(targetId = normalizeHolonId(repairHolonId(r.targetId) ?: r.targetId))
        } catch (e: IllegalArgumentException) {
            platformDependencies.log(
                LogLevel.WARN, "HolonRepair",
                "Dropped unresolvable relationship target '${r.targetId}' from '$sourcePath': ${e.message}"
            )
            null
        }
    }

    val normalizedHeader = holon.header.copy(
        id = normalizedId,
        name = holon.header.name.trim(),
        summary = holon.header.summary?.trim(),
        relationships = normalizedRelationships,
        subHolons = normalizedSubHolons,
        filePath = sourcePath
    )
    return holon.copy(header = normalizedHeader, rawContent = rawContent)
}


/**
 * Canonicalize a HolonHeader: sort sub_holons by id and relationships by (type, targetId).
 * Tree-view order is the view's responsibility (it sorts by name at render time), so the
 * on-disk order of sub_holons carries no semantic meaning. A stable on-disk order makes
 * write → read → write round-trips bitwise-stable, which is what the import's
 * content-diff relies on.
 */
internal fun canonicalizeHeader(header: HolonHeader): HolonHeader = header.copy(
    subHolons = header.subHolons.sortedBy { it.id },
    relationships = header.relationships.sortedWith(compareBy({ it.type }, { it.targetId }))
)

/**
 * The canonical function for serializing a Holon to a string for file system persistence.
 * It serializes a minimal, clean representation of the holon, ensuring that runtime-only
 * fields like `rawContent` are not included in the file. The output is canonicalized
 * (alphabetically-sorted JSON object keys, sorted sub_holons/relationships) so that a
 * write → read → write cycle produces byte-identical output — see `canonicalize` for why.
 *
 * @param holon The in-memory Holon object to prepare.
 * @return A pretty-printed, canonicalized JSON string.
 */
internal fun prepareHolonForWriting(holon: Holon): String {
    @kotlinx.serialization.Serializable
    data class SerializableHolon(
        val header: HolonHeader,
        val payload: JsonElement,
        val execute: JsonElement? = null
    )

    val serializable = SerializableHolon(
        header = canonicalizeHeader(holon.header),
        payload = canonicalize(holon.payload),
        execute = holon.execute?.let { canonicalize(it) }
    )

    return json.encodeToString(SerializableHolon.serializer(), serializable)
}

/**
 * Synchronizes a Holon's `rawContent` cache to match its structured data.
 * This is the canonical way to ensure consistency after a structured change (e.g., a rename).
 *
 * @param holon The potentially desynchronized Holon object.
 * @return A new, fully consistent Holon object.
 */
internal fun synchronizeRawContent(holon: Holon): Holon {
    val newRawContent = prepareHolonForWriting(holon)
    return holon.copy(rawContent = newRawContent)
}