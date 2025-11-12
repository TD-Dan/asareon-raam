package app.auf.feature.knowledgegraph

import app.auf.util.PlatformDependencies
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; isLenient = true }

/**
 * A structured, type-safe representation of all possible validation failures that can occur
 * during the creation of a Holon from a source string. This allows for precise,
 * machine-readable error handling.
 */
sealed class HolonValidationError {
    /** The input string was not well-formed JSON or did not match the Holon schema. */
    data class MalformedJson(val error: String) : HolonValidationError()
    /** The `header.id` inside the JSON content does not match the file's name. */
    data class MismatchedId(val path: String, val expectedId: String, val foundId: String) : HolonValidationError()
    /** The ID (either from the filename or header) does not conform to the required format. */
    data class InvalidIdFormat(val message: String): HolonValidationError()
}

/**
 * A custom, self-contained sealed class to represent the outcome of the
 * holon creation process. It can hold either a successful result or a structured,
 * typed error, unlike the standard `kotlin.Result` which requires a `Throwable`.
 */
sealed class HolonCreationResult {
    data class Success(val holon: Holon) : HolonCreationResult()
    data class Failure(val error: HolonValidationError) : HolonCreationResult()
}


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
    // [THE FIX] Regex now expects an uppercase Z.
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

    // [THE FIX] Ensure the timestamp is uppercased for canonical format.
    return "$normalizedName-${timestampPart.uppercase()}"
}


/**
 * The sole, canonical gateway for creating a validated and normalized Holon object from a string.
 * This function is the primary defense against data corruption and inconsistency.
 *
 * @param rawContent The raw JSON string content from a file.
 * @param sourcePath The file path from which the content was read, used for the ID match validation.
 * @param platformDependencies Required to extract the filename from the path.
 * @return A `HolonCreationResult` object containing either the successfully validated and normalized `Holon` or a specific `HolonValidationError`.
 */
internal fun createHolonFromString(
    rawContent: String,
    sourcePath: String,
    platformDependencies: PlatformDependencies
): HolonCreationResult {
    // --- 1. Validation: Is it well-formed JSON? ---
    val holon = try {
        json.decodeFromString<Holon>(rawContent)
    } catch (e: Exception) {
        return HolonCreationResult.Failure(HolonValidationError.MalformedJson(e.message ?: "Unknown parsing error."))
    }

    // --- 2. Validation: Does the internal ID match the filename? ---
    val expectedId = platformDependencies.getFileName(sourcePath).removeSuffix(".json")
    if (holon.header.id != expectedId) {
        return HolonCreationResult.Failure(HolonValidationError.MismatchedId(sourcePath, expectedId, holon.header.id))
    }

    // --- 3. Normalization and Final Validation ---
    try {
        // Use the now-local, hardened normalization function on all relevant IDs.
        val normalizedId = normalizeHolonId(holon.header.id)
        val normalizedHeader = holon.header.copy(
            id = normalizedId,
            name = holon.header.name.trim(),
            summary = holon.header.summary?.trim(),
            relationships = holon.header.relationships.map { it.copy(targetId = normalizeHolonId(it.targetId)) },
            subHolons = holon.header.subHolons.map { it.copy(id = normalizeHolonId(it.id)) }
        )
        val normalizedHolon = holon.copy(header = normalizedHeader, rawContent = rawContent)
        return HolonCreationResult.Success(normalizedHolon)
    } catch (e: IllegalArgumentException) {
        return HolonCreationResult.Failure(HolonValidationError.InvalidIdFormat(e.message ?: "Invalid ID format."))
    }
}


/**
 * The canonical function for serializing a Holon to a string for file system persistence.
 * It serializes a minimal, clean representation of the holon, ensuring that runtime-only
 * fields like `rawContent` are not included in the file.
 *
 * @param holon The in-memory Holon object to prepare.
 * @return A pretty-printed JSON string.
 */
internal fun prepareHolonForWriting(holon: Holon): String {
    @kotlinx.serialization.Serializable
    data class SerializableHolon(
        val header: HolonHeader,
        val payload: kotlinx.serialization.json.JsonElement,
        val execute: kotlinx.serialization.json.JsonElement? = null
    )

    val serializable = SerializableHolon(
        header = holon.header,
        payload = holon.payload,
        execute = holon.execute
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