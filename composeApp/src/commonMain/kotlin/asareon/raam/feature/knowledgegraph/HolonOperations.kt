package asareon.raam.feature.knowledgegraph

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
 * The sole, canonical gateway for creating a validated and normalized Holon object from a string.
 * This function is the primary defense against data corruption and inconsistency.
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

    // --- 2. Validation: Does the internal ID match the filename? ---
    val expectedId = platformDependencies.getFileName(sourcePath).removeSuffix(".json")
    if (holon.header.id != expectedId) {
        throw HolonValidationException("Mismatched ID in '$sourcePath': File name implies ID '$expectedId' but header contains '${holon.header.id}'.")
    }

    // --- 3. Normalization and Final Validation ---
    try {
        val normalizedId = normalizeHolonId(holon.header.id)
        val normalizedHeader = holon.header.copy(
            id = normalizedId,
            name = holon.header.name.trim(),
            summary = holon.header.summary?.trim(),
            relationships = holon.header.relationships.map { it.copy(targetId = normalizeHolonId(it.targetId)) },
            subHolons = holon.header.subHolons.map { it.copy(id = normalizeHolonId(it.id)) },
            filePath = sourcePath
        )
        return holon.copy(header = normalizedHeader, rawContent = rawContent)
    } catch (e: IllegalArgumentException) {
        throw HolonValidationException("Invalid ID format in '$sourcePath': ${e.message}")
    }
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