package app.auf.feature.knowledgegraph

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tier 1 Unit Test for HolonOperations.
 *
 * Mandate (P-TEST-001, T1): To test the pure functions in HolonOperations
 * in complete isolation to verify their contracts.
 */
class KnowledgeGraphFeatureT1HolonOperationsTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; isLenient = true }

    @Test
    fun `prepareHolonForWriting should serialize header and payload but exclude rawContent`() {
        // Arrange
        val richHeader = HolonHeader(
            id = "test-holon", type = "Test", name = "Test Holon",
            filePath = "path/file.json", parentId = "parent", depth = 1
        )
        val inMemoryHolon = Holon(
            header = richHeader,
            payload = buildJsonObject { put("key", "value") },
            rawContent = "This is raw content that should be discarded during serialization."
        )

        // Act
        val jsonString = prepareHolonForWriting(inMemoryHolon)
        val parsedJson = json.parseToJsonElement(jsonString).jsonObject

        // Assert
        assertFalse(parsedJson.containsKey("rawContent"), "The serialized output must not contain the 'rawContent' field.")
        val parsedHeader = parsedJson["header"]?.jsonObject
        assertNotNull(parsedHeader, "Serialized output must contain a header.")
        assertEquals("path/file.json", parsedHeader["filePath"]?.jsonPrimitive?.content)
        assertEquals("parent", parsedHeader["parentId"]?.jsonPrimitive?.content)
        assertEquals(1, parsedHeader["depth"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `synchronizeRawContent should update rawContent to match structured data`() {
        // Arrange
        val desyncedHolon = Holon(
            header = HolonHeader(id = "h1", type = "T", name = "New Name"), // Name is "New Name"
            payload = buildJsonObject { put("key", "value") },
            rawContent = """{"header":{"name":"Old Name"},"payload":{}}""" // Raw content is stale
        )

        // Act
        val syncedHolon = synchronizeRawContent(desyncedHolon)
        val parsedRawContent = json.parseToJsonElement(syncedHolon.rawContent).jsonObject

        // Assert
        assertEquals("New Name", parsedRawContent["header"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
        assertEquals(syncedHolon.rawContent, prepareHolonForWriting(desyncedHolon))
    }
}