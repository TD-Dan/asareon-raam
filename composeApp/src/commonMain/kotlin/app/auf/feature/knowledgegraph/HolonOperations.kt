package app.auf.feature.knowledgegraph

import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; isLenient = true }

/**
 * Takes a parent Holon's JSON content string and a reference to a new child,
 * and returns a new JSON content string with the child reference added.
 * This is a pure function.
 *
 * It returns the original content if the child already exists in the sub_holons list
 * or if the parent content is malformed JSON.
 *
 * @param parentContent The raw JSON string of the parent Holon.
 * @param childRef The SubHolonRef object of the child to add.
 * @return The updated JSON string of the parent Holon.
 */
internal fun addSubHolonRefToContent(parentContent: String, childRef: SubHolonRef): String {
    return try {
        val parentHolon = json.decodeFromString<Holon>(parentContent)
        // Ensure idempotency: do not add a duplicate reference.
        if (parentHolon.header.subHolons.any { it.id == childRef.id }) {
            return parentContent
        }
        val updatedSubHolons = parentHolon.header.subHolons + childRef
        val updatedHeader = parentHolon.header.copy(subHolons = updatedSubHolons)
        val updatedHolon = parentHolon.copy(header = updatedHeader)
        prepareHolonForWriting(updatedHolon)
    } catch (e: Exception) {
        // Fail gracefully if the parent content is not a valid Holon.
        // TODO: add error reporting. We cannot fail silently!
        // TODO: add unit test(s) to verify correct behaviour!
        // TODO: re-raise exception!
        parentContent
    }
}

/**
 * Takes an in-memory Holon object and prepares it for file system persistence.
 * This function creates a 'clean' version of the holon by:
 * 1. Retaining the enriched runtime metadata (`filePath`, `parentId`, `depth`).
 * 2. Setting the `content` field to an empty string to prevent recursive serialization.
 * This is the canonical function for serializing a holon before writing it to disk.
 *
 * @param holon The in-memory Holon object to prepare.
 * @return A pretty-printed JSON string representation of the holon.
 */
internal fun prepareHolonForWriting(holon: Holon): String {
    // Create a copy, explicitly clearing the content field before serialization.
    val cleanHolon = holon.copy(content = "")
    return json.encodeToString(Holon.serializer(), cleanHolon)
}