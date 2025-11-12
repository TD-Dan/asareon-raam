package app.auf.feature.knowledgegraph

import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; isLenient = true }

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