package app.auf.feature.knowledgegraph

import app.auf.fakes.FakePlatformDependencies
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    private val platform = FakePlatformDependencies("test")

    // --- Test Group: normalizeHolonId ---

    @Test
    fun `normalizeHolonId should return a valid, sanitized ID`() {
        val rawId = "  My-!@#Valid-Name-123-20251112T183000Z  "
        val expected = "my-valid-name-123-20251112T183000Z"
        assertEquals(expected, normalizeHolonId(rawId))
    }

    @Test
    fun `normalizeHolonId should throw if timestamp is missing`() {
        assertFailsWith<IllegalArgumentException> {
            normalizeHolonId("just-a-name")
        }
    }

    @Test
    fun `normalizeHolonId should throw if hyphen separator is missing`() {
        assertFailsWith<IllegalArgumentException> {
            normalizeHolonId("nametimestamp20251112T183000Z")
        }
    }

    @Test
    fun `normalizeHolonId should throw if timestamp is malformed`() {
        assertFailsWith<IllegalArgumentException> {
            normalizeHolonId("name-2025-11-12T18:30:00Z")
        }
    }

    @Test
    fun `normalizeHolonId should throw if name part becomes empty after sanitization`() {
        assertFailsWith<IllegalArgumentException> {
            normalizeHolonId("!@#$-20251112T183000Z")
        }
    }

    // --- Test Group: createHolonFromString ---

    @Test
    fun `createHolonFromString should succeed and normalize a valid holon`() {
        // Arrange
        val rawContent = """
            {
                "header": {
                    "id": "My-Holon-1-20251112T190000Z",
                    "type": "Test", "name": "  My Holon 1  ",
                    "sub_holons": [{ "id": "Sub-Holon-2-20251112T190100Z", "type": "Sub", "summary": "s" }]
                },
                "payload": {}
            }
        """.trimIndent()
        val sourcePath = "My-Holon-1-20251112T190000Z.json"

        // Act
        val holon = createHolonFromString(rawContent, sourcePath, platform)

        // Assert
        assertEquals("my-holon-1-20251112T190000Z", holon.header.id)
        assertEquals("My Holon 1", holon.header.name) // name is trimmed but not lowercased
        assertEquals("sub-holon-2-20251112T190100Z", holon.header.subHolons.first().id)
        assertEquals(rawContent, holon.rawContent, "Original rawContent must be preserved.")
        assertEquals(sourcePath, holon.header.filePath, "File path must be enriched.")
    }

    @Test
    fun `createHolonFromString should throw for malformed JSON`() {
        val rawContent = """{ "header": { "id": "test-20251112T190000Z" },""" // Missing closing brace
        val sourcePath = "test-20251112T190000Z.json"

        val exception = assertFailsWith<HolonValidationException> {
            createHolonFromString(rawContent, sourcePath, platform)
        }
        assertTrue(exception.message!!.contains("Malformed JSON"))
    }

    @Test
    fun `createHolonFromString should throw for mismatched ID and filename`() {
        val rawContent = """{ "header": { "id": "actual-id-20251112T190000Z", "type": "T", "name": "N" }, "payload": {} }"""
        val sourcePath = "filename-id-20251112T190000Z.json"

        val exception = assertFailsWith<HolonValidationException> {
            createHolonFromString(rawContent, sourcePath, platform)
        }
        assertTrue(exception.message!!.contains("Mismatched ID"))
        assertTrue(exception.message!!.contains("actual-id-20251112T190000Z"))
        assertTrue(exception.message!!.contains("filename-id-20251112T190000Z"))
    }

    @Test
    fun `createHolonFromString should throw for invalid sub-holon ID format`() {
        val rawContent = """
            {
                "header": {
                    "id": "valid-parent-20251112T190000Z", "type": "T", "name": "N",
                    "sub_holons": [{ "id": "invalid-sub-holon", "type": "S", "summary": "" }]
                }, "payload": {}
            }
        """.trimIndent()
        val sourcePath = "valid-parent-20251112T190000Z.json"

        val exception = assertFailsWith<HolonValidationException> {
            createHolonFromString(rawContent, sourcePath, platform)
        }
        assertTrue(exception.message!!.contains("Invalid ID format"))
        assertTrue(exception.message!!.contains("invalid-sub-holon"))
    }


    // --- Test Group: Serialization ---

    @Test
    fun `prepareHolonForWriting should serialize header and payload but exclude rawContent`() {
        // Arrange
        val richHeader = HolonHeader(
            id = "test-holon-20251112T190000Z", type = "Test", name = "Test Holon",
            filePath = "path/file.json", parentId = "parent-20251112T180000Z", depth = 1
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
        assertEquals("parent-20251112T180000Z", parsedHeader["parentId"]?.jsonPrimitive?.content)
        assertEquals(1, parsedHeader["depth"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `synchronizeRawContent should update rawContent to match structured data`() {
        // Arrange
        val desyncedHolon = Holon(
            header = HolonHeader(id = "hl1-20251112T190000Z", type = "T", name = "New Name"), // Name is "New Name"
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