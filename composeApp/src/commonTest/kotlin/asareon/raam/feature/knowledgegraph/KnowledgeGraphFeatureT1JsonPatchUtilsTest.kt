package asareon.raam.feature.knowledgegraph

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tier 1 Unit Tests for JsonPatchUtils.
 *
 * Mandate (P-TEST-001, T1): To test the pure functions in JsonPatchUtils
 * in complete isolation to verify their contracts.
 *
 * Covers: parseJsonPointer, resolveJsonPointer, applyPatchOp, validatePatchOperations
 */
class KnowledgeGraphFeatureT1JsonPatchUtilsTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; isLenient = true }

    // =========================================================================
    // Shared test fixtures
    // =========================================================================

    private val sampleObject: JsonObject = buildJsonObject {
        put("header", buildJsonObject {
            put("id", "test-holon-20260101T000000Z")
            put("name", "Test Holon")
            put("type", "Project")
            put("summary", "A test holon.")
            put("filePath", "test/path.json")
            put("parentId", "parent-20260101T000000Z")
            put("depth", 1)
            put("created_at", "2026-01-01T00:00:00Z")
            put("modified_at", "2026-01-01T00:00:00Z")
            putJsonArray("sub_holons") {}
        })
        put("payload", buildJsonObject {
            put("title", "Original Title")
            put("status", "active")
            putJsonObject("nested") {
                put("deep_key", "deep_value")
                putJsonArray("items") {
                    add(JsonPrimitive("alpha"))
                    add(JsonPrimitive("beta"))
                    add(JsonPrimitive("gamma"))
                }
            }
            putJsonArray("tags") {
                add(JsonPrimitive("tag1"))
                add(JsonPrimitive("tag2"))
            }
        })
    }

    // =========================================================================
    // Test Group: parseJsonPointer
    // =========================================================================

    @Test
    fun `parseJsonPointer should parse a simple single-segment path`() {
        val segments = parseJsonPointer("/payload")
        assertEquals(listOf("payload"), segments)
    }

    @Test
    fun `parseJsonPointer should parse a multi-segment path`() {
        val segments = parseJsonPointer("/payload/nested/deep_key")
        assertEquals(listOf("payload", "nested", "deep_key"), segments)
    }

    @Test
    fun `parseJsonPointer should return empty list for empty string`() {
        val segments = parseJsonPointer("")
        assertEquals(emptyList(), segments)
    }

    @Test
    fun `parseJsonPointer should throw for path missing leading slash`() {
        assertFailsWith<IllegalArgumentException> {
            parseJsonPointer("payload/title")
        }
    }

    @Test
    fun `parseJsonPointer should handle tilde-1 escape for forward slash in key`() {
        // RFC 6901: ~1 encodes '/'
        val segments = parseJsonPointer("/payload/key~1with~1slashes")
        assertEquals(listOf("payload", "key/with/slashes"), segments)
    }

    @Test
    fun `parseJsonPointer should handle tilde-0 escape for tilde in key`() {
        // RFC 6901: ~0 encodes '~'
        val segments = parseJsonPointer("/payload/key~0with~0tildes")
        assertEquals(listOf("payload", "key~with~tildes"), segments)
    }

    @Test
    fun `parseJsonPointer should handle combined escapes in correct order`() {
        // RFC 6901: ~01 is '~1' (tilde then one), NOT '/'
        // The replacement order matters: ~1 -> '/' first, then ~0 -> '~'
        // Actually the implementation does replace("~1", "/").replace("~0", "~")
        // So ~01 -> after ~1 replacement: ~01 has no ~1 match at position 0...
        // Let's test: "~01" -> replace ~1 with / -> "~0/" ... wait, "~01" contains "~0" then "1"
        // Let me think: the string is "~01". Scanning for "~1": is there a ~1? At index 0 we have ~0, at index 1 we have 01.
        // Actually "~01" as a string: char 0 = '~', char 1 = '0', char 2 = '1'
        // replace("~1", "/"): does the substring "~1" appear? No — we have "~0" then "1". No match.
        // replace("~0", "~"): "~01" -> "~1"
        // Hmm, that gives us "~1" which is wrong — it should be "~1" as a literal.
        // Actually per RFC 6901, the correct decoding order is: first replace ~1->/, then ~0->~
        // The string ~01 should decode to: ~01 -> (no ~1 found) -> ~01 -> replace ~0 -> ~1
        // So "~01" decodes to the literal string "~1". That's correct per the RFC.
        val segments = parseJsonPointer("/payload/~01")
        assertEquals(listOf("payload", "~1"), segments)
    }

    @Test
    fun `parseJsonPointer should handle array index segment`() {
        val segments = parseJsonPointer("/payload/items/0")
        assertEquals(listOf("payload", "items", "0"), segments)
    }

    @Test
    fun `parseJsonPointer should handle array append token`() {
        val segments = parseJsonPointer("/payload/tags/-")
        assertEquals(listOf("payload", "tags", "-"), segments)
    }

    // =========================================================================
    // Test Group: resolveJsonPointer
    // =========================================================================

    @Test
    fun `resolveJsonPointer should resolve a top-level key`() {
        val result = resolveJsonPointer(sampleObject, listOf("payload"))
        assertNotNull(result)
        assertTrue(result is JsonObject)
    }

    @Test
    fun `resolveJsonPointer should resolve a nested key`() {
        val result = resolveJsonPointer(sampleObject, listOf("payload", "nested", "deep_key"))
        assertNotNull(result)
        assertEquals("deep_value", result.jsonPrimitive.content)
    }

    @Test
    fun `resolveJsonPointer should resolve an array element by index`() {
        val result = resolveJsonPointer(sampleObject, listOf("payload", "nested", "items", "1"))
        assertNotNull(result)
        assertEquals("beta", result.jsonPrimitive.content)
    }

    @Test
    fun `resolveJsonPointer should return null for non-existent key`() {
        val result = resolveJsonPointer(sampleObject, listOf("payload", "nonexistent"))
        assertNull(result)
    }

    @Test
    fun `resolveJsonPointer should return null for out-of-bounds array index`() {
        val result = resolveJsonPointer(sampleObject, listOf("payload", "nested", "items", "99"))
        assertNull(result)
    }

    @Test
    fun `resolveJsonPointer should return null for non-numeric array index`() {
        val result = resolveJsonPointer(sampleObject, listOf("payload", "nested", "items", "abc"))
        assertNull(result)
    }

    @Test
    fun `resolveJsonPointer should return null when traversing into a primitive`() {
        val result = resolveJsonPointer(sampleObject, listOf("payload", "title", "sub"))
        assertNull(result)
    }

    @Test
    fun `resolveJsonPointer should resolve root with empty segments`() {
        val result = resolveJsonPointer(sampleObject, emptyList())
        assertEquals(sampleObject, result)
    }

    // =========================================================================
    // Test Group: applyPatchOp — replace
    // =========================================================================

    @Test
    fun `applyPatchOp replace should update an existing key in an object`() {
        val result = applyPatchOp(
            sampleObject, listOf("payload", "title"), "replace", JsonPrimitive("New Title")
        ) as JsonObject
        assertEquals("New Title", result["payload"]?.jsonObject?.get("title")?.jsonPrimitive?.content)
    }

    @Test
    fun `applyPatchOp replace should update a deeply nested key`() {
        val result = applyPatchOp(
            sampleObject, listOf("payload", "nested", "deep_key"), "replace", JsonPrimitive("new_deep")
        ) as JsonObject
        assertEquals("new_deep", result["payload"]?.jsonObject?.get("nested")?.jsonObject?.get("deep_key")?.jsonPrimitive?.content)
    }

    @Test
    fun `applyPatchOp replace should update an array element by index`() {
        val result = applyPatchOp(
            sampleObject, listOf("payload", "nested", "items", "0"), "replace", JsonPrimitive("ALPHA")
        ) as JsonObject
        val items = result["payload"]?.jsonObject?.get("nested")?.jsonObject?.get("items")?.jsonArray
        assertNotNull(items)
        assertEquals("ALPHA", items[0].jsonPrimitive.content)
        assertEquals("beta", items[1].jsonPrimitive.content)  // others unchanged
    }

    @Test
    fun `applyPatchOp replace should throw for non-existent key`() {
        assertFailsWith<IllegalArgumentException> {
            applyPatchOp(sampleObject, listOf("payload", "nonexistent"), "replace", JsonPrimitive("val"))
        }
    }

    @Test
    fun `applyPatchOp replace should replace an entire object value`() {
        val newNested = buildJsonObject { put("completely", "different") }
        val result = applyPatchOp(
            sampleObject, listOf("payload", "nested"), "replace", newNested
        ) as JsonObject
        assertEquals("different", result["payload"]?.jsonObject?.get("nested")?.jsonObject?.get("completely")?.jsonPrimitive?.content)
    }

    // =========================================================================
    // Test Group: applyPatchOp — add
    // =========================================================================

    @Test
    fun `applyPatchOp add should create a new key in an object`() {
        val result = applyPatchOp(
            sampleObject, listOf("payload", "new_field"), "add", JsonPrimitive("new_value")
        ) as JsonObject
        assertEquals("new_value", result["payload"]?.jsonObject?.get("new_field")?.jsonPrimitive?.content)
    }

    @Test
    fun `applyPatchOp add should overwrite an existing key in an object`() {
        val result = applyPatchOp(
            sampleObject, listOf("payload", "title"), "add", JsonPrimitive("Overwritten")
        ) as JsonObject
        assertEquals("Overwritten", result["payload"]?.jsonObject?.get("title")?.jsonPrimitive?.content)
    }

    @Test
    fun `applyPatchOp add should append to an array using dash token`() {
        val result = applyPatchOp(
            sampleObject, listOf("payload", "tags", "-"), "add", JsonPrimitive("tag3")
        ) as JsonObject
        val tags = result["payload"]?.jsonObject?.get("tags")?.jsonArray
        assertNotNull(tags)
        assertEquals(3, tags.size)
        assertEquals("tag3", tags[2].jsonPrimitive.content)
    }

    @Test
    fun `applyPatchOp add should insert at a specific array index`() {
        val result = applyPatchOp(
            sampleObject, listOf("payload", "nested", "items", "1"), "add", JsonPrimitive("inserted")
        ) as JsonObject
        val items = result["payload"]?.jsonObject?.get("nested")?.jsonObject?.get("items")?.jsonArray
        assertNotNull(items)
        assertEquals(4, items.size)  // was 3, now 4
        assertEquals("alpha", items[0].jsonPrimitive.content)
        assertEquals("inserted", items[1].jsonPrimitive.content)
        assertEquals("beta", items[2].jsonPrimitive.content)
        assertEquals("gamma", items[3].jsonPrimitive.content)
    }

    // =========================================================================
    // Test Group: applyPatchOp — remove
    // =========================================================================

    @Test
    fun `applyPatchOp remove should delete an existing key from an object`() {
        val result = applyPatchOp(
            sampleObject, listOf("payload", "status"), "remove", null
        ) as JsonObject
        assertNull(result["payload"]?.jsonObject?.get("status"))
    }

    @Test
    fun `applyPatchOp remove should delete an array element by index`() {
        val result = applyPatchOp(
            sampleObject, listOf("payload", "tags", "0"), "remove", null
        ) as JsonObject
        val tags = result["payload"]?.jsonObject?.get("tags")?.jsonArray
        assertNotNull(tags)
        assertEquals(1, tags.size)
        assertEquals("tag2", tags[0].jsonPrimitive.content)
    }

    @Test
    fun `applyPatchOp remove should throw for non-existent key`() {
        assertFailsWith<IllegalArgumentException> {
            applyPatchOp(sampleObject, listOf("payload", "nonexistent"), "remove", null)
        }
    }

    @Test
    fun `applyPatchOp remove should throw for out-of-bounds array index`() {
        assertFailsWith<IllegalArgumentException> {
            applyPatchOp(sampleObject, listOf("payload", "tags", "99"), "remove", null)
        }
    }

    @Test
    fun `applyPatchOp remove should throw when attempting to remove root`() {
        assertFailsWith<IllegalArgumentException> {
            applyPatchOp(sampleObject, emptyList(), "remove", null)
        }
    }

    // =========================================================================
    // Test Group: applyPatchOp — error cases
    // =========================================================================

    @Test
    fun `applyPatchOp should throw for unknown operation type`() {
        assertFailsWith<IllegalArgumentException> {
            applyPatchOp(sampleObject, listOf("payload", "title"), "move", JsonPrimitive("val"))
        }
    }

    @Test
    fun `applyPatchOp should throw when traversing into a primitive`() {
        assertFailsWith<IllegalArgumentException> {
            applyPatchOp(sampleObject, listOf("payload", "title", "sub"), "replace", JsonPrimitive("val"))
        }
    }

    @Test
    fun `applyPatchOp should throw for non-numeric array index`() {
        assertFailsWith<IllegalArgumentException> {
            applyPatchOp(sampleObject, listOf("payload", "tags", "abc"), "replace", JsonPrimitive("val"))
        }
    }

    // =========================================================================
    // Test Group: applyPatchOp — immutability verification
    // =========================================================================

    @Test
    fun `applyPatchOp should not mutate the original tree`() {
        val originalTitle = sampleObject["payload"]?.jsonObject?.get("title")?.jsonPrimitive?.content
        applyPatchOp(sampleObject, listOf("payload", "title"), "replace", JsonPrimitive("Changed"))
        // Original should be unchanged
        assertEquals(originalTitle, sampleObject["payload"]?.jsonObject?.get("title")?.jsonPrimitive?.content)
    }

    // =========================================================================
    // Test Group: validatePatchOperations — valid operations
    // =========================================================================

    @Test
    fun `validatePatchOperations should accept a valid replace op`() {
        val ops = buildJsonArray {
            addJsonObject {
                put("op", "replace")
                put("path", "/payload/title")
                put("value", "New Title")
            }
        }
        val (validated, errors) = validatePatchOperations(ops, sampleObject)
        assertTrue(errors.isEmpty(), "Expected no errors but got: $errors")
        assertEquals(1, validated.size)
        assertEquals("replace", validated[0].op)
        assertEquals(listOf("payload", "title"), validated[0].path)
    }

    @Test
    fun `validatePatchOperations should accept a valid add op`() {
        val ops = buildJsonArray {
            addJsonObject {
                put("op", "add")
                put("path", "/payload/new_field")
                put("value", "new_value")
            }
        }
        val (validated, errors) = validatePatchOperations(ops, sampleObject)
        assertTrue(errors.isEmpty(), "Expected no errors but got: $errors")
        assertEquals(1, validated.size)
        assertEquals("add", validated[0].op)
    }

    @Test
    fun `validatePatchOperations should accept a valid remove op`() {
        val ops = buildJsonArray {
            addJsonObject {
                put("op", "remove")
                put("path", "/payload/status")
            }
        }
        val (validated, errors) = validatePatchOperations(ops, sampleObject)
        assertTrue(errors.isEmpty(), "Expected no errors but got: $errors")
        assertEquals(1, validated.size)
        assertEquals("remove", validated[0].op)
    }

    @Test
    fun `validatePatchOperations should accept a valid array append op`() {
        val ops = buildJsonArray {
            addJsonObject {
                put("op", "add")
                put("path", "/payload/tags/-")
                put("value", "tag3")
            }
        }
        val (validated, errors) = validatePatchOperations(ops, sampleObject)
        assertTrue(errors.isEmpty(), "Expected no errors but got: $errors")
        assertEquals(1, validated.size)
    }

    @Test
    fun `validatePatchOperations should accept multiple valid ops`() {
        val ops = buildJsonArray {
            addJsonObject {
                put("op", "replace")
                put("path", "/payload/title")
                put("value", "Updated")
            }
            addJsonObject {
                put("op", "add")
                put("path", "/payload/new_key")
                put("value", "new")
            }
            addJsonObject {
                put("op", "remove")
                put("path", "/payload/status")
            }
        }
        val (validated, errors) = validatePatchOperations(ops, sampleObject)
        assertTrue(errors.isEmpty(), "Expected no errors but got: $errors")
        assertEquals(3, validated.size)
    }

    @Test
    fun `validatePatchOperations should accept replace on writable header fields`() {
        val ops = buildJsonArray {
            addJsonObject {
                put("op", "replace")
                put("path", "/header/name")
                put("value", "New Name")
            }
            addJsonObject {
                put("op", "replace")
                put("path", "/header/summary")
                put("value", "New Summary")
            }
        }
        val (validated, errors) = validatePatchOperations(ops, sampleObject)
        assertTrue(errors.isEmpty(), "Expected no errors but got: $errors")
        assertEquals(2, validated.size)
    }

    // =========================================================================
    // Test Group: validatePatchOperations — error cases
    // =========================================================================

    @Test
    fun `validatePatchOperations should reject invalid op type`() {
        val ops = buildJsonArray {
            addJsonObject {
                put("op", "move")
                put("path", "/payload/title")
                put("value", "x")
            }
        }
        val (validated, errors) = validatePatchOperations(ops, sampleObject)
        assertTrue(validated.isEmpty())
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("'op' must be one of"))
    }

    @Test
    fun `validatePatchOperations should reject missing path`() {
        val ops = buildJsonArray {
            addJsonObject {
                put("op", "replace")
                put("value", "x")
            }
        }
        val (validated, errors) = validatePatchOperations(ops, sampleObject)
        assertTrue(validated.isEmpty())
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("Missing required 'path'"))
    }

    @Test
    fun `validatePatchOperations should reject path without leading slash`() {
        val ops = buildJsonArray {
            addJsonObject {
                put("op", "replace")
                put("path", "payload/title")
                put("value", "x")
            }
        }
        val (validated, errors) = validatePatchOperations(ops, sampleObject)
        assertTrue(validated.isEmpty())
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("Invalid path"))
    }

    @Test
    fun `validatePatchOperations should reject replace without value`() {
        val ops = buildJsonArray {
            addJsonObject {
                put("op", "replace")
                put("path", "/payload/title")
            }
        }
        val (validated, errors) = validatePatchOperations(ops, sampleObject)
        assertTrue(validated.isEmpty())
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("requires a 'value'"))
    }

    @Test
    fun `validatePatchOperations should reject add without value`() {
        val ops = buildJsonArray {
            addJsonObject {
                put("op", "add")
                put("path", "/payload/new_field")
            }
        }
        val (validated, errors) = validatePatchOperations(ops, sampleObject)
        assertTrue(validated.isEmpty())
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("requires a 'value'"))
    }

    @Test
    fun `validatePatchOperations should reject remove with value`() {
        val ops = buildJsonArray {
            addJsonObject {
                put("op", "remove")
                put("path", "/payload/status")
                put("value", "should not be here")
            }
        }
        val (validated, errors) = validatePatchOperations(ops, sampleObject)
        assertTrue(validated.isEmpty())
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("must not include a 'value'"))
    }

    @Test
    fun `validatePatchOperations should reject replace on non-existent path`() {
        val ops = buildJsonArray {
            addJsonObject {
                put("op", "replace")
                put("path", "/payload/does_not_exist")
                put("value", "x")
            }
        }
        val (validated, errors) = validatePatchOperations(ops, sampleObject)
        assertTrue(validated.isEmpty())
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("does not exist"))
    }

    @Test
    fun `validatePatchOperations should reject root path`() {
        val ops = buildJsonArray {
            addJsonObject {
                put("op", "replace")
                put("path", "/")
                put("value", "x")
            }
        }
        val (validated, errors) = validatePatchOperations(ops, sampleObject)
        assertTrue(validated.isEmpty())
        // The root path "/" parses to a single empty string segment ""
        // This may hit either the "root path" check or path resolution failure
        assertTrue(errors.isNotEmpty())
    }

    @Test
    fun `validatePatchOperations should reject non-JSON-object operation element`() {
        val ops = buildJsonArray {
            add(JsonPrimitive("not an object"))
        }
        val (validated, errors) = validatePatchOperations(ops, sampleObject)
        assertTrue(validated.isEmpty())
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("Not a valid JSON object"))
    }

    // =========================================================================
    // Test Group: validatePatchOperations — protected paths
    // =========================================================================

    @Test
    fun `validatePatchOperations should reject write to header id`() {
        val ops = buildJsonArray {
            addJsonObject {
                put("op", "replace")
                put("path", "/header/id")
                put("value", "hacked-id")
            }
        }
        val (validated, errors) = validatePatchOperations(ops, sampleObject)
        assertTrue(validated.isEmpty())
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("protected field"))
    }

    @Test
    fun `validatePatchOperations should reject write to header filePath`() {
        val ops = buildJsonArray {
            addJsonObject {
                put("op", "replace")
                put("path", "/header/filePath")
                put("value", "hacked/path.json")
            }
        }
        val (validated, errors) = validatePatchOperations(ops, sampleObject)
        assertTrue(validated.isEmpty())
        assertTrue(errors[0].contains("protected field"))
    }

    @Test
    fun `validatePatchOperations should reject write to header parentId`() {
        val ops = buildJsonArray {
            addJsonObject {
                put("op", "replace")
                put("path", "/header/parentId")
                put("value", "hacked-parent")
            }
        }
        val (validated, errors) = validatePatchOperations(ops, sampleObject)
        assertTrue(validated.isEmpty())
        assertTrue(errors[0].contains("protected field"))
    }

    @Test
    fun `validatePatchOperations should reject write to header depth`() {
        val ops = buildJsonArray {
            addJsonObject {
                put("op", "replace")
                put("path", "/header/depth")
                put("value", 999)
            }
        }
        val (validated, errors) = validatePatchOperations(ops, sampleObject)
        assertTrue(validated.isEmpty())
        assertTrue(errors[0].contains("protected field"))
    }

    @Test
    fun `validatePatchOperations should reject write to header sub_holons`() {
        val ops = buildJsonArray {
            addJsonObject {
                put("op", "replace")
                put("path", "/header/sub_holons")
                put("value", buildJsonArray {})
            }
        }
        val (validated, errors) = validatePatchOperations(ops, sampleObject)
        assertTrue(validated.isEmpty())
        assertTrue(errors[0].contains("protected field"))
    }

    @Test
    fun `validatePatchOperations should reject write to header created_at`() {
        val ops = buildJsonArray {
            addJsonObject {
                put("op", "replace")
                put("path", "/header/created_at")
                put("value", "2020-01-01T00:00:00Z")
            }
        }
        val (validated, errors) = validatePatchOperations(ops, sampleObject)
        assertTrue(validated.isEmpty())
        assertTrue(errors[0].contains("protected field"))
    }

    // =========================================================================
    // Test Group: validatePatchOperations — atomicity (all-or-nothing)
    // =========================================================================

    @Test
    fun `validatePatchOperations should reject ALL ops if any single op is invalid`() {
        val ops = buildJsonArray {
            // Op 0: valid
            addJsonObject {
                put("op", "replace")
                put("path", "/payload/title")
                put("value", "Updated")
            }
            // Op 1: INVALID — targets protected field
            addJsonObject {
                put("op", "replace")
                put("path", "/header/id")
                put("value", "hacked")
            }
            // Op 2: valid
            addJsonObject {
                put("op", "add")
                put("path", "/payload/new_key")
                put("value", "new")
            }
        }
        val (validated, errors) = validatePatchOperations(ops, sampleObject)
        assertTrue(validated.isEmpty(), "All ops must be rejected when one is invalid")
        assertEquals(1, errors.size, "Should report the one invalid op")
        assertTrue(errors[0].contains("[1]"), "Error should reference operation index 1")
    }

    @Test
    fun `validatePatchOperations should report ALL errors when multiple ops are invalid`() {
        val ops = buildJsonArray {
            // Op 0: invalid op type
            addJsonObject {
                put("op", "copy")
                put("path", "/payload/title")
            }
            // Op 1: missing value for replace
            addJsonObject {
                put("op", "replace")
                put("path", "/payload/title")
            }
            // Op 2: protected path
            addJsonObject {
                put("op", "replace")
                put("path", "/header/id")
                put("value", "x")
            }
        }
        val (validated, errors) = validatePatchOperations(ops, sampleObject)
        assertTrue(validated.isEmpty())
        assertEquals(3, errors.size, "Should report all 3 errors")
        assertTrue(errors[0].contains("[0]"))
        assertTrue(errors[1].contains("[1]"))
        assertTrue(errors[2].contains("[2]"))
    }

    // =========================================================================
    // Test Group: validatePatchOperations — array append validation
    // =========================================================================

    @Test
    fun `validatePatchOperations should reject array append when parent does not exist`() {
        val ops = buildJsonArray {
            addJsonObject {
                put("op", "add")
                put("path", "/payload/nonexistent_array/-")
                put("value", "item")
            }
        }
        val (validated, errors) = validatePatchOperations(ops, sampleObject)
        assertTrue(validated.isEmpty())
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("Cannot append"))
    }

    @Test
    fun `validatePatchOperations should reject array append when parent is not an array`() {
        val ops = buildJsonArray {
            addJsonObject {
                put("op", "add")
                put("path", "/payload/nested/-")
                put("value", "item")
            }
        }
        val (validated, errors) = validatePatchOperations(ops, sampleObject)
        assertTrue(validated.isEmpty())
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("not an array"))
    }
}