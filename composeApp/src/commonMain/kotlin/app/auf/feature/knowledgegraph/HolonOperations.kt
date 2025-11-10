package app.auf.feature.knowledgegraph

import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; isLenient = true }

/**
 * Takes a parent Holon's JSON content string and a reference to a new child,
 * and returns a new JSON content string with the child reference added.
 * This is a pure function.
 *
 * It returns the original content if the child already exists in the sub_holons list
 * or if the parent content string is malformed JSON.
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
        json.encodeToString(Holon.serializer(), updatedHolon)
    } catch (e: Exception) {
        // Fail gracefully if the parent content is not a valid Holon.
        // TODO: add error reporting. We cannot fail silently!
        // TODO: add unit test(s) to verify correct behaviour!
        // TODO: re-raise exception!
        parentContent
    }
}

// TODO: Move to this file other holon specific operations, such as validations and checks!
