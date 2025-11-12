package app.auf.feature.knowledgegraph

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Tier 1 Unit Test for HolonOperations.
 *
 * Mandate (P-TEST-001, T1): To test the pure functions in HolonOperations
 * in complete isolation to verify their contracts.
 */
class KnowledgeGraphT1HolonOperationsTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; isLenient = true }

    @Test
    fun `prepareHolonForWriting should serialize enriched header fields and remove content field`() {
        // Arrange
        val richHeader = HolonHeader(
            id = "test-holon",
            type = "Test",
            name = "Test Holon",
            filePath = "persona/test-holon/test-holon.json",
            parentId = "persona",
            depth = 1
        )
        val inMemoryHolon = Holon(
            header = richHeader,
            payload = kotlinx.serialization.json.buildJsonObject { put("key", "value") },
            content = "This is raw content that should be discarded during serialization."
        )

        // Act
        val jsonString = prepareHolonForWriting(inMemoryHolon)
        val parsedJson = json.parseToJsonElement(jsonString).jsonObject

        // Assert
        // 1. Assert that the redundant 'content' field is NOT present.
        assertFalse(parsedJson.containsKey("content"), "The serialized output must not contain the 'content' field.")

        // 2. Assert that the enriched header fields ARE present.
        val parsedHeader = parsedJson["header"]?.jsonObject
        assertNotNull(parsedHeader, "Serialized output must contain a header.")
        assertEquals("persona/test-holon/test-holon.json", parsedHeader["filePath"]?.jsonPrimitive?.content)
        assertEquals("persona", parsedHeader["parentId"]?.jsonPrimitive?.content)
        assertEquals(1, parsedHeader["depth"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `addSubHolonRefToContent should correctly add a child reference`() {
        // Arrange
        val parentHolon = Holon(
            header = HolonHeader(id = "parent", type = "Parent", name = "Parent"),
            payload = kotlinx.serialization.json.buildJsonObject {}
        )
        val parentContent = prepareHolonForWriting(parentHolon)
        val childRef = SubHolonRef(id = "child", type = "Child", summary = "Child Summary")

        // Act
        val updatedParentContent = addSubHolonRefToContent(parentContent, childRef)
        val updatedParentHolon = json.decodeFromString<Holon>(updatedParentContent)

        // Assert
        assertEquals(1, updatedParentHolon.header.subHolons.size)
        assertEquals("child", updatedParentHolon.header.subHolons[0].id)
    }
}